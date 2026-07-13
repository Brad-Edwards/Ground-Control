// gc_integration_manager: approved-PR integration manager MCP tool (GC-O011).
//
// Action-discriminated dispatcher:
//   plan    — discover approved PRs in the target repo, sort by configured
//             ordering, cap at max_queue_size, return the plan envelope.
//   prepare — rebase approved PRs onto the base branch one-by-one, run
//             the repo's completion gate, and push force-with-lease.
//   status  — read-only diagnostic: lock state + last run info.
//   release — idempotent lock release (removes .gc-integration-lock dir).
//
// Mode is a closed enum {prepare, enqueue, merge}.  Only "prepare" is
// executable under the current ADR set; "enqueue" and "merge" are refused at
// plan time (before any side effect) so the refusal is surfaced early.
//
// All external I/O (execFile, git, gh) is injected via the `deps` parameter
// so the module is fully unit-testable without a real Git repo or gh CLI.

import { execFile as execFileCb } from "node:child_process";
import { promisify } from "node:util";
import { join, isAbsolute } from "node:path";
import { readFileSync, mkdirSync, realpathSync, writeFileSync, rmSync, readdirSync, statSync } from "node:fs";
import {
  parseGroundControlYaml,
  isSafeLabelName,
  normalizeIntegrationManagerConfig,
  detectSensitiveBodyContent,
  ensureGitRepo,
  getOwnerRepo,
  INTEGRATION_MANAGER_ORDERINGS,
  INTEGRATION_MANAGER_MERGE_STRATEGIES,
  acquireIntegrationLock,
  assertRealpathInRepo,
  runWatchCiRun,
  runWatchSonarAnalysis,
} from "./lib.js";

const execFileAsync = promisify(execFileCb);

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const VALID_ACTIONS = ["plan", "prepare", "status", "release"];
const VALID_MODES = ["prepare", "enqueue", "merge"];
const DEFAULT_MODE = "prepare";
const DEFAULT_APPROVAL_LABEL = "approved-for-integration";
const DEFAULT_ORDERING = "pr_number_asc";
const DEFAULT_MAX_QUEUE_SIZE = 20;

// Maximum pages fetched from the GitHub Pulls API (100 PRs/page × 5 = 500).
// Exceeding this is refused with error:discovery_too_large rather than
// silently truncating.
const MAX_DISCOVERY_PAGES = 5;

// Maximum summary string length surfaced in per-PR outcome records.
const MAX_SUMMARY_LENGTH = 200;

// ---------------------------------------------------------------------------
// JSON schema exported for index.js registration.
// ---------------------------------------------------------------------------

export const GC_INTEGRATION_MANAGER_INPUT_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["action", "repo_path"],
  properties: {
    action: { enum: ["plan", "prepare", "status", "release"] },
    repo_path: { type: "string", minLength: 1 },
    mode: { enum: ["prepare", "enqueue", "merge"] },
  },
};

export const GC_INTEGRATION_MANAGER_DESCRIPTION =
  "Approved PR integration manager (GC-O011). " +
  "Action-discriminated: plan (discover + ordered queue), " +
  "prepare (rebase + run gates + force-with-lease push; with mode=merge also executes the merge per-PR), " +
  "status (read lock and last-run state; read-only), " +
  "release (idempotent integration lock release). " +
  "Mode is closed enum {prepare, enqueue, merge}; " +
  "merge is enabled via the ADR-029 carve-out (2026-05-26) and requires " +
  "workflow.integration_manager.merge_strategy in .ground-control.yaml. " +
  "enqueue remains reserved.";

// ---------------------------------------------------------------------------
// Production-default implementations for injectable dependencies.
// ---------------------------------------------------------------------------

/**
 * Production execFile wrapper: promisified child_process.execFile.
 * Tests replace this with a fake that records argv calls.
 */
async function defaultExecFile(file, argv, options) {
  return execFileAsync(file, argv, options);
}

/**
 * Read the .ground-control.yaml text from the repo root.  Throws with
 * `code: "ENOENT"` when the file is absent (mirrors fs.readFileSync).
 */
function defaultReadYaml(repoRoot) {
  // eslint-disable-next-line security/detect-non-literal-fs-filename
  return readFileSync(join(repoRoot, ".ground-control.yaml"), "utf-8");
}

/**
 * Production integration lock acquirer.
 */
async function defaultAcquireIntegrationLock(repoRoot, opts) {
  return acquireIntegrationLock(repoRoot, opts);
}

/**
 * Production halt-ledger writer.  Creates the run directory and writes the
 * halt.json file.  Tests can inject a spy or no-op to inspect calls.
 */
function defaultWriteHaltLedger(runDir, ledger) {
  // eslint-disable-next-line security/detect-non-literal-fs-filename
  mkdirSync(runDir, { recursive: true });
  // eslint-disable-next-line security/detect-non-literal-fs-filename
  writeFileSync(join(runDir, "halt.json"), JSON.stringify(ledger, null, 2), "utf-8");
}

// ---------------------------------------------------------------------------
// Watcher adapters
//
// The hook contract for the prepare loop is:
//   (pr, ctx, deps) => Promise<{conclusion: "success"|"failure"|"skipped"|"queued_too_long"|"timed_out", details_url?}>
//
// The real lib.js watchers have their own return envelopes.  These two
// adapter functions translate between the watcher envelope and the hook
// contract so the prepare loop stays simple.
// ---------------------------------------------------------------------------

/**
 * Production CI watcher adapter.  Calls runWatchCiRun from lib.js and maps
 * its envelope to the hook contract.
 *
 * runWatchCiRun returns:
 *   {ok, conclusion: "success"|"failure"|"queued_too_long"|"timed_out"|..., url?, ...}
 *
 * Mapping:
 *   conclusion "success"          → {conclusion: "success"}
 *   conclusion "failure"          → {conclusion: "failure", details_url: url}
 *   conclusion "queued_too_long"  → {conclusion: "queued_too_long"}
 *   conclusion "timed_out"        → {conclusion: "timed_out"}
 *   any other / error             → {conclusion: "skipped"} (non-fatal)
 */
async function defaultRunCiWatcher(pr, ctx) {
  const result = await runWatchCiRun({
    repoPath: ctx.repoRoot,
    branch: pr.head_ref,
  });

  if (!result.ok) {
    // watcher couldn't run (no CI config, etc.) — treat as skipped
    return { conclusion: "skipped" };
  }

  const c = result.conclusion;
  if (c === "success") return { conclusion: "success" };
  if (c === "failure") return { conclusion: "failure", details_url: result.url };
  if (c === "queued_too_long") return { conclusion: "queued_too_long" };
  if (c === "timed_out") return { conclusion: "timed_out" };
  // Any other conclusion (e.g. "skipped", "cancelled" mapped from CI): treat as skipped.
  return { conclusion: "skipped" };
}

/**
 * Production Sonar watcher adapter.  Calls runWatchSonarAnalysis from lib.js
 * and maps its envelope to the hook contract.
 *
 * runWatchSonarAnalysis returns:
 *   {ok, skipped?, quality_gate: "OK"|"ERROR"|"WARN"|"NONE", ...}
 *
 * Mapping:
 *   skipped:true                  → {conclusion: "skipped"}
 *   quality_gate "OK"             → {conclusion: "success"}
 *   quality_gate "ERROR"/"WARN"   → {conclusion: "failure"}
 *   any error / other             → {conclusion: "skipped"} (non-fatal)
 */
async function defaultRunSonarWatcher(pr, ctx) {
  const result = await runWatchSonarAnalysis({
    repoPath: ctx.repoRoot,
    prNumber: pr.pr_number,
  });

  if (!result.ok) {
    return { conclusion: "skipped" };
  }
  if (result.skipped) {
    return { conclusion: "skipped" };
  }

  const qg = result.quality_gate;
  if (qg === "OK") return { conclusion: "success" };
  if (qg === "ERROR" || qg === "WARN") return { conclusion: "failure" };
  // NONE or other — treat as skipped
  return { conclusion: "skipped" };
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Build an error envelope.  Scrubs the message for sensitive content before
 * returning — all user-surfaced strings pass through this gate.
 */
function errorEnvelope(error, message, next_action, extra = {}) {
  const sensitive = detectSensitiveBodyContent(message);
  return {
    ok: false,
    error,
    message: sensitive ? "<redacted>" : message,
    next_action,
    ...extra,
  };
}

/**
 * Scrub a string for sensitive content, replacing with "<redacted>" when a
 * pattern matches.
 */
function scrub(s) {
  if (typeof s !== "string") return s;
  return detectSensitiveBodyContent(s) ? "<redacted>" : s;
}

/**
 * Truncate a summary string to MAX_SUMMARY_LENGTH chars and scrub it.
 */
function safeSummary(s) {
  if (typeof s !== "string") return "";
  const truncated = s.length > MAX_SUMMARY_LENGTH ? s.slice(0, MAX_SUMMARY_LENGTH) : s;
  return scrub(truncated);
}

/**
 * Generate a run ID: <timestamp>-<random6>.
 * `deps.now` and `deps.randomId` are injectable for deterministic tests.
 */
function makeRunId(deps) {
  const ts = deps.now ? deps.now() : Date.now();
  const rand = deps.randomId ? deps.randomId() : Math.random().toString(36).slice(2, 8).padEnd(6, "0");
  return `${ts}-${rand}`;
}

/**
 * Classify a failure as needing a consultation_halt (true) vs. a simple
 * blocked outcome (false).  This is the v1 seam — intentionally small set.
 *
 * Consultation criteria:
 *   - PR head OID moved since discovery (force-with-lease lease mismatch).
 *   - Configured approval_label race (label differs mid-run).
 *
 * Everything else (worktree_create_failed, base_fetch_failed, rebase_conflict,
 * completion_gate_failed, ci_failed, sonar_skipped_but_configured) is NOT a
 * consultation — it is either a blocked or a queue_wide_halt.
 */
function classifyAsConsultation(failureClass) {
  return failureClass === "pr_head_moved" || failureClass === "approval_label_race";
}

// ---------------------------------------------------------------------------
// buildIntegrationQueue (extracted from runPlanAction for reuse by prepare)
// ---------------------------------------------------------------------------

/**
 * Discover and build the ordered queue of approved PRs.
 * Returns `{ok:true, queue:[...], owner, repo, policy}` or an error envelope.
 *
 * This is the shared inner logic that both runPlanAction and runPrepareAction
 * call.  Callers must have already validated mode (plan refuses non-prepare
 * modes before calling this).
 */
async function buildIntegrationQueue(args, deps) {
  const { execFile, ensureGitRepo: ensureRepo, getOwnerRepo: getOwner, readYaml } = deps;

  // ── Resolve and canonicalize repo path ───────────────────────────────────
  let repoRoot;
  try {
    repoRoot = await ensureRepo(args.repo_path);
  } catch (e) {
    return errorEnvelope(
      "invalid_repo_path",
      `repo_path is not a valid Git repository: ${e.message}`,
      "verify_repo_path",
    );
  }

  // ── Read and parse .ground-control.yaml ──────────────────────────────────
  let yamlText;
  try {
    yamlText = readYaml(repoRoot);
  } catch (e) {
    return errorEnvelope(
      "invalid_config",
      e.code === "ENOENT"
        ? ".ground-control.yaml not found at the repository root"
        : `.ground-control.yaml could not be read: ${e.message}`,
      "fix_ground_control_yaml",
    );
  }

  const parseResult = parseGroundControlYaml(yamlText);
  if (!parseResult.ok) {
    return errorEnvelope(
      "invalid_config",
      `Invalid .ground-control.yaml: ${parseResult.errors.join("; ")}`,
      "fix_ground_control_yaml",
    );
  }

  // ── Extract integration_manager config with defaults ──────────────────────
  const rawIM = parseResult.value.workflow?.integration_manager;
  const imResult = normalizeIntegrationManagerConfig(rawIM ?? null);
  if (!imResult.ok) {
    return errorEnvelope(
      "invalid_config",
      `workflow.integration_manager config errors: ${imResult.errors.join("; ")}`,
      "fix_ground_control_yaml",
    );
  }

  const imConfig = imResult.value;
  const approvalLabel = imConfig.approval_label ?? DEFAULT_APPROVAL_LABEL;
  const ordering = imConfig.ordering ?? DEFAULT_ORDERING;
  const maxQueueSize = imConfig.max_queue_size ?? DEFAULT_MAX_QUEUE_SIZE;

  // ── Defensive label validation ────────────────────────────────────────────
  if (!isSafeLabelName(approvalLabel)) {
    return errorEnvelope(
      "invalid_approval_label",
      `The resolved approval_label '${approvalLabel}' is not a safe label name`,
      "fix_ground_control_yaml",
    );
  }

  // ── Resolve owner/repo ────────────────────────────────────────────────────
  let owner, repo;
  try {
    ({ owner, name: repo } = await getOwner(repoRoot));
  } catch (e) {
    return errorEnvelope(
      "github_remote_not_resolved",
      `Could not resolve GitHub owner/repo: ${e.message}`,
      "verify_remote",
    );
  }

  // ── Validate configured identity against the checkout (GC-P026) ────────────
  // .ground-control.yaml::github_repo is an assertion, not an alternate
  // destination: if it disagrees with the checkout's origin remote, refuse
  // before any discovery or mutation rather than act on a stale or mistyped
  // identity. Compared case-insensitively (GitHub owner/repo are case-folding).
  const configuredRepo = parseResult.value.github_repo;
  if (configuredRepo && configuredRepo.toLowerCase() !== `${owner}/${repo}`.toLowerCase()) {
    return errorEnvelope(
      "github_identity_mismatch",
      `.ground-control.yaml github_repo '${configuredRepo}' does not match the checkout's origin remote '${owner}/${repo}'`,
      "fix_ground_control_yaml",
    );
  }

  // ── Paginated PR discovery (cap at MAX_DISCOVERY_PAGES pages) ─────────────
  const allPrs = [];
  let hitPageCap = false;

  for (let page = 1; page <= MAX_DISCOVERY_PAGES + 1; page++) {
    if (page > MAX_DISCOVERY_PAGES) {
      hitPageCap = true;
      break;
    }
    let stdout;
    try {
      const result = await execFile("gh", [
        "api",
        "-X",
        "GET",
        `/repos/${owner}/${repo}/pulls`,
        "--field",
        "state=open",
        "--field",
        "per_page=100",
        "--field",
        `page=${page}`,
      ]);
      stdout = result.stdout;
    } catch (e) {
      return errorEnvelope(
        "discovery_failed",
        `gh api call failed on page ${page}: ${e.message}`,
        "verify_remote",
      );
    }

    let pageData;
    try {
      pageData = JSON.parse(stdout);
    } catch (e) {
      return errorEnvelope(
        "discovery_failed",
        `Could not parse GitHub API response on page ${page}: ${e.message}`,
        "contact_support",
      );
    }

    if (!Array.isArray(pageData) || pageData.length === 0) {
      break;
    }

    allPrs.push(...pageData);

    if (pageData.length < 100) {
      break;
    }

    if (page === MAX_DISCOVERY_PAGES) {
      // Will set hitPageCap on next iteration.
    }
  }

  if (hitPageCap) {
    return errorEnvelope(
      "discovery_too_large",
      `More than ${MAX_DISCOVERY_PAGES * 100} open PRs were found; narrow the search using a more specific approval_label`,
      "narrow_approval_label",
    );
  }

  // ── Client-side label filter ──────────────────────────────────────────────
  const approved = allPrs.filter((pr) =>
    Array.isArray(pr.labels) &&
    pr.labels.some((l) => l.name === approvalLabel),
  );

  // ── Queue cap check ───────────────────────────────────────────────────────
  if (approved.length > maxQueueSize) {
    return errorEnvelope(
      "queue_too_large",
      `${approved.length} approved PRs exceed the configured max_queue_size of ${maxQueueSize}`,
      "narrow_approval_label",
    );
  }

  // ── Ordering ──────────────────────────────────────────────────────────────
  const sorted = [...approved];
  if (ordering === "pr_number_asc") {
    sorted.sort((a, b) => a.number - b.number);
  } else if (ordering === "pr_number_desc") {
    sorted.sort((a, b) => b.number - a.number);
  } else if (ordering === "approved_at_asc") {
    sorted.sort((a, b) => a.created_at.localeCompare(b.created_at));
  }

  // ── Build queue entries ───────────────────────────────────────────────────
  const queue = sorted.map((pr, idx) => {
    const headFullName = pr.head?.repo?.full_name ?? null;
    const baseFullName = pr.base?.repo?.full_name ?? null;
    const headIsFork = headFullName !== null && baseFullName !== null
      ? headFullName !== baseFullName
      : false;
    const [headRepoOwner, headRepoName] = headFullName != null
      ? headFullName.split("/")
      : [owner, repo];
    return {
      ordinal: idx + 1,
      pr_number: pr.number,
      head_ref: scrub(pr.head.ref),
      head_oid: scrub(pr.head.sha),
      base_ref: scrub(pr.base.ref),
      head_repo_owner: scrub(headRepoOwner),
      head_repo_name: scrub(headRepoName),
      head_is_fork: headIsFork,
      created_at: scrub(pr.created_at),
      updated_at: scrub(pr.updated_at),
    };
  });

  return {
    ok: true,
    repoRoot,
    owner,
    repo,
    cfg: parseResult.value,
    policy: {
      approval_label: approvalLabel,
      ordering,
      max_queue_size: maxQueueSize,
    },
    queue,
  };
}

// ---------------------------------------------------------------------------
// runPlanAction
// ---------------------------------------------------------------------------

/**
 * Implements the `plan` action.
 *
 * @param {object} args - Validated MCP tool args (action, repo_path, mode).
 * @param {object} deps - Injected dependencies.
 * @returns {Promise<object>} Plan envelope or error envelope.
 */
async function runPlanAction(args, deps) {
  // ── Clause d: mode refusal short-circuit ─────────────────────────────────
  if (args.mode === "enqueue" || args.mode === "merge") {
    return {
      ok: false,
      error: "mode_disabled",
      message:
        `${args.mode} mode is reserved; the integration manager only executes prepare mode under the current ADR set`,
      next_action: "file_adr_amendment",
      mode: args.mode,
    };
  }

  const effectiveMode = args.mode ?? DEFAULT_MODE;

  const queueResult = await buildIntegrationQueue(args, deps);
  if (!queueResult.ok) {
    return queueResult;
  }

  const { owner, repo, policy, queue } = queueResult;

  return {
    ok: true,
    action: "plan",
    mode: effectiveMode,
    owner,
    repo,
    policy,
    plan: queue,
  };
}

// ---------------------------------------------------------------------------
// preparePullRequestBranch — per-PR worktree isolation, rebase, gates, push
// ---------------------------------------------------------------------------

/**
 * Per-PR preparation: isolated worktree, fetch, rebase, completion gate,
 * force-with-lease push, CI/Sonar watcher hooks, worktree cleanup.
 *
 * @param {object} pr   - Queue entry ({pr_number, head_ref, head_oid, base_ref}).
 * @param {object} ctx  - Run context ({repoRoot, runId, cfg, owner, repo}).
 * @param {object} deps - Injected dependencies.
 * @returns {Promise<object>} Per-PR outcome record.
 */
async function preparePullRequestBranch(pr, ctx, deps) {
  const { execFile } = deps;
  const { repoRoot, runId, cfg } = ctx;

  const tmpRef = `integ-tmp-${pr.pr_number}`;
  const worktreePath = join(repoRoot, ".gc", "integration-worktrees", runId, String(pr.pr_number));

  // ── Worktree path containment check ──────────────────────────────────────
  // We use realpathSync on repoRoot (already canonical from ensureGitRepo).
  // assertRealpathInRepo walks up existing ancestors and checks containment.
  // For a path that doesn't exist yet (new worktree), we pass the lexical
  // absolute path; the function handles ENOENT by walking up to an ancestor.
  let repoRootReal;
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- repoRoot validated by ensureGitRepo
    repoRootReal = realpathSync(repoRoot);
  } catch {
    repoRootReal = repoRoot; // best-effort fallback
  }

  const containCheck = assertRealpathInRepo(repoRootReal, worktreePath, "integration-worktree path");
  if (!containCheck.ok) {
    return {
      pr_number: pr.pr_number,
      outcome: "blocked",
      failure_class: "worktree_path_invalid",
      summary: safeSummary(`Worktree path rejected: ${containCheck.error}`),
      next_action: "contact_support",
    };
  }

  // ── Fork refusal ──────────────────────────────────────────────────────────
  // The prepare-only lane (GC-O011 first slice) supports same-repo PRs only.
  // Fork PRs are listed in the plan so maintainers can see them, but prepare
  // refuses them immediately without any git or gh side-effects.
  if (pr.head_is_fork) {
    return {
      pr_number: pr.pr_number,
      outcome: "blocked",
      failure_class: "fork_pr_unsupported",
      summary: safeSummary(
        `PR from fork ${pr.head_repo_owner}/${pr.head_repo_name} is not supported in the prepare-only lane (GC-O011 first slice)`,
      ),
      next_action: "merge_manually_or_open_followup",
    };
  }

  let worktreeCreated = false;

  try {
    // ── Step 1: Fetch PR head into a temporary local ref ─────────────────
    // Use git fetch origin pull/<n>/head:<tmp-ref> (not gh pr checkout, which
    // pollutes the current worktree state).
    try {
      await execFile("git", [
        "-C", repoRoot,
        "fetch", "origin",
        `pull/${pr.pr_number}/head:${tmpRef}`,
      ]);
    } catch (e) {
      return {
        pr_number: pr.pr_number,
        outcome: "blocked",
        failure_class: "worktree_create_failed",
        summary: safeSummary(`Failed to fetch PR head: ${e.message ?? String(e)}`),
        next_action: "check_remote_access",
      };
    }

    // ── Step 2: Create isolated worktree ──────────────────────────────────
    try {
      await execFile("git", [
        "-C", repoRoot,
        "worktree", "add",
        worktreePath,
        tmpRef,
      ]);
      worktreeCreated = true;
    } catch (e) {
      return {
        pr_number: pr.pr_number,
        outcome: "blocked",
        failure_class: "worktree_create_failed",
        summary: safeSummary(`git worktree add failed: ${e.message ?? String(e)}`),
        next_action: "check_remote_access",
      };
    }

    // ── Step 3: Fetch base branch in the worktree ─────────────────────────
    try {
      await execFile("git", [
        "-C", worktreePath,
        "fetch", "origin", pr.base_ref,
      ]);
    } catch (e) {
      return {
        pr_number: pr.pr_number,
        outcome: "queue_wide_halt",
        failure_class: "base_fetch_failed",
        summary: safeSummary(`Base branch fetch failed (${pr.base_ref}): ${e.message ?? String(e)}`),
        next_action: "check_base_branch",
      };
    }

    // ── Step 4: Compute merge-base for the rebase ─────────────────────────
    let mergeBase;
    try {
      const mbResult = await execFile("git", [
        "-C", worktreePath,
        "merge-base", tmpRef, `origin/${pr.base_ref}`,
      ]);
      mergeBase = mbResult.stdout.trim();
    } catch (e) {
      // If merge-base fails, fall back to rebasing from the beginning.
      mergeBase = `origin/${pr.base_ref}`;
    }

    // ── Step 5: Rebase PR head onto latest base ───────────────────────────
    try {
      await execFile("git", [
        "-C", worktreePath,
        "rebase", "--onto", `origin/${pr.base_ref}`, mergeBase, tmpRef,
      ]);
    } catch (e) {
      // Abort the rebase to leave the worktree clean.
      try {
        await execFile("git", ["-C", worktreePath, "rebase", "--abort"]);
      } catch {
        // Best-effort abort; ignore secondary failures.
      }

      // Count conflicted files from the error output for the summary.
      const errText = (e.stderr ?? e.stdout ?? e.message ?? "").toString();
      const conflictLines = errText.split("\n").filter((l) => l.startsWith("CONFLICT")).length;
      const count = conflictLines > 0 ? conflictLines : "unknown";

      return {
        pr_number: pr.pr_number,
        outcome: "blocked",
        failure_class: "rebase_conflict",
        summary: safeSummary(`Rebase produced conflicts on ${count} file(s): ${errText.slice(0, 100)}`),
        next_action: "resolve_conflicts",
      };
    }

    // ── Step 6: Run the completion gate ───────────────────────────────────
    const completionCommand = cfg?.workflow?.completion_command;
    if (completionCommand) {
      try {
        // The command string is repo-authored config, not user input.
        // execFile("bash", ["-c", ...]) is the deliberately-excepted form
        // documented in the dispatch spec: argv is exactly [bash, -c, <cmd>].
        await execFile("bash", ["-c", completionCommand], { cwd: worktreePath });
      } catch (e) {
        return {
          pr_number: pr.pr_number,
          outcome: "blocked",
          failure_class: "completion_gate_failed",
          summary: safeSummary(`Completion gate exited non-zero: ${e.message ?? String(e)}`),
          next_action: "fix_completion_gate",
        };
      }
    }

    // ── Step 7: Force-with-lease push ─────────────────────────────────────
    // Push BEFORE running CI/Sonar watchers so the watchers observe the
    // rebased commit, not the pre-rebase head.
    // Lease expectation: the PR head OID at discovery time (pr.head_oid).
    // If the remote head has moved since discovery, the lease mismatch
    // triggers consultation_halt.
    try {
      await execFile("git", [
        "-C", worktreePath,
        "push",
        `--force-with-lease=${pr.head_ref}:${pr.head_oid}`,
        "origin",
        `${tmpRef}:${pr.head_ref}`,
      ]);
    } catch (e) {
      const errText = (e.stderr ?? e.stdout ?? e.message ?? "").toString();
      // Lease mismatch: git outputs "stale info" or "rejected" for lease failures.
      const isLeaseMismatch =
        /stale info|force with lease|rejected.*lease|lease.*rejected|rejected.*\[remote rejected\]/i.test(errText) ||
        (e.message ?? "").includes("lease");

      if (isLeaseMismatch) {
        return {
          pr_number: pr.pr_number,
          outcome: "consultation_halt",
          failure_class: "pr_head_moved",
          halt_reason: "pr_head_moved",
          summary: safeSummary(`PR #${pr.pr_number} head moved since discovery; force-with-lease rejected`),
          candidate_resolutions: [
            "Re-run plan to discover the new head OID",
            "Verify the PR was not force-pushed concurrently",
            "Check for automated commits (e.g., bot activity) on this branch",
          ],
          next_action: "consult_maintainer",
        };
      }

      return {
        pr_number: pr.pr_number,
        outcome: "blocked",
        failure_class: "push_failed",
        summary: safeSummary(`git push --force-with-lease failed: ${errText.slice(0, 150)}`),
        next_action: "check_remote_access",
      };
    }

    // ── Step 8: CI watcher hook ───────────────────────────────────────────
    // Hook contract: (pr, ctx, deps) => Promise<{conclusion, details_url?}>
    // Conclusions: "success"|"skipped" → continue; "failure" → blocked ci_failed;
    // "queued_too_long" → blocked ci_queued_too_long; "timed_out" → blocked ci_timed_out.
    // Watchers run after push so they verify the rebased commit, not stale state.
    const ciResult = await deps.runCiWatcher(pr, ctx, deps);
    if (ciResult.conclusion === "failure") {
      return {
        pr_number: pr.pr_number,
        outcome: "blocked",
        failure_class: "ci_failed",
        summary: safeSummary(`CI check failed${ciResult.details_url ? `: ${ciResult.details_url}` : ""}`),
        next_action: "fix_ci",
      };
    }
    if (ciResult.conclusion === "queued_too_long") {
      return {
        pr_number: pr.pr_number,
        outcome: "blocked",
        failure_class: "ci_queued_too_long",
        summary: safeSummary("CI run has been queued longer than the configured timeout"),
        next_action: "check_ci_queue",
      };
    }
    if (ciResult.conclusion === "timed_out") {
      return {
        pr_number: pr.pr_number,
        outcome: "blocked",
        failure_class: "ci_timed_out",
        summary: safeSummary("CI run did not complete within the configured total timeout"),
        next_action: "check_ci_run",
      };
    }

    // ── Step 9: Sonar watcher hook ────────────────────────────────────────
    // Hook contract: (pr, ctx, deps) => Promise<{conclusion, details_url?}>
    // Conclusions: "success"|"skipped" (no sonar config) → continue;
    // "skipped" (sonar configured) → blocked sonar_skipped_but_configured;
    // "failure" → blocked sonar_gate_red.
    const sonarResult = await deps.runSonarWatcher(pr, ctx, deps);
    const hasSonarConfig = cfg?.sonarcloud != null;
    if (sonarResult.conclusion === "skipped" && hasSonarConfig) {
      return {
        pr_number: pr.pr_number,
        outcome: "blocked",
        failure_class: "sonar_skipped_but_configured",
        summary: safeSummary("Sonar analysis was skipped but sonarcloud is configured in .ground-control.yaml"),
        next_action: "check_sonar_configuration",
      };
    }
    if (sonarResult.conclusion === "failure") {
      return {
        pr_number: pr.pr_number,
        outcome: "blocked",
        failure_class: "sonar_gate_red",
        summary: safeSummary(`Sonar quality gate is red${sonarResult.details_url ? `: ${sonarResult.details_url}` : ""}`),
        next_action: "fix_sonar_issues",
      };
    }

    return {
      pr_number: pr.pr_number,
      outcome: "ready",
      summary: safeSummary(`PR #${pr.pr_number} rebased and pushed successfully`),
    };
  } finally {
    // ── Cleanup: remove worktree and delete tmp ref ───────────────────────
    // Failures here are logged (not surfaced) and do not change the outcome.
    if (worktreeCreated) {
      try {
        await execFile("git", [
          "-C", repoRoot,
          "worktree", "remove", "--force", worktreePath,
        ]);
      } catch {
        // Best-effort cleanup.
      }
    }
    // Delete the temporary local ref regardless of worktree cleanup outcome.
    try {
      await execFile("git", [
        "-C", repoRoot,
        "update-ref", "-d", `refs/heads/${tmpRef}`,
      ]);
    } catch {
      // Best-effort cleanup.
    }
  }
}

// ---------------------------------------------------------------------------
// runPrepareAction
// ---------------------------------------------------------------------------

/**
 * Implements the `prepare` action.
 *
 * @param {object} args - Validated MCP tool args (action, repo_path, mode).
 * @param {object} deps - Injected dependencies.
 * @returns {Promise<object>} Readiness ledger envelope or error envelope.
 */
async function runPrepareAction(args, deps) {
  // ── Clause a: mode refusal short-circuit ─────────────────────────────────
  // enqueue remains reserved (no ADR carve-out).
  // merge is permitted via the ADR-029 amendment (2026-05-26, issue #989).
  if (args.mode === "enqueue") {
    return {
      ok: false,
      error: "mode_disabled",
      message:
        "enqueue mode is reserved; the integration manager only executes prepare or merge mode under the current ADR set",
      next_action: "file_adr_amendment",
      mode: args.mode,
    };
  }

  // ── Clause b: resolve config + owner/repo via buildIntegrationQueue ───────
  const queueResult = await buildIntegrationQueue(args, deps);
  if (!queueResult.ok) {
    return queueResult;
  }

  const { repoRoot, owner, repo, cfg, policy, queue } = queueResult;

  // ── Resolve merge strategy (only relevant when mode === "merge") ──────────
  const rawIM = cfg?.workflow?.integration_manager;
  const mergeStrategy = rawIM?.merge_strategy ?? "merge";

  // ── Generate run ID ───────────────────────────────────────────────────────
  const runId = makeRunId(deps);

  // ── Clause d: acquire the integration lock ────────────────────────────────
  const acquireLock = deps.acquireIntegrationLock ?? defaultAcquireIntegrationLock;
  let releaseLock;
  try {
    releaseLock = await acquireLock(repoRoot);
  } catch (e) {
    if (e.code === "ELOCKED") {
      return errorEnvelope(
        "lock_contended",
        `another integration run is in progress at ${repoRoot}`,
        "wait_or_release",
      );
    }
    return errorEnvelope(
      "lock_failed",
      `Failed to acquire integration lock: ${e.message}`,
      "contact_support",
    );
  }

  // Run context passed to per-PR preparation.
  const ctx = { repoRoot, runId, cfg, owner, repo };

  const results = [];
  let haltReason = null;
  let haltPrNumber = null;

  const writeHaltLedger = deps.writeHaltLedger ?? defaultWriteHaltLedger;

  try {
    // ── Clause e: per-PR preparation loop ────────────────────────────────
    for (const pr of queue) {
      let prOutcome;
      try {
        prOutcome = await preparePullRequestBranch(pr, ctx, deps);
      } catch (e) {
        // Unexpected throw inside per-PR preparation: treat as blocked.
        prOutcome = {
          pr_number: pr.pr_number,
          outcome: "blocked",
          failure_class: "unexpected_error",
          summary: safeSummary(`Unexpected error during PR preparation: ${e.message ?? String(e)}`),
          next_action: "contact_support",
        };
      }

      if (prOutcome.outcome === "queue_wide_halt") {
        // Record this entry and stop; subsequent PRs NOT added.
        results.push({
          pr_number: prOutcome.pr_number,
          outcome: prOutcome.outcome,
          summary: prOutcome.summary,
          failure_class: prOutcome.failure_class,
          next_action: prOutcome.next_action,
        });
        return {
          ok: false,
          error: "queue_wide_halt",
          message: scrub(prOutcome.summary),
          next_action: prOutcome.next_action ?? "check_base_branch",
          run_id: runId,
          owner,
          repo,
          policy,
          results,
        };
      }

      if (prOutcome.outcome === "consultation_halt") {
        // Record this entry, write the halt ledger, and stop.
        const haltRecord = {
          pr_number: prOutcome.pr_number,
          outcome: prOutcome.outcome,
          summary: prOutcome.summary,
          failure_class: prOutcome.failure_class,
          halt_reason: prOutcome.halt_reason,
          candidate_resolutions: prOutcome.candidate_resolutions ?? [],
          next_action: prOutcome.next_action,
        };
        results.push(haltRecord);

        haltReason = prOutcome.halt_reason;
        haltPrNumber = prOutcome.pr_number;

        const runDir = join(repoRoot, ".gc", "integration-runs", runId);
        const ledger = {
          run_id: runId,
          halt_reason: scrub(haltReason),
          pr_number_at_halt: haltPrNumber,
          queue_state: results.map((r) => ({ pr_number: r.pr_number, outcome: r.outcome })),
          timestamp: new Date().toISOString(),
        };
        try {
          writeHaltLedger(runDir, ledger);
        } catch {
          // Best-effort ledger write; don't fail the action if fs is unavailable.
        }

        return {
          ok: false,
          error: "consultation_halt",
          run_id: runId,
          halt_reason: scrub(haltReason),
          candidate_resolutions: prOutcome.candidate_resolutions ?? [],
          next_action: "consult_maintainer",
          owner,
          repo,
          policy,
          results,
        };
      }

      // outcome === "ready" or "blocked" — record first, then optionally merge.
      const prRecord = {
        pr_number: prOutcome.pr_number,
        outcome: prOutcome.outcome,
        summary: prOutcome.summary,
        ...(prOutcome.failure_class ? { failure_class: prOutcome.failure_class } : {}),
        ...(prOutcome.next_action ? { next_action: prOutcome.next_action } : {}),
      };

      // ── Merge step (mode=merge + outcome=ready only) ──────────────────────
      // Runs per-PR, in queue order. A single merge failure does NOT halt the
      // queue; the PR is marked blocked:merge_failed and the loop continues.
      // The merge step never runs when: outcome is not "ready", a halt has
      // already fired, or the lock was lost (those paths returned early above).
      if (args.mode === "merge" && prOutcome.outcome === "ready") {
        const { execFile } = deps;
        const strategyFlag = `--${mergeStrategy}`;
        try {
          await execFile("gh", [
            "pr", "merge",
            String(prOutcome.pr_number),
            strategyFlag,
            "--delete-branch",
            "--repo", `${owner}/${repo}`,
          ]);
          prRecord.outcome = "merged";
          prRecord.merged_at = new Date().toISOString();
          prRecord.summary = safeSummary(`PR #${prOutcome.pr_number} merged (${mergeStrategy})`);
          delete prRecord.failure_class;
          delete prRecord.next_action;
        } catch (e) {
          const errText = safeSummary((e.stderr ?? e.stdout ?? e.message ?? "").toString());
          prRecord.outcome = "blocked";
          prRecord.failure_class = "merge_failed";
          prRecord.summary = safeSummary(`gh pr merge failed: ${errText}`);
          prRecord.next_action = "check_merge_permissions";
        }
      }

      results.push(prRecord);
    }

    // ── Clause f: return readiness ledger ────────────────────────────────
    return {
      ok: true,
      action: "prepare",
      mode: args.mode,
      run_id: runId,
      owner,
      repo,
      policy,
      results,
    };
  } finally {
    // Lock MUST be released on every exit path.
    if (releaseLock) {
      try {
        await releaseLock();
      } catch {
        // Best-effort release.
      }
    }
  }
}

// ---------------------------------------------------------------------------
// runStatusAction
// ---------------------------------------------------------------------------

/**
 * Implements the `status` action: read-only diagnostic.
 *
 * Returns the lock state and the most-recent run's halt.json (if any).
 * Never makes gh calls or git calls — purely local filesystem reads.
 *
 * Injectable deps:
 *   deps.statFile(path)           → {ok, mtimeMs} or {ok:false}
 *   deps.readdir(path)            → string[] of entry names (throws on ENOENT)
 *   deps.readFile(path)           → string (throws on ENOENT/etc.)
 *
 * @param {object} args - Validated MCP tool args (action, repo_path).
 * @param {object} deps - Injected dependencies.
 * @returns {Promise<object>} Status envelope.
 */
async function runStatusAction(args, deps) {
  // Resolve the repo path.  For status we do a best-effort realpath without
  // calling ensureGitRepo (which shells out to git).  If the path is not
  // absolute we fall through to the raw arg.
  const rawPath = (args.repo_path ?? "").trim();
  let repoRoot = rawPath;
  try {
    if (isAbsolute(rawPath)) {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- rawPath is user-supplied but we only read metadata
      repoRoot = realpathSync(rawPath);
    }
  } catch {
    // best-effort; proceed with rawPath
  }

  const lockPath = join(repoRoot, ".gc-integration-lock");

  // Determine lock state.
  const statFile = deps.statFile ?? defaultStatFile;
  const lockStat = statFile(lockPath);
  const lockHeld = lockStat.ok;

  // Find the most-recent run in .gc/integration-runs/.
  const runsDir = join(repoRoot, ".gc", "integration-runs");
  let lastRun = null;
  try {
    const readdir = deps.readdir ?? defaultReaddir;
    const readFile = deps.readFile ?? defaultReadFile;

    const entries = readdir(runsDir);
    if (entries.length > 0) {
      // Find the entry with the most-recent mtime.
      let newestEntry = null;
      let newestMtime = -Infinity;
      for (const entry of entries) {
        const entryPath = join(runsDir, entry);
        const s = statFile(entryPath);
        if (s.ok && s.mtimeMs > newestMtime) {
          newestMtime = s.mtimeMs;
          newestEntry = entry;
        }
      }

      if (newestEntry !== null) {
        const haltJsonPath = join(runsDir, newestEntry, "halt.json");
        let haltData = null;
        try {
          const raw = readFile(haltJsonPath);
          const parsed = JSON.parse(raw);
          haltData = {
            run_id: scrub(parsed.run_id ?? newestEntry),
            started_at: scrub(parsed.timestamp ?? null),
            halt_reason: parsed.halt_reason != null ? scrub(parsed.halt_reason) : undefined,
          };
          // Remove undefined keys.
          if (haltData.halt_reason === undefined) {
            delete haltData.halt_reason;
          }
        } catch {
          // halt.json absent or unparseable — return minimal run info.
          haltData = { run_id: scrub(newestEntry), started_at: null };
        }
        lastRun = haltData;
      }
    }
  } catch {
    // runsDir absent or unreadable — lastRun stays null.
  }

  return {
    ok: true,
    action: "status",
    repo_path: scrub(repoRoot),
    lock_held: lockHeld,
    lock_path: scrub(lockPath),
    last_run: lastRun,
  };
}

// ---------------------------------------------------------------------------
// runReleaseAction
// ---------------------------------------------------------------------------

/**
 * Implements the `release` action: idempotent integration lock release.
 *
 * The integration lock is a directory at <repoRoot>/.gc-integration-lock
 * created by proper-lockfile via mkdir.  Releasing it externally means
 * removing that directory with fs.rm.
 *
 * Injectable deps:
 *   deps.statFile(path)  → {ok, mtimeMs} or {ok:false}
 *   deps.rmFile(path)    → void (throws on error)
 *
 * @param {object} args - Validated MCP tool args (action, repo_path).
 * @param {object} deps - Injected dependencies.
 * @returns {Promise<object>} Release envelope.
 */
async function runReleaseAction(args, deps) {
  const rawPath = (args.repo_path ?? "").trim();
  let repoRoot = rawPath;
  try {
    if (isAbsolute(rawPath)) {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- validated absolute path
      repoRoot = realpathSync(rawPath);
    }
  } catch {
    // best-effort; proceed with rawPath
  }

  const lockPath = join(repoRoot, ".gc-integration-lock");
  const statFile = deps.statFile ?? defaultStatFile;
  const rmFile = deps.rmFile ?? defaultRmFile;

  // Check whether the lock exists.
  const lockStat = statFile(lockPath);
  if (!lockStat.ok) {
    return {
      ok: true,
      action: "release",
      released: false,
      reason: "no_lock_held",
    };
  }

  // Attempt removal.
  try {
    rmFile(lockPath);
  } catch (e) {
    const raw = e?.message ?? String(e);
    const msg = detectSensitiveBodyContent(raw) ? "<redacted>" : raw;
    return {
      ok: false,
      error: "release_failed",
      message: msg,
      next_action: "manual_remove_lockfile",
    };
  }

  return {
    ok: true,
    action: "release",
    released: true,
    lock_path: scrub(lockPath),
  };
}

// ---------------------------------------------------------------------------
// Production-default implementations for status/release injectable deps.
// ---------------------------------------------------------------------------

function defaultStatFile(filePath) {
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- caller-validated path
    const s = statSync(filePath);
    return { ok: true, mtimeMs: s.mtimeMs };
  } catch {
    return { ok: false };
  }
}

function defaultReaddir(dirPath) {
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- caller-validated path
  return readdirSync(dirPath);
}

function defaultReadFile(filePath) {
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- caller-validated path
  return readFileSync(filePath, "utf-8");
}

function defaultRmFile(filePath) {
  // The lock is a directory created by proper-lockfile's mkdir strategy.
  rmSync(filePath, { recursive: true, force: true });
}

// ---------------------------------------------------------------------------
// runIntegrationManager — top-level dispatcher
// ---------------------------------------------------------------------------

/**
 * Top-level dispatcher for gc_integration_manager.
 *
 * @param {object} args - MCP tool input (action, repo_path, mode?).
 * @param {object} [deps] - Injectable dependencies for testing.
 * @returns {Promise<object>} Result envelope.
 */
export async function runIntegrationManager(args = {}, deps = {}) {
  // Fill in production defaults for any uninjected deps.
  const resolvedDeps = {
    execFile: deps.execFile ?? defaultExecFile,
    ensureGitRepo: deps.ensureGitRepo ?? ensureGitRepo,
    getOwnerRepo: deps.getOwnerRepo ?? getOwnerRepo,
    readYaml: deps.readYaml ?? defaultReadYaml,
    acquireIntegrationLock: deps.acquireIntegrationLock ?? defaultAcquireIntegrationLock,
    writeHaltLedger: deps.writeHaltLedger ?? defaultWriteHaltLedger,
    // CI/Sonar watcher hooks — real adapters wired to lib.js watchers.
    runCiWatcher: deps.runCiWatcher ?? defaultRunCiWatcher,
    runSonarWatcher: deps.runSonarWatcher ?? defaultRunSonarWatcher,
    // Status/release injectable deps.
    statFile: deps.statFile ?? defaultStatFile,
    readdir: deps.readdir ?? defaultReaddir,
    readFile: deps.readFile ?? defaultReadFile,
    rmFile: deps.rmFile ?? defaultRmFile,
    // Run-ID generation — injectable for deterministic tests.
    now: deps.now,
    randomId: deps.randomId,
  };

  try {
    // ── Validate action ──────────────────────────────────────────────────────
    const { action } = args;
    if (!action || !VALID_ACTIONS.includes(action)) {
      return errorEnvelope(
        "unknown_action",
        `action must be one of: ${VALID_ACTIONS.join(", ")}; got ${JSON.stringify(action)}`,
        "use_supported_action",
      );
    }

    // ── Validate repo_path ───────────────────────────────────────────────────
    if (!args.repo_path || typeof args.repo_path !== "string" || args.repo_path.trim() === "") {
      return errorEnvelope(
        "invalid_repo_path",
        "repo_path is required and must be a non-empty string",
        "verify_repo_path",
      );
    }

    // ── Validate mode ────────────────────────────────────────────────────────
    if (args.mode !== undefined && !VALID_MODES.includes(args.mode)) {
      return errorEnvelope(
        "unknown_mode",
        `mode must be one of: ${VALID_MODES.join(", ")}; got ${JSON.stringify(args.mode)}`,
        "use_supported_mode",
      );
    }

    // Normalise args: apply default mode.
    const normalizedArgs = {
      ...args,
      mode: args.mode ?? DEFAULT_MODE,
    };

    // ── Dispatch on action ───────────────────────────────────────────────────
    switch (action) {
      case "plan":
        return await runPlanAction(normalizedArgs, resolvedDeps);

      case "prepare":
        return await runPrepareAction(normalizedArgs, resolvedDeps);

      case "status":
        return await runStatusAction(normalizedArgs, resolvedDeps);

      case "release":
        return await runReleaseAction(normalizedArgs, resolvedDeps);

      default:
        // Unreachable due to VALID_ACTIONS guard above, but kept for safety.
        return errorEnvelope(
          "unknown_action",
          `Unknown action: ${action}`,
          "use_supported_action",
        );
    }
  } catch (e) {
    // Never throw across the MCP boundary.
    const raw = e?.message ?? String(e);
    const sensitive = detectSensitiveBodyContent(raw);
    return errorEnvelope(
      "unexpected_error",
      sensitive ? "<redacted>" : `Unexpected error: ${raw}`,
      "contact_support",
    );
  }
}

// Split from gc-integrate.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Declaration bodies are unchanged.

import { isAbsolute, join, resolve } from "node:path";
import { readFileSync, readdirSync, realpathSync, rmSync, statSync } from "node:fs";
import { detectSensitiveBodyContent, resolveMcpLaunchWorkspaceAuthorization } from "../lib.js";
import { buildIntegrationQueue, defaultAcquireIntegrationLock, defaultWriteHaltLedger, errorEnvelope, makeRunId, safeSummary, scrub } from "./exec-file-async.js";
import { preparePullRequestBranch } from "./run-plan-action.js";

// One PR's preparation, with an unexpected throw normalized into a blocked
// record so a single bad PR cannot abort the whole queue.
async function prepareOnePullRequest(pr, ctx, deps) {
  try {
    return await preparePullRequestBranch(pr, ctx, deps);
  } catch (e) {
    return {
      pr_number: pr.pr_number,
      outcome: "blocked",
      failure_class: "unexpected_error",
      summary: safeSummary(`Unexpected error during PR preparation: ${e.message ?? String(e)}`),
      next_action: "contact_support",
    };
  }
}

// A queue-wide halt records the offending PR and stops; later PRs are not added.
function queueWideHaltEnvelope(prOutcome, results, { runId, owner, repo, policy }) {
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

// A consultation halt records the PR, writes the halt ledger best-effort, and
// stops the run for a maintainer decision.
function consultationHaltEnvelope(prOutcome, results, ctx, writeHaltLedger) {
  const { repoRoot, runId, owner, repo, policy } = ctx;
  const haltReason = prOutcome.halt_reason;
  results.push({
    pr_number: prOutcome.pr_number,
    outcome: prOutcome.outcome,
    summary: prOutcome.summary,
    failure_class: prOutcome.failure_class,
    halt_reason: haltReason,
    candidate_resolutions: prOutcome.candidate_resolutions ?? [],
    next_action: prOutcome.next_action,
  });

  try {
    writeHaltLedger(join(repoRoot, ".gc", "integration-runs", runId), {
      run_id: runId,
      halt_reason: scrub(haltReason),
      pr_number_at_halt: prOutcome.pr_number,
      queue_state: results.map((r) => ({ pr_number: r.pr_number, outcome: r.outcome })),
      timestamp: new Date().toISOString(),
    });
  } catch {
    // Best-effort ledger write; fs being unavailable does not fail the action.
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

// Merge one prepared PR, mutating its record in place. A failure marks the PR
// blocked rather than throwing, so the queue continues.
async function mergePreparedPullRequest(prRecord, { mergeStrategy, owner, repo }, deps) {
  const { execFile } = deps;
  try {
    await execFile("gh", [
      "pr", "merge",
      String(prRecord.pr_number),
      `--${mergeStrategy}`,
      "--delete-branch",
      "--repo", `${owner}/${repo}`,
    ]);
    prRecord.outcome = "merged";
    prRecord.merged_at = new Date().toISOString();
    prRecord.summary = safeSummary(`PR #${prRecord.pr_number} merged (${mergeStrategy})`);
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

// enqueue stays reserved (no ADR carve-out); merge is permitted by the ADR-029
// amendment (2026-05-26, issue #989). Returns a refusal envelope, or null.
function refuseReservedMode(mode) {
  if (mode !== "enqueue") return null;
  return {
    ok: false,
    error: "mode_disabled",
    message:
      "enqueue mode is reserved; the integration manager only executes prepare or merge mode under the current ADR set",
    next_action: "file_adr_amendment",
    mode,
  };
}

// The repo-level integration lock, so two runs cannot race on one repository.
// Returns {ok:true, releaseLock} or an error envelope.
async function acquireRunLock(repoRoot, deps) {
  const acquireLock = deps.acquireIntegrationLock ?? defaultAcquireIntegrationLock;
  try {
    return { ok: true, releaseLock: await acquireLock(repoRoot) };
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
}

export // ---------------------------------------------------------------------------
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
  const modeRefusal = refuseReservedMode(args.mode);
  if (modeRefusal) return modeRefusal;

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

  const lock = await acquireRunLock(repoRoot, deps);
  if (!lock.ok) return lock;
  const releaseLock = lock.releaseLock;

  // Run context passed to per-PR preparation.
  const ctx = { repoRoot, runId, cfg, owner, repo };

  const results = [];

  const writeHaltLedger = deps.writeHaltLedger ?? defaultWriteHaltLedger;

  try {
    // ── Clause e: per-PR preparation loop ────────────────────────────────
    for (const pr of queue) {
      const prOutcome = await prepareOnePullRequest(pr, ctx, deps);

      if (prOutcome.outcome === "queue_wide_halt") {
        return queueWideHaltEnvelope(prOutcome, results, { runId, owner, repo, policy });
      }

      if (prOutcome.outcome === "consultation_halt") {
        return consultationHaltEnvelope(
          prOutcome, results, { repoRoot, runId, owner, repo, policy }, writeHaltLedger,
        );
      }

      // outcome === "ready" or "blocked" — record first, then optionally merge.
      const prRecord = {
        pr_number: prOutcome.pr_number,
        outcome: prOutcome.outcome,
        summary: prOutcome.summary,
        ...(prOutcome.failure_class ? { failure_class: prOutcome.failure_class } : {}),
        ...(prOutcome.next_action ? { next_action: prOutcome.next_action } : {}),
      };

      // Runs per-PR, in queue order. A single merge failure does NOT halt the
      // queue; the PR is marked blocked:merge_failed and the loop continues.
      // Never reached unless the outcome is "ready" and no halt fired above.
      if (args.mode === "merge" && prOutcome.outcome === "ready") {
        await mergePreparedPullRequest(prRecord, { mergeStrategy, owner, repo }, deps);
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
}// The status and release actions read and remove files under the repository
// root. That root is NOT taken from `repo_path`: a caller-supplied root is
// itself attacker-controlled, so validating a path against it constrains
// nothing. The root comes from the MCP launch workspace instead, and
// `repo_path` is checked against it and then discarded — the same binding
// #1535 put on the review lane, where an unbound `repo_path` would let a caller
// point the server at any other checkout it can reach and act on that
// repository with the server's credentials. Here the lane also pushes and can
// merge, so the binding matters more, not less.
//
// Every path below is therefore built from the launch workspace plus constant
// segments and directory-entry names, and each entry name is required to be a
// single plain path segment before it is joined.

// The MCP launch workspace root. Injectable so tests can bind a fixture root.
async function defaultResolveWorkspaceRoot() {
  const authorization = await resolveMcpLaunchWorkspaceAuthorization();
  return realpathSync(authorization.workspaceRoot);
}

// Resolve the workspace this run may touch, refusing a `repo_path` that names
// anything else. Returns {ok:true, workspaceRoot} or an error envelope.
async function authorizedWorkspaceRoot(args, deps) {
  const resolveRoot = deps.resolveWorkspaceRoot ?? defaultResolveWorkspaceRoot;
  let workspaceRoot;
  try {
    workspaceRoot = await resolveRoot();
  } catch (e) {
    return errorEnvelope(
      "workspace_unavailable",
      `The MCP launch workspace could not be resolved: ${e.message ?? String(e)}`,
      "restart_mcp_server",
    );
  }

  const requested = (args.repo_path ?? "").trim();
  let requestedReal = requested;
  if (isAbsolute(requested)) {
    try {
      requestedReal = realpathSync(requested);
    } catch {
      requestedReal = resolve(requested);
    }
  }
  if (requestedReal !== workspaceRoot) {
    return errorEnvelope(
      "repo_not_authorized",
      "repo_path is outside the MCP launch workspace authorized for this run",
      "run_against_the_launch_workspace",
    );
  }
  return { ok: true, workspaceRoot };
}

// A readdir entry must be one plain segment. Rejecting separators and traversal
// keeps a crafted directory name from steering the join below.
function isPlainSegment(entry) {
  return typeof entry === "string"
    && entry !== ""
    && entry !== "."
    && entry !== ".."
    && !entry.includes("/")
    && !entry.includes("\\")
    && !entry.includes("\0");
}

export function defaultReadFile(filePath) {
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- built from the launch workspace root, never from caller input
  return readFileSync(filePath, "utf-8");
}export function defaultReaddir(dirPath) {
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- built from the launch workspace root, never from caller input
  return readdirSync(dirPath);
}export // ---------------------------------------------------------------------------
// Production-default implementations for status/release injectable deps.
// ---------------------------------------------------------------------------

function defaultStatFile(filePath) {
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- built from the launch workspace root, never from caller input
    const s = statSync(filePath);
    return { ok: true, mtimeMs: s.mtimeMs };
  } catch {
    return { ok: false };
  }
}

// Newest run directory by mtime, or null when the runs directory is empty.
function newestRunEntry(entries, runsDir, statFile) {
  let newestEntry = null;
  let newestMtime = -Infinity;
  for (const entry of entries) {
    if (!isPlainSegment(entry)) continue;
    const s = statFile(join(runsDir, entry));
    if (s.ok && s.mtimeMs > newestMtime) {
      newestMtime = s.mtimeMs;
      newestEntry = entry;
    }
  }
  return newestEntry;
}

// A run's halt.json reduced to the scrubbed fields the status envelope reports.
// An absent or unparseable ledger degrades to minimal run info rather than
// failing the read-only status action.
function haltRecordFor(runsDir, entry, readFile) {
  try {
    const parsed = JSON.parse(readFile(join(runsDir, entry, "halt.json")));
    const record = {
      run_id: scrub(parsed.run_id ?? entry),
      started_at: scrub(parsed.timestamp ?? null),
    };
    if (parsed.halt_reason != null) record.halt_reason = scrub(parsed.halt_reason);
    return record;
  } catch {
    return { run_id: scrub(entry), started_at: null };
  }
}

// Most-recent run under <repoRoot>/.gc/integration-runs, or null when the
// directory is absent, unreadable, or empty.
function readLastRunRecord(repoRoot, statFile, deps) {
  const runsDir = join(repoRoot, ".gc", "integration-runs");
  try {
    const readdir = deps.readdir ?? defaultReaddir;
    const readFile = deps.readFile ?? defaultReadFile;
    const entries = readdir(runsDir);
    if (entries.length === 0) return null;
    const newestEntry = newestRunEntry(entries, runsDir, statFile);
    return newestEntry === null ? null : haltRecordFor(runsDir, newestEntry, readFile);
  } catch {
    return null;
  }
}

export // ---------------------------------------------------------------------------
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
  const authorized = await authorizedWorkspaceRoot(args, deps);
  if (!authorized.ok) return authorized;
  const repoRoot = authorized.workspaceRoot;

  const lockPath = join(repoRoot, ".gc-integration-lock");

  // Determine lock state.
  const statFile = deps.statFile ?? defaultStatFile;
  const lockHeld = statFile(lockPath).ok;

  const lastRun = readLastRunRecord(repoRoot, statFile, deps);

  return {
    ok: true,
    action: "status",
    repo_path: scrub(repoRoot),
    lock_held: lockHeld,
    lock_path: scrub(lockPath),
    last_run: lastRun,
  };
}export function defaultRmFile(filePath) {
  // The lock is a directory created by proper-lockfile's mkdir strategy.
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- built from the launch workspace root, never from caller input
  rmSync(filePath, { recursive: true, force: true });
}export // ---------------------------------------------------------------------------
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
  const authorized = await authorizedWorkspaceRoot(args, deps);
  if (!authorized.ok) return authorized;
  const repoRoot = authorized.workspaceRoot;

  const lockPath = join(repoRoot, ".gc-integration-lock");
  const statFile = deps.statFile ?? defaultStatFile;
  const rmFile = deps.rmFile ?? defaultRmFile;

  // Check whether the lock exists.
  const lockStat = statFile(lockPath);
  if (lockStat.ok === false) {
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

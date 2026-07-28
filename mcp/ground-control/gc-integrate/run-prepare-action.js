// Split from gc-integrate.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Declaration bodies are unchanged.

import { isAbsolute, join } from "node:path";
import { readFileSync, readdirSync, realpathSync, rmSync, statSync } from "node:fs";
import { detectSensitiveBodyContent, ensureGitRepo } from "../lib.js";
import { buildIntegrationQueue, defaultAcquireIntegrationLock, defaultWriteHaltLedger, errorEnvelope, makeRunId, safeSummary, scrub } from "./exec-file-async.js";
import { preparePullRequestBranch } from "./run-plan-action.js";

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
}export function defaultReadFile(filePath) {
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- caller-validated path
  return readFileSync(filePath, "utf-8");
}export function defaultReaddir(dirPath) {
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- caller-validated path
  return readdirSync(dirPath);
}export // ---------------------------------------------------------------------------
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
}export // ---------------------------------------------------------------------------
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
}export function defaultRmFile(filePath) {
  // The lock is a directory created by proper-lockfile's mkdir strategy.
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

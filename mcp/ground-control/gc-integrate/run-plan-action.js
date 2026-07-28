// Split from gc-integrate.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Declaration bodies are unchanged.

import { join } from "node:path";
import { realpathSync } from "node:fs";
import { assertRealpathInRepo, ensureGitRepo } from "../lib.js";
import { DEFAULT_MODE, buildIntegrationQueue, safeSummary } from "./exec-file-async.js";

export // ---------------------------------------------------------------------------
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
}export // ---------------------------------------------------------------------------
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

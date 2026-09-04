// Extracted from gc-implement-mechanical.js (issue #1355).
//
// The module had reached 1,231 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md). gc-implement-mechanical.js remains the tool entry point.

import { failure, requireField } from "./gate-helpers.js";
import { mapCompletion } from "./publish.js";

export async function runReadiness(args, deps) {
  const action = "readiness";
  for (const field of ["prNumber", "completion"]) {
    const invalid = requireField(args, field, action);
    if (invalid) return invalid;
  }
  const result = await deps.assertCompletion(mapCompletion(args, "pre_merge"));
  if (!result.ok) {
    return failure(
      action,
      result.error,
      result.message,
      result.next_action ?? "repair_readiness_evidence_and_retry",
      { completion: result },
    );
  }
  return {
    ok: true,
    action,
    phase: "ready_for_review",
    readiness_report: result.readiness_report,
    next_action: "wait_for_user_to_merge_the_pr",
  };
}
export async function runFinalize(args, deps) {
  const action = "finalize";
  for (const field of ["prNumber", "completion"]) {
    const invalid = requireField(args, field, action);
    if (invalid) return invalid;
  }
  const completion = await deps.assertCompletion(mapCompletion(args, "post_merge"));
  if (!completion.ok) {
    return failure(
      action,
      completion.error,
      completion.message,
      completion.next_action ?? "repair_post_merge_evidence_and_retry",
      { completion },
    );
  }
  const close = await deps.closeIssue({
    repoPath: args.repoPath,
    issueNumber: args.issueNumber,
    prNumber: args.prNumber,
    // In the finalize flow the post-merge assertion already posted the validated
    // final-report marker (bound to this PR), so the close marker gate passes. A
    // genuine bypass is authorized only by a trusted issue-thread override comment,
    // never a caller field (issue #1541 security review).
  });
  if (!close.ok) {
    return failure(
      action,
      close.error,
      close.message,
      close.next_action ?? "repair_issue_close_and_retry",
      { completion, close },
    );
  }
  return {
    ok: true,
    action,
    phase: "closed",
    completion,
    close,
    next_action: "workflow_complete",
  };
}
export const STATION_BY_ACTION = Object.freeze({
  bootstrap: "issue_branch_resolution",
  verify: "completion_gate",
  publish: "git_publish",
});
export const MARKER_BY_ACTION = Object.freeze({
  readiness: "ready_for_review",
  finalize: "post_merge",
});
export const GATE_VERDICT_ERRORS = Object.freeze({
  bootstrap: new Set(["implement_mechanical_branch_prepare_failed"]),
  verify: new Set([
    "implement_mechanical_completion_gate_failed",
    "implement_mechanical_policy_gate_failed",
    "implement_mechanical_gate_tree_changed",
  ]),
  publish: new Set([
    "implement_mechanical_precommit_failed",
    "implement_mechanical_sensitive_path_present",
    "implement_mechanical_commit_message_invalid",
    "implement_mechanical_branch_mismatch",
  ]),
});
export function classifyStationResult(action, envelope) {
  if (envelope?.ok) return "pass";
  const verdictErrors = GATE_VERDICT_ERRORS[action];
  if (verdictErrors?.has(envelope?.error)) return "fail";
  // A quality-gate refusal is the gate rendering a verdict, even though it arrives as its own
  // structured error rather than a command exit code.
  if (action === "verify" && envelope?.quality && envelope.quality.ok === false) return "fail";
  return "not_evaluable";
}
export const INERT_EMITTER = Object.freeze({
  openRun: async () => null,
  ensureRun: async () => null,
  markState: async () => null,
  closeRun: async () => null,
  recordRequirementUids: async () => null,
  recordStationAttempt: async () => null,
  markerTransition: async () => null,
  station: async (_phase, fn) => fn(),
});
export function guardEmitter(emitter) {
  const swallow = (name) => async (...callArgs) => {
    try {
      return await emitter[name](...callArgs);
    } catch {
      return null;
    }
  };
  return {
    openRun: swallow("openRun"),
    ensureRun: swallow("ensureRun"),
    markState: swallow("markState"),
    closeRun: swallow("closeRun"),
    recordRequirementUids: swallow("recordRequirementUids"),
    recordStationAttempt: swallow("recordStationAttempt"),
    markerTransition: swallow("markerTransition"),
    async station(phase, fn) {
      let ran = false;
      let phaseResult;
      let phaseError;
      const tracked = async () => {
        ran = true;
        try {
          phaseResult = await fn();
          return phaseResult;
        } catch (error) {
          phaseError = error;
          throw error;
        }
      };
      try {
        return await emitter.station(phase, tracked);
      } catch (error) {
        if (phaseError) throw phaseError;
        return ran ? phaseResult : fn();
      }
    },
  };
}
export async function resolveRunBranch(args, deps) {
  if (args.branchName) return args.branchName;
  try {
    const { stdout } = await deps.runGit(args.repoPath, ["branch", "--show-current"], deps.execFile);
    const branch = stdout.trim();
    return branch === "" ? null : branch;
  } catch {
    return null;
  }
}
export async function resolveEmitter(args, deps) {
  try {
    const context = await deps.getContext(args.repoPath);
    if (context?.status !== "ok" || !context.project) return INERT_EMITTER;
    const branch = await resolveRunBranch(args, deps);
    if (!branch) return INERT_EMITTER;
    return deps.createLifecycle({
      project: context.project,
      repo: context.github_repo,
      issueNumber: args.issueNumber,
      branch,
      workflowType: "IMPLEMENT",
      runtimeDriver: args.driver,
      requirementUids: args.requirements?.map((item) => item.uid),
      // Known from the monitor boundary onward; absent on the earlier actions, which leave the
      // field alone rather than clearing it.
      prNumber: args.prNumber,
    });
  } catch {
    return INERT_EMITTER;
  }
}
export async function applyRunStateTransition(action, result, emitter) {
  if (action === "readiness" && result.ok) {
    await emitter.markState("READY_FOR_REVIEW");
    return;
  }
  if (action !== "finalize") return;
  if (result.ok) {
    await emitter.closeRun({ finalState: "MERGED", outcome: "MERGED" });
    return;
  }
  if (result.close?.error === "close_pr_not_merged" && result.close.pr_state === "CLOSED") {
    await emitter.closeRun({ finalState: "CLOSED", outcome: "CLOSED_WITHOUT_MERGE" });
  }
}

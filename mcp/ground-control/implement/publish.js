// Extracted from gc-implement-mechanical.js (issue #1355).
//
// The module had reached 1,231 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md). gc-implement-mechanical.js remains the tool entry point.

import { ciGateFindings } from "../gate-finding-adapters.js";
import { commandFailure, failure, requireField } from "./gate-helpers.js";
import { isSensitivePublishPath, readPublishPaths, validateCommitMessage } from "./verify.js";

export async function runPublish(args, deps) {
  const action = "publish";
  const invalidBranch = requireField(args, "branchName", action);
  if (invalidBranch) return invalidBranch;
  const authorization = await deps.authorizeRepo(args.repoPath);
  if (!authorization.ok) {
    return failure(
      action,
      authorization.error,
      authorization.message,
      "repair_authorized_checkout_and_retry",
    );
  }
  const repoRoot = authorization.repoRoot;
  // The pre-publish hook command is repository configuration, so a broken
  // .ground-control.yaml must refuse here rather than silently fall back to
  // the default boundary command.
  const context = await deps.getContext(args.repoPath);
  if (context?.status !== "ok") {
    return failure(
      action,
      "implement_mechanical_context_invalid",
      context?.errors?.join("; ") ?? "Ground Control repository context is unavailable",
      "repair_ground_control_context_and_retry",
    );
  }
  const authorized = await deps.authorizeRequirementUid({
    repoPath: args.repoPath,
    issueNumber: args.issueNumber,
    requestedRequirementUid: args.requestedRequirementUid,
  });
  if (!authorized.ok) {
    return failure(action, authorized.error, authorized.message, authorized.next_action);
  }
  const { stdout: activeBranch } = await deps.runGit(
    repoRoot,
    ["branch", "--show-current"],
    deps.execFile,
  );
  if (activeBranch.trim() !== args.branchName) {
    return failure(
      action,
      "implement_mechanical_branch_mismatch",
      `Active branch is '${activeBranch.trim()}', expected '${args.branchName}'`,
      "return_to_the_issue_branch_and_retry",
    );
  }
  const publishPaths = await readPublishPaths(
    repoRoot,
    deps.runGit,
    deps.execFile,
  );
  const sensitivePath = publishPaths.find(isSensitivePublishPath);
  if (sensitivePath) {
    return failure(
      action,
      "implement_mechanical_sensitive_path_present",
      `Refusing to publish sensitive path '${sensitivePath}'`,
      "remove_the_sensitive_path_from_the_change_and_retry",
    );
  }
  if (args.synchronization) {
    if (publishPaths.length > 0) {
      await deps.runGit(repoRoot, ["add", "-A"], deps.execFile);
    }
    const completed = await deps.synchronize({
      repoPath: repoRoot,
      issueNumber: args.issueNumber,
      branchName: args.branchName,
      action: "complete",
      recordId: args.synchronization.record_id,
      preSyncSha: args.synchronization.pre_sync_sha,
      fetchedBaseSha: args.synchronization.fetched_base_sha,
      outcome: args.synchronization.outcome,
      requestedRequirementUid: authorized.requirementUid,
    });
    if (!completed.ok) {
      return failure(
        action,
        completed.error,
        completed.message,
        completed.next_action ?? "repair_base_synchronization_and_retry",
        { synchronization: completed },
      );
    }
    return {
      ok: true,
      action,
      phase: "publish_complete",
      synchronization: completed,
      next_action: "render_and_create_the_synchronized_pr",
    };
  }

  if (publishPaths.length > 0) {
    const messageError = validateCommitMessage(args.commitMessage);
    if (messageError) {
      return failure(
        action,
        "implement_mechanical_commit_message_invalid",
        messageError,
        "supply_a_safe_imperative_commit_message_and_retry",
      );
    }
    await deps.runGit(repoRoot, ["add", "-A"], deps.execFile);
    const { stdout: stagedPaths } = await deps.runGit(
      repoRoot,
      ["diff", "--cached", "--name-only"],
      deps.execFile,
    );
    const paths = stagedPaths.split(/\r?\n/).filter(Boolean);
    if (paths.length === 0) {
      return failure(
        action,
        "implement_mechanical_nothing_to_commit",
        "No staged content changes are available to publish",
        "inspect_the_change_before_publishing",
      );
    }
    try {
      await deps.preCommit(
        repoRoot,
        deps.execFile,
        context,
        authorized.requirementUid,
      );
    } catch (error) {
      return commandFailure(action, "precommit", error);
    }
    try {
      await deps.runGit(repoRoot, ["commit", "-m", args.commitMessage], deps.execFile);
    } catch (error) {
      return commandFailure(action, "commit", error);
    }
  }
  try {
    await deps.runGit(
      repoRoot,
      ["push", "-u", "origin", args.branchName],
      deps.execFile,
    );
  } catch (error) {
    return commandFailure(action, "push", error);
  }
  const started = await deps.synchronize({
    repoPath: repoRoot,
    issueNumber: args.issueNumber,
    branchName: args.branchName,
    action: "start",
    requestedRequirementUid: authorized.requirementUid,
  });
  if (!started.ok) {
    return failure(
      action,
      started.error,
      started.message,
      started.next_action ?? "repair_base_synchronization_and_retry",
      { synchronization: started },
    );
  }
  if (started.status === "complete") {
    return {
      ok: true,
      action,
      phase: "publish_complete",
      synchronization: started,
      next_action: "render_and_create_the_synchronized_pr",
    };
  }
  if (started.status === "conflicts") {
    return failure(
      action,
      "implement_mechanical_merge_conflicts",
      "The freshly fetched integration branch conflicts with the feature branch",
      "resolve_every_conflict_then_retry_publish_completion",
      {
        synchronization: started,
        retry_input: {
          record_id: started.recordId,
          pre_sync_sha: started.preSyncSha,
          fetched_base_sha: started.fetchedBaseSha,
          outcome: started.outcome,
        },
      },
    );
  }
  if (started.status !== "merge_ready") {
    return failure(
      action,
      "implement_mechanical_sync_state_unknown",
      `Unexpected synchronization state '${started.status}'`,
      "inspect_the_preserved_synchronization_state",
      { synchronization: started },
    );
  }
  const completed = await deps.synchronize({
    repoPath: repoRoot,
    issueNumber: args.issueNumber,
    branchName: args.branchName,
    action: "complete",
    recordId: started.recordId,
    preSyncSha: started.preSyncSha,
    fetchedBaseSha: started.fetchedBaseSha,
    outcome: started.outcome,
    requestedRequirementUid: authorized.requirementUid,
  });
  if (!completed.ok) {
    return failure(
      action,
      completed.error,
      completed.message,
      completed.next_action ?? "repair_base_synchronization_and_retry",
      { synchronization: completed },
    );
  }
  return {
    ok: true,
    action,
    phase: "publish_complete",
    synchronization: completed,
    next_action: "render_and_create_the_synchronized_pr",
  };
}
export async function runMonitor(args, deps) {
  const action = "monitor";
  for (const field of ["branchName", "prNumber"]) {
    const invalid = requireField(args, field, action);
    if (invalid) return invalid;
  }
  // CI and SonarCloud are two distinct gates with distinct rework profiles, so this action records
  // two station attempts rather than one. The outer dispatcher leaves `monitor` un-instrumented for
  // exactly this reason.
  let ci;
  await deps.emitter.station("ci", async () => {
    ci = await deps.watchCi({
      repoPath: args.repoPath,
      branch: args.branchName,
    });
    const passed = ci.ok && ci.conclusion === "success";
    return {
      ok: passed,
      error: passed ? undefined : ci.error ?? `ci_${ci.conclusion ?? "unknown"}`,
      // `ci.ok === false` means the watcher could not observe a run at all — no verdict
      // exists to record. Only a run that actually concluded produces pass or fail.
      stationResult: ci.ok ? (passed ? "pass" : "fail") : "not_evaluable",
      ...(ci.ok ? { findings: ciGateFindings(ci).findings } : {}),
    };
  });
  if (!ci.ok || ci.conclusion !== "success") {
    return failure(
      action,
      ci.error ?? `ci_${ci.conclusion ?? "unknown"}`,
      ci.message ?? ci.log_summary ?? `CI concluded '${ci.conclusion ?? "unknown"}'`,
      "diagnose_and_fix_ci_then_rerun_publish_and_monitor",
      { failed_stage: "ci", ci },
    );
  }
  let sonar;
  let sonarPassed;
  await deps.emitter.station("sonarcloud", async () => {
    sonar = await deps.watchSonar({
      repoPath: args.repoPath,
      prNumber: args.prNumber,
    });
    sonarPassed =
      sonar.ok
      && (
        sonar.skipped === true
        || (
          sonar.quality_gate === "OK"
          && sonar.issues_summary?.open_count === 0
          && sonar.hotspots_summary?.open_count === 0
        )
      );
    return {
      ok: sonarPassed,
      error: sonarPassed ? undefined : sonar.error ?? "sonar_findings_open",
      // A repo with no sonarcloud block skips the gate: that is coverage, not a pass, and
      // counting it as one would inflate first-pass yield with runs Sonar never inspected.
      stationResult: sonar.ok ? (sonar.skipped === true ? "skipped_station" : sonarPassed ? "pass" : "fail") : "not_evaluable",
      ...(Array.isArray(sonar.measurement_findings) ? { findings: sonar.measurement_findings } : {}),
    };
  });
  if (!sonarPassed) {
    return failure(
      action,
      sonar.error ?? "sonar_findings_open",
      sonar.message ?? "SonarCloud has an incomplete gate, open issue, or open hotspot",
      "fix_sonar_findings_then_rerun_publish_and_monitor",
      { failed_stage: "sonar", sonar },
    );
  }
  return {
    ok: true,
    action,
    phase: "remote_gates_complete",
    ci,
    sonar,
    ci_status: "green",
    sonar_status: sonar.skipped ? "skipped" : "passed",
    next_action: "post_pre_merge_readiness",
  };
}
export function mapCompletion(args, phase) {
  const input = args.completion;
  return {
    repoPath: args.repoPath,
    issueNumber: args.issueNumber,
    prNumber: args.prNumber,
    requirements: (input.requirements ?? []).map((item) => ({
      uid: item.uid,
      title: item.title,
      status: item.status,
      statusIntent: item.status_intent,
      note: item.note,
    })),
    files: input.files,
    reviews: input.reviews,
    traceability: input.traceability,
    ciStatus: input.ci_status,
    sonarStatus: input.sonar_status,
    planCommentUrl: input.plan_comment_url,
    summary: input.summary,
    plainEnglishOutcome: input.plain_english_outcome,
    touchedFiles: input.touched_files,
    project: input.project,
    phase,
  };
}

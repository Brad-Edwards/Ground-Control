// Deterministic /implement phase automation.
//
// This module composes the existing guarded MCP primitives into coarse-grained
// successful-path phases. It deliberately does not invoke a model. A phase
// either completes mechanically or returns `agent_required: true` with the
// preserved state and a bounded reason for an agent to repair.

import { execFile as execFileCb } from "node:child_process";
import { promisify } from "node:util";
import { z } from "zod";
import {
  authorizeImplementMutationCheckout,
  getRepoGroundControlContext,
  getRequirementByUid,
  getTraceabilityByArtifact,
  runPrepareImplementBranch,
  runMarkImplementIssuePickedUp,
  runGetIssueThread,
  runAssertQualityGates,
  runSynchronizeImplementBranch,
  runWatchCiRun,
  runWatchSonarAnalysis,
  runAssertCompletion,
  runCloseIssueAfterMerge,
  detectSensitiveBodyContent,
  EXACT_REQUIREMENT_UID_RE,
  runImplementGitCommand,
  runImplementPreCommit,
  resolveWorkflowPolicyCommand,
} from "./lib.js";

const execFileAsync = promisify(execFileCb);

export const IMPLEMENT_MECHANICAL_ACTIONS = Object.freeze([
  "bootstrap",
  "verify",
  "publish",
  "monitor",
  "readiness",
  "finalize",
]);

const requirementShape = z.object({
  uid: z.string().min(1),
  status_intent: z.string().min(1).optional(),
});

const completionRequirementShape = z.object({
  uid: z.string().min(1),
  title: z.string().min(1).optional(),
  status: z.string().min(1).optional(),
  status_intent: z.string().min(1).optional(),
  note: z.string().optional(),
});

const completionShape = z.object({
  requirements: z.array(completionRequirementShape),
  files: z.object({
    added: z.array(z.string()).optional(),
    modified: z.array(z.string()).optional(),
    renamed: z.array(z.string()).optional(),
    deleted: z.array(z.string()).optional(),
  }),
  reviews: z.array(z.object({
    reviewer: z.string().min(1),
    summary: z.string().min(1),
  })),
  traceability: z.object({
    added: z.array(z.string()).optional(),
    updated: z.array(z.string()).optional(),
    deleted: z.array(z.string()).optional(),
    notes: z.string().optional(),
  }).optional(),
  ci_status: z.string().min(1),
  sonar_status: z.string().min(1),
  plan_comment_url: z.string().url().nullable().optional(),
  summary: z.string().min(1).optional(),
  plain_english_outcome: z.string().min(1),
  touched_files: z.array(z.string()).optional(),
  project: z.string().min(1).optional(),
});

export const gcImplementMechanicalZodShape = {
  action: z.enum(IMPLEMENT_MECHANICAL_ACTIONS),
  repo_path: z.string().min(1),
  invocation_root: z.string().min(1).optional(),
  issue_number: z.number().int().positive(),
  branch_name: z.string().min(1).max(50).optional(),
  base_branch: z.string().min(1).optional(),
  driver: z.string().regex(/^[a-z0-9._-]{1,40}$/i).optional(),
  requested_requirement_uid: z.string().regex(EXACT_REQUIREMENT_UID_RE).optional(),
  requirements: z.array(requirementShape).optional(),
  commit_message: z.string().min(1).max(200).optional(),
  synchronization: z.object({
    record_id: z.string().regex(/^[0-9a-f]{32}$/),
    pre_sync_sha: z.string().regex(/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/),
    fetched_base_sha: z.string().regex(/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/),
    outcome: z.enum(["merged_clean", "merged_conflicts_resolved"]),
  }).optional(),
  pr_number: z.number().int().positive().optional(),
  completion: completionShape.optional(),
};

export const GC_IMPLEMENT_MECHANICAL_DESCRIPTION =
  "Run coarse-grained deterministic /implement phases without a model turn per mechanical step. " +
  "Actions: bootstrap (issue/branch/context/pickup), verify (configured completion command + configured " +
  "workflow.policy_command + quality gates), " +
  "publish (stage + pre-commit + commit + push + remote-base synchronization), monitor (CI + Sonar), " +
  "readiness (pre-merge completion assertion), finalize (post-merge assertion + idempotent issue close). " +
  "Always pass action, repo_path, and issue_number. Depending on action, also pass invocation_root, branch_name, " +
  "base_branch, driver, requested_requirement_uid, requirements, commit_message, synchronization, pr_number, or completion. " +
  "A phase either completes or returns agent_required=true with a bounded repair reason; it never invokes an agent.";

function bounded(value, max = 1200) {
  const text = typeof value === "string" ? value : String(value ?? "");
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}

function failure(action, error, message, nextAction, extra = {}) {
  const safeMessage = bounded(message);
  return {
    ok: false,
    action,
    error,
    message: detectSensitiveBodyContent(safeMessage) ? "<redacted>" : safeMessage,
    agent_required: true,
    next_action: nextAction,
    ...extra,
  };
}

function requireField(args, field, action) {
  if (args[field] == null || args[field] === "") {
    return failure(
      action,
      "implement_mechanical_input_invalid",
      `${field} is required for action=${action}`,
      "supply_the_required_structured_input_and_retry",
    );
  }
  return null;
}

function commandFailure(action, stage, error) {
  const detail =
    typeof error?.stderr === "string" && error.stderr.trim() !== ""
      ? error.stderr
      : error?.message;
  return failure(
    action,
    `implement_mechanical_${stage}_failed`,
    detail ?? `${stage} failed`,
    `repair_${stage}_and_retry`,
    { failed_stage: stage },
  );
}

export function extractInScopeRequirementUids(issueBody) {
  if (typeof issueBody !== "string" || issueBody === "") return [];

  const sectionLines = [];
  let sectionLevel = null;
  for (const line of issueBody.split(/\r?\n/)) {
    const heading = line.match(/^(#{1,6})\s+(.+?)\s*$/);
    if (heading) {
      const level = heading[1].length;
      const title = heading[2].trim().toLowerCase();
      if (sectionLevel == null) {
        if (level >= 2 && level <= 4 && title === "requirements") {
          sectionLevel = level;
        }
        continue;
      }
      if (level <= sectionLevel) break;
      sectionLines.push(line);
      continue;
    }
    if (sectionLevel != null) sectionLines.push(line);
  }

  const seen = new Set();
  const result = [];
  for (const line of sectionLines) {
    const bullet = line.match(/^\s*[-*+]\s+(.+?)\s*$/);
    if (!bullet) continue;
    for (const token of bullet[1].split(/[\s,;]+/)) {
      const candidate = token.replace(/^[`[(]+|[`)\].:]+$/g, "");
      if (!EXACT_REQUIREMENT_UID_RE.test(candidate)) break;
      if (!seen.has(candidate)) {
        seen.add(candidate);
        result.push(candidate);
      }
    }
  }
  return result;
}

async function readStatus(repoRoot, runGit, commandRunner) {
  const { stdout } = await runGit(
    repoRoot,
    ["status", "--porcelain=v1", "--untracked-files=normal"],
    commandRunner,
  );
  return stdout;
}

async function runBootstrap(args, deps) {
  const action = "bootstrap";
  for (const field of ["invocationRoot", "branchName", "driver"]) {
    const invalid = requireField(args, field, action);
    if (invalid) return invalid;
  }
  const context = await deps.getContext(args.repoPath);
  if (context?.status !== "ok") {
    return failure(
      action,
      "implement_mechanical_context_invalid",
      context?.errors?.join("; ") ?? "Ground Control repository context is unavailable",
      "repair_ground_control_context_and_retry",
    );
  }
  const prepared = await deps.prepareBranch({
    repoPath: args.repoPath,
    invocationRoot: args.invocationRoot,
    issueNumber: args.issueNumber,
    branchName: args.branchName,
    baseBranch: args.baseBranch ?? context.workflow?.base_branch ?? "dev",
    checkoutMode: "same_checkout",
  });
  if (!prepared.ok) {
    return failure(action, prepared.error, prepared.message, prepared.next_action ?? "repair_branch_and_retry");
  }
  const thread = await deps.getIssueThread({
    repoPath: args.repoPath,
    issueNumber: args.issueNumber,
  });
  if (!thread.ok) {
    return failure(action, thread.error, thread.message, "repair_issue_access_and_retry");
  }
  const requirementUids = extractInScopeRequirementUids(thread.body);
  if (
    args.requestedRequirementUid != null
    && !requirementUids.includes(args.requestedRequirementUid)
  ) {
    return failure(
      action,
      "implement_mechanical_requested_requirement_out_of_scope",
      `The issue Requirements section does not include '${args.requestedRequirementUid}'`,
      "add_the_requested_requirement_to_the_authoritative_issue_section_and_retry",
    );
  }
  let requirements;
  let issueTraceabilityLinks;
  try {
    requirements = await Promise.all(requirementUids.map(async (uid) => {
      const requirement = await deps.getRequirement(uid, context.project);
      return {
        id: requirement.id,
        uid: requirement.uid,
        title: requirement.title,
        statement: requirement.statement,
        status: requirement.status,
        wave: requirement.wave,
      };
    }));
    issueTraceabilityLinks = await deps.getTraceabilityByArtifact(
      "GITHUB_ISSUE",
      String(args.issueNumber),
      context.project,
    );
  } catch (error) {
    return failure(
      action,
      "implement_mechanical_issue_context_failed",
      error.message,
      "repair_requirement_or_traceability_access_and_retry",
    );
  }
  const pickupAlreadyRecorded = (thread.comments ?? []).some((comment) =>
    typeof comment?.body === "string"
    && comment.body.includes("Picked up by /implement")
    && comment.body.includes(`\`${prepared.branch}\``),
  );
  let pickup = { ok: true, reused: true };
  if (!pickupAlreadyRecorded) {
    pickup = await deps.markPickedUp({
      repoPath: args.repoPath,
      issueNumber: args.issueNumber,
      driver: args.driver,
      branchName: prepared.branch,
    });
    if (!pickup.ok) {
      return failure(action, pickup.error, pickup.message, "repair_pickup_record_and_retry");
    }
  }
  return {
    ok: true,
    action,
    phase: "bootstrap_complete",
    repo_path: prepared.repo_path,
    branch: prepared.branch,
    project: context.project,
    config: context,
    issue: {
      number: args.issueNumber,
      title: thread.title,
      body: thread.body,
      labels: thread.labels,
      comments: thread.comments,
      url: thread.url,
      hash: thread.hash,
    },
    requirement_uids: requirementUids,
    in_scope_requirements: requirements,
    issue_traceability_links: issueTraceabilityLinks,
    pickup,
    next_action: "run_agent_architecture_assessment_and_plan",
  };
}

async function runVerify(args, deps) {
  const action = "verify";
  const context = await deps.getContext(args.repoPath);
  if (context?.status !== "ok") {
    return failure(
      action,
      "implement_mechanical_context_invalid",
      context?.errors?.join("; ") ?? "Ground Control repository context is unavailable",
      "repair_ground_control_context_and_retry",
    );
  }
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
  const command =
    context.workflow?.completion_command ?? context.workflow?.test_command;
  if (typeof command !== "string" || command.trim() === "") {
    return failure(
      action,
      "implement_mechanical_completion_command_missing",
      "No completion or test command is configured",
      "configure_a_completion_command_and_retry",
    );
  }
  const before = await readStatus(repoRoot, deps.runGit, deps.execFile);
  try {
    await deps.execFile("bash", ["-c", command], { cwd: repoRoot });
  } catch (error) {
    return commandFailure(action, "completion_gate", error);
  }
  const policyCommand = resolveWorkflowPolicyCommand(context);
  try {
    await deps.execFile("bash", ["-c", policyCommand], { cwd: repoRoot });
  } catch (error) {
    return commandFailure(action, "policy_gate", error);
  }
  const after = await readStatus(repoRoot, deps.runGit, deps.execFile);
  if (after !== before) {
    return failure(
      action,
      "implement_mechanical_gate_tree_changed",
      "The completion or policy gate changed the checkout",
      "inspect_and_commit_or_revert_gate_generated_changes",
    );
  }
  const quality = await deps.assertQuality({
    project: context.project,
    requirements: args.requirements ?? [],
  });
  if (!quality.ok) {
    return failure(
      action,
      quality.error,
      quality.message,
      quality.next_action ?? "repair_quality_gate_and_retry",
      { quality },
    );
  }
  return {
    ok: true,
    action,
    phase: "verification_complete",
    completion_command: command,
    policy_command: policyCommand,
    policy: "passed",
    quality,
    next_action: "run_required_agent_reviews_or_publish",
  };
}

function validateCommitMessage(message) {
  if (typeof message !== "string" || message.trim() === "") {
    return "commit_message is required";
  }
  if (/[\r\n]/.test(message)) return "commit_message must be a single line";
  if (/\b(?:codex|claude|chatgpt|openai|anthropic)\b|co-authored-by|generated with/i.test(message)) {
    return "commit_message must not contain assistant or vendor attribution";
  }
  return null;
}

const SENSITIVE_STAGED_PATH_RE =
  /(?:^|\/)(?:\.secrets?(?:\/|$)|credentials?(?:\.|\/|$))|(?:^|\/)[^/]+\.(?:pem|key|p12|pfx)$/i;

function isSensitivePublishPath(path) {
  const basename = path.split("/").at(-1);
  const sensitiveEnv =
    /^\.env(?:\.|$)/i.test(basename)
    && !/^\.env\.(?:example|sample|template)$/i.test(basename);
  return sensitiveEnv || SENSITIVE_STAGED_PATH_RE.test(path);
}

function splitNullPaths(stdout) {
  return stdout.split("\0").filter(Boolean);
}

async function readPublishPaths(repoRoot, runGit, commandRunner) {
  const [tracked, staged, untracked] = await Promise.all([
    runGit(repoRoot, ["diff", "--name-only", "-z"], commandRunner),
    runGit(repoRoot, ["diff", "--cached", "--name-only", "-z"], commandRunner),
    runGit(repoRoot, ["ls-files", "--others", "--exclude-standard", "-z"], commandRunner),
  ]);
  return [...new Set([
    ...splitNullPaths(tracked.stdout),
    ...splitNullPaths(staged.stdout),
    ...splitNullPaths(untracked.stdout),
  ])];
}

async function runPublish(args, deps) {
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
      await deps.preCommit(repoRoot, deps.execFile, context);
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

async function runMonitor(args, deps) {
  const action = "monitor";
  for (const field of ["branchName", "prNumber"]) {
    const invalid = requireField(args, field, action);
    if (invalid) return invalid;
  }
  const ci = await deps.watchCi({
    repoPath: args.repoPath,
    branch: args.branchName,
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
  const sonar = await deps.watchSonar({
    repoPath: args.repoPath,
    prNumber: args.prNumber,
  });
  const sonarPassed =
    sonar.ok
    && (
      sonar.skipped === true
      || (
        sonar.quality_gate === "OK"
        && sonar.issues_summary?.open_count === 0
        && sonar.hotspots_summary?.open_count === 0
      )
    );
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

function mapCompletion(args, phase) {
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

async function runReadiness(args, deps) {
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

async function runFinalize(args, deps) {
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

const defaultDeps = {
  execFile: execFileAsync,
  authorizeRepo: authorizeImplementMutationCheckout,
  runGit: runImplementGitCommand,
  preCommit: runImplementPreCommit,
  getContext: getRepoGroundControlContext,
  getRequirement: getRequirementByUid,
  getTraceabilityByArtifact,
  prepareBranch: runPrepareImplementBranch,
  markPickedUp: runMarkImplementIssuePickedUp,
  getIssueThread: runGetIssueThread,
  assertQuality: runAssertQualityGates,
  synchronize: runSynchronizeImplementBranch,
  watchCi: runWatchCiRun,
  watchSonar: runWatchSonarAnalysis,
  assertCompletion: runAssertCompletion,
  closeIssue: runCloseIssueAfterMerge,
};

export async function runImplementMechanical(args, overrides = {}) {
  const deps = { ...defaultDeps, ...overrides };
  switch (args.action) {
    case "bootstrap":
      return runBootstrap(args, deps);
    case "verify":
      return runVerify(args, deps);
    case "publish":
      return runPublish(args, deps);
    case "monitor":
      return runMonitor(args, deps);
    case "readiness":
      return runReadiness(args, deps);
    case "finalize":
      return runFinalize(args, deps);
    default:
      return {
        ok: false,
        error: "implement_mechanical_action_invalid",
        message: `Unknown action '${args.action}'`,
        agent_required: false,
      };
  }
}

export async function gcImplementMechanicalToolHandler(args, overrides = {}) {
  return runImplementMechanical({
    action: args.action,
    repoPath: args.repo_path,
    invocationRoot: args.invocation_root,
    issueNumber: args.issue_number,
    branchName: args.branch_name,
    baseBranch: args.base_branch,
    driver: args.driver,
    requestedRequirementUid: args.requested_requirement_uid,
    requirements: args.requirements,
    commitMessage: args.commit_message,
    synchronization: args.synchronization,
    prNumber: args.pr_number,
    completion: args.completion,
  }, overrides);
}

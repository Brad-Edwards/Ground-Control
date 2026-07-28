// Deterministic /implement phase automation.
//
// This module composes the existing guarded MCP primitives into coarse-grained
// successful-path phases. It deliberately does not invoke a model. A phase
// either completes mechanically or returns `agent_required: true` with the
// preserved state and a bounded reason for an agent to repair.

import { execFile as execFileCb } from "node:child_process";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
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
  extractInScopeRequirementUids,
  requestedRequirementUidAuthorization,
  authorizeRequestedRequirementUid,
  runImplementGitCommand,
  runImplementPreCommit,
  resolveWorkflowPolicyCommand,
  implementGateEnvironment,
} from "./lib.js";
import { createWorkflowRunLifecycleEmitter } from "./workflow-run-lifecycle.js";
import {
  ciGateFindings,
  policyGateFindings,
  spotbugsGateFindings,
  valeGateFindings,
} from "./gate-finding-adapters.js";

export { extractInScopeRequirementUids };

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
  "requested_requirement_uid names the requirement under test. Every action that can reach a repository gate resolves it " +
  "server-side against the target issue's Requirements section and refuses an unlisted UID; verify and publish then export " +
  "the bound value to every repo-authored gate as ACES_REQUIREMENT_UID, so a governance gate still receives requirement " +
  "identity on an issue branch that carries no UID. " +
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
  // Bootstrap already holds the authoritative thread, so it binds against that
  // body directly rather than re-reading it.
  const authorized = requestedRequirementUidAuthorization(
    thread.body,
    args.requestedRequirementUid,
  );
  if (!authorized.ok) {
    return failure(action, authorized.error, authorized.message, authorized.next_action);
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


/**
 * Where each child gate writes its structured artifact for this verify run.
 *
 * Under the repo's build directory rather than a temp dir so the files sit beside the reports
 * the gates already produce and are cleaned by the same `clean`.
 */
function childGateArtifactPaths(repoRoot) {
  const dir = join(repoRoot, "build", "gc-measurement");
  return { dir, policy: join(dir, "policy.json"), vale: join(dir, "vale.json") };
}

/** Read a gate's JSON artifact, or null when it did not produce one. */
function readGateArtifact(path) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    // Absent or malformed: the attempt is recorded not_evaluable rather than guessed at.
    return null;
  }
}

/**
 * Record the SpotBugs attempt from the XML report the completion command already wrote.
 *
 * SpotBugs runs inside the completion command, so its duration is not separable from the
 * parent's; the parent's measured duration is passed rather than fabricating a child-specific
 * one, and the attempt is skipped entirely when no report exists.
 */
async function emitSpotbugsAttempt(emitter, repoRoot, timing) {
  const reportDir = join(repoRoot, "backend", "build", "reports", "spotbugs");
  let xml = "";
  try {
    for (const entry of readdirSync(reportDir)) {
      if (entry.endsWith(".xml")) xml += readFileSync(join(reportDir, entry), "utf8");
    }
  } catch {
    return;
  }
  if (xml === "") return;
  const { findings } = spotbugsGateFindings(xml);
  await emitter.recordStationAttempt({
    stationId: "spotbugs",
    startedAt: timing.startedAt,
    endedAt: new Date(),
    durationMs: timing.durationMs,
    stationResult: findings.length === 0 ? "pass" : "fail",
    findings,
  });
}

/**
 * Record the policy and Vale attempts from the artifacts `make policy` just wrote.
 *
 * Each carries its own verdict. Policy also reports its own duration, so the parent command's
 * duration is never divided between them; Vale reports none and therefore records none rather
 * than inheriting one.
 */
async function emitPolicyAndValeAttempts(emitter, artifacts, startedAt) {
  const policy = readGateArtifact(artifacts.policy);
  if (policy) {
    const { findings } = policyGateFindings(policy);
    await emitter.recordStationAttempt({
      stationId: "policy",
      startedAt,
      endedAt: new Date(),
      durationMs: Number.isFinite(policy.duration_ms) ? policy.duration_ms : undefined,
      stationResult: findings.length === 0 ? "pass" : "fail",
      findings,
    });
  }
  const vale = readGateArtifact(artifacts.vale);
  if (vale) {
    const { findings } = valeGateFindings(vale);
    await emitter.recordStationAttempt({
      stationId: "vale",
      startedAt,
      endedAt: new Date(),
      stationResult: findings.length === 0 ? "pass" : "fail",
      findings,
    });
  }
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
  // The repository's gates may need the requirement under test, which they
  // normally read from the branch name. A run that targets a requirement whose
  // branch carries no UID supplies it here instead (issue #1434). Building the
  // environment before the first gate keeps an invalid value from surfacing as
  // a misleading gate failure.
  const authorized = await deps.authorizeRequirementUid({
    repoPath: args.repoPath,
    issueNumber: args.issueNumber,
    requestedRequirementUid: args.requestedRequirementUid,
  });
  if (!authorized.ok) {
    return failure(action, authorized.error, authorized.message, authorized.next_action);
  }
  const gateEnv = implementGateEnvironment(authorized.requirementUid);
  // Child gates write their own structured artifacts so their facts come from the run that
  // already happened. Nothing is re-executed to be measured, and no combined console transcript
  // is parsed (issue #1355).
  const artifacts = childGateArtifactPaths(repoRoot);
  const childEnv = { ...gateEnv, GC_POLICY_JSON: artifacts.policy, GC_VALE_JSON: artifacts.vale };
  const before = await readStatus(repoRoot, deps.runGit, deps.execFile);
  const completionStartedAt = new Date();
  const completionStartedMs = Date.now();
  let completionError = null;
  try {
    await deps.execFile("bash", ["-c", command], { cwd: repoRoot, env: childEnv });
  } catch (error) {
    completionError = error;
  }
  await emitSpotbugsAttempt(deps.emitter, repoRoot, {
    startedAt: completionStartedAt,
    durationMs: Date.now() - completionStartedMs,
  });
  if (completionError) {
    return commandFailure(action, "completion_gate", completionError);
  }
  const policyCommand = resolveWorkflowPolicyCommand(context);
  const policyStartedAt = new Date();
  let policyError = null;
  try {
    await deps.execFile("bash", ["-c", policyCommand], { cwd: repoRoot, env: childEnv });
  } catch (error) {
    policyError = error;
  }
  await emitPolicyAndValeAttempts(deps.emitter, artifacts, policyStartedAt);
  if (policyError) {
    return commandFailure(action, "policy_gate", policyError);
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

async function runMonitor(args, deps) {
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

/**
 * Station id per mechanical action (issue #1435). These are stable machine ids from the workflow's
 * own phase vocabulary — never a SKILL step number, display label, MCP tool name, or `next_action`
 * value, all of which are aliases that change without the gate changing.
 *
 * `monitor` is deliberately absent: it runs two distinct gates and instruments them itself.
 */
const STATION_BY_ACTION = Object.freeze({
  bootstrap: "issue_branch_resolution",
  verify: "completion_gate",
  publish: "git_publish",
});

/**
 * Actions that record a transition rather than inspect anything (issue #1355).
 *
 * `readiness` and `post_merge` were previously routed through the station channel, which made
 * them look like gates whose verdict was permanently unobservable. The catalogue declares them
 * lifecycle markers; a per-station yield computed over them would have been counting transitions
 * as inspections.
 */
const MARKER_BY_ACTION = Object.freeze({
  readiness: "ready_for_review",
  finalize: "post_merge",
});

/**
 * Error codes that are a gate's own verdict rather than a failure to reach one.
 *
 * The distinction is the whole point of the separate axis: `make check` exiting non-zero is the
 * completion gate saying "fail", while an unreadable `.ground-control.yaml` means no gate ran and
 * nothing can be concluded. Collapsing the second into `fail` would invent defects out of
 * configuration errors and permanently depress first-pass yield.
 */
const GATE_VERDICT_ERRORS = Object.freeze({
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

/**
 * State this action's station verdict explicitly.
 *
 * Deliberately not `ok ? "pass" : "fail"`: that generic rule is what ADR-090 section 3 forbids,
 * because it reads every operational failure as a quality defect. Each action names the codes that
 * are genuinely its gate speaking; everything else is not_evaluable.
 */
function classifyStationResult(action, envelope) {
  if (envelope?.ok) return "pass";
  const verdictErrors = GATE_VERDICT_ERRORS[action];
  if (verdictErrors?.has(envelope?.error)) return "fail";
  // A quality-gate refusal is the gate rendering a verdict, even though it arrives as its own
  // structured error rather than a command exit code.
  if (action === "verify" && envelope?.quality && envelope.quality.ok === false) return "fail";
  return "not_evaluable";
}

/** No-op lifecycle emitter used whenever the run identity cannot be resolved. */
const INERT_EMITTER = Object.freeze({
  openRun: async () => null,
  ensureRun: async () => null,
  markState: async () => null,
  closeRun: async () => null,
  recordRequirementUids: async () => null,
  recordStationAttempt: async () => null,
  markerTransition: async () => null,
  station: async (_phase, fn) => fn(),
});

/**
 * Wrap an emitter so a defect inside it can never surface as a workflow failure.
 *
 * `station` is the delicate one: when the call throws, the failure is either the phase's own (which
 * must propagate unchanged) or the emitter's (which must be invisible). Tracking whether the phase
 * ran, and what it produced, is what tells those two apart — and it guarantees the phase runs
 * exactly once either way.
 */
function guardEmitter(emitter) {
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

/**
 * The branch half of the run's natural key. `readiness` and `finalize` do not take `branch_name`,
 * so without this the emitter would upsert a *different*, nullable-branch run and mark that one
 * merged while the branch-qualified run opened at bootstrap stayed RUNNING forever. The checkout is
 * on the issue branch by construction, so reading it is an observation, not a guess.
 */
async function resolveRunBranch(args, deps) {
  if (args.branchName) return args.branchName;
  try {
    const { stdout } = await deps.runGit(args.repoPath, ["branch", "--show-current"], deps.execFile);
    const branch = stdout.trim();
    return branch === "" ? null : branch;
  } catch {
    return null;
  }
}

/**
 * Resolve the run identity from repository context. Returns the inert emitter when any part of the
 * natural key is unavailable: a partial key would fabricate a second run rather than observe the
 * real one, which is worse than recording nothing.
 */
async function resolveEmitter(args, deps) {
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

/**
 * Apply the run-state transitions this action's outcome demonstrates. Only transitions the tool
 * layer can actually observe are recorded: a merged PR, a PR closed without merging, and the paused
 * ready-for-review state. An `agent_required` failure is a failed *attempt*, not a failed run — the
 * caller repairs it and retries — so it leaves the run open.
 */
async function applyRunStateTransition(action, result, emitter) {
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

const defaultDeps = {
  execFile: execFileAsync,
  createLifecycle: createWorkflowRunLifecycleEmitter,
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
  authorizeRequirementUid: authorizeRequestedRequirementUid,
  closeIssue: runCloseIssueAfterMerge,
};

function dispatch(args, deps) {
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
      return Promise.resolve({
        ok: false,
        error: "implement_mechanical_action_invalid",
        message: `Unknown action '${args.action}'`,
        agent_required: false,
      });
  }
}

export async function runImplementMechanical(args, overrides = {}) {
  const deps = { ...defaultDeps, ...overrides };
  if (!IMPLEMENT_MECHANICAL_ACTIONS.includes(args.action)) {
    return dispatch(args, deps);
  }

  // Lifecycle observation (issue #1435). A phase is never allowed to fail, change, or stall because
  // recording it failed, so every emitter call goes through the guard above. The emitter timestamps
  // each transition immediately and queues the transport, so none of the calls below waits on the
  // backend — the awaits here settle on the next microtask regardless of how the network behaves.
  const emitter = guardEmitter(await resolveEmitter(args, deps));
  // Recorded as opening before dispatch, not after: a run that only becomes visible once it
  // finishes is exactly the gap this closes.
  await (args.action === "bootstrap" ? emitter.openRun() : emitter.ensureRun());

  const station = STATION_BY_ACTION[args.action];
  const instrumented = { ...deps, emitter };

  let result;
  if (station) {
    // The emitter is handed an observation of the dispatch, not the dispatch envelope itself:
    // the station verdict is measurement and must not become a field of the tool's public
    // contract. The envelope escapes through the closure exactly as it is.
    await emitter.station(station, async () => {
      result = await dispatch(args, instrumented);
      return {
        ok: result.ok,
        error: result.error,
        stationResult: classifyStationResult(args.action, result),
        ...(Array.isArray(result.measurement_findings) ? { findings: result.measurement_findings } : {}),
      };
    });
  } else {
    result = await dispatch(args, instrumented);
  }

  const marker = MARKER_BY_ACTION[args.action];
  if (marker && result.ok) {
    await emitter.markerTransition(marker);
  }

  if (args.action === "bootstrap" && result.ok) {
    await emitter.recordRequirementUids(result.requirement_uids);
  }
  await applyRunStateTransition(args.action, result, emitter);
  return result;
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

// Extracted from gc-implement-mechanical.js (issue #1355).
//
// The module had reached 1,231 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md). gc-implement-mechanical.js remains the tool entry point.

import { implementGateEnvironment, resolveWorkflowPolicyCommand } from "../lib.js";
import { childGateArtifactPaths, commandFailure, emitPolicyAndValeAttempts, emitSpotbugsAttempt, failure, readStatus } from "./gate-helpers.js";

export async function runVerify(args, deps) {
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
    // Gradle's report tree is not cleared between attempts, so the emitter needs a floor to tell
    // this attempt's report from the last one's.
    freshnessFloorMs: artifacts.freshnessFloorMs,
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
export function validateCommitMessage(message) {
  if (typeof message !== "string" || message.trim() === "") {
    return "commit_message is required";
  }
  if (/[\r\n]/.test(message)) return "commit_message must be a single line";
  if (/\b(?:codex|claude|chatgpt|openai|anthropic)\b|co-authored-by|generated with/i.test(message)) {
    return "commit_message must not contain assistant or vendor attribution";
  }
  return null;
}
export const SENSITIVE_STAGED_PATH_RE =
  /(?:^|\/)(?:\.secrets?(?:\/|$)|credentials?(?:\.|\/|$))|(?:^|\/)[^/]+\.(?:pem|key|p12|pfx)$/i;
export function isSensitivePublishPath(path) {
  const basename = path.split("/").at(-1);
  const sensitiveEnv =
    /^\.env(?:\.|$)/i.test(basename)
    && !/^\.env\.(?:example|sample|template)$/i.test(basename);
  return sensitiveEnv || SENSITIVE_STAGED_PATH_RE.test(path);
}
export function splitNullPaths(stdout) {
  return stdout.split("\0").filter(Boolean);
}
export async function readPublishPaths(repoRoot, runGit, commandRunner) {
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

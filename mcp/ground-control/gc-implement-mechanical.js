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
import { MARKER_BY_ACTION, STATION_BY_ACTION, applyRunStateTransition, classifyStationResult, guardEmitter, resolveEmitter, runFinalize, runReadiness } from "./implement/completion.js";
import { completionShape, execFileAsync, requirementShape, runBootstrap } from "./implement/gate-helpers.js";
import { runMonitor, runPublish } from "./implement/publish.js";
import { runVerify } from "./implement/verify.js";

export { extractInScopeRequirementUids };

export const IMPLEMENT_MECHANICAL_ACTIONS = Object.freeze([
  "bootstrap",
  "verify",
  "publish",
  "monitor",
  "readiness",
  "finalize",
]);
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

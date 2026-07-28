// Extracted from gc-implement-mechanical.js (issue #1355).
//
// The module had reached 1,231 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md). gc-implement-mechanical.js remains the tool entry point.

import { policyGateFindings, spotbugsGateFindings, valeGateFindings } from "../gate-finding-adapters.js";
import { detectSensitiveBodyContent, extractInScopeRequirementUids, requestedRequirementUidAuthorization } from "../lib.js";
import { execFile as execFileCb } from "node:child_process";
import { mkdirSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { promisify } from "node:util";
import { z } from "zod";

export const execFileAsync = promisify(execFileCb);
export const requirementShape = z.object({
  uid: z.string().min(1),
  status_intent: z.string().min(1).optional(),
});
export const completionRequirementShape = z.object({
  uid: z.string().min(1),
  title: z.string().min(1).optional(),
  status: z.string().min(1).optional(),
  status_intent: z.string().min(1).optional(),
  note: z.string().optional(),
});
export const completionShape = z.object({
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
export function bounded(value, max = 1200) {
  const text = typeof value === "string" ? value : String(value ?? "");
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}
export function failure(action, error, message, nextAction, extra = {}) {
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
export function requireField(args, field, action) {
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
export function commandFailure(action, stage, error) {
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
export async function readStatus(repoRoot, runGit, commandRunner) {
  const { stdout } = await runGit(
    repoRoot,
    ["status", "--porcelain=v1", "--untracked-files=normal"],
    commandRunner,
  );
  return stdout;
}
export async function runBootstrap(args, deps) {
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
export function childGateArtifactPaths(repoRoot) {
  const dir = join(repoRoot, "build", "gc-measurement");
  // Created here rather than left to the gates. Each writes fail-open, so a missing directory
  // would swallow the write and the policy and vale stations would silently never be recorded —
  // measurement absent for the reason hardest to notice, because nothing fails.
  try {
    mkdirSync(dir, { recursive: true });
  } catch {
    // Best effort: a gate that cannot write its artifact is recorded as unmeasured, never as a pass.
  }
  return { dir, policy: join(dir, "policy.json"), vale: join(dir, "vale.json") };
}
export function readGateArtifact(path) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    // Absent or malformed: the attempt is recorded not_evaluable rather than guessed at.
    return null;
  }
}
export async function emitSpotbugsAttempt(emitter, repoRoot, timing) {
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
  const { findings, dropped } = spotbugsGateFindings(xml);
  await emitter.recordStationAttempt({
    stationId: "spotbugs",
    startedAt: timing.startedAt,
    endedAt: new Date(),
    durationMs: timing.durationMs,
    stationResult: findings.length === 0 ? "pass" : "fail",
    findings,
    findingsDropped: dropped,
  });
}
export async function emitPolicyAndValeAttempts(emitter, artifacts, startedAt) {
  const policy = readGateArtifact(artifacts.policy);
  if (policy) {
    const { findings, dropped } = policyGateFindings(policy);
    await emitter.recordStationAttempt({
      stationId: "policy",
      startedAt,
      endedAt: new Date(),
      durationMs: Number.isFinite(policy.duration_ms) ? policy.duration_ms : undefined,
      stationResult: findings.length === 0 ? "pass" : "fail",
      findings,
      findingsDropped: dropped,
    });
  }
  const vale = readGateArtifact(artifacts.vale);
  if (vale) {
    const { findings, dropped } = valeGateFindings(vale);
    await emitter.recordStationAttempt({
      stationId: "vale",
      startedAt,
      endedAt: new Date(),
      stationResult: findings.length === 0 ? "pass" : "fail",
      findings,
      findingsDropped: dropped,
    });
  }
}

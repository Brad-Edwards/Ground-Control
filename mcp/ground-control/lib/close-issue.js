// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { TO_CAMEL } from "./field-mapping.js";
import { extractGhErrorMessage } from "./grc-legacy-compat-2.js";
import { getOwnerRepo, readIssueCommentBodies, validateSourceDevStartGate } from "./grc-legacy-compat-3.js";
import { ensureGitRepo } from "./grc-legacy-compat-4.js";
import { devStartGateConfigFailure, devStartGateFailure, readDevStartPlanFields, readSourceBearingDecision, validateNonSourceDevStartGate } from "./grc-legacy-compat.js";
import { buildCodexReviewCycleMarker, normalizeDevStartGateConfig, parseCodexReviewCycleMarkers } from "./repo-context-2.js";
import { execFile } from "./runtime-primitives.js";

const TO_SNAKE = Object.fromEntries(Object.entries(TO_CAMEL).map(([k, v]) => [v, k]));
export const OPAQUE_VALUE_KEYS = new Set([
  "metadata",
  "schemaBody",
  "schema_body",
  // GC-T014: NIST SP 800-30 Rev. 1 methodology-defined value bags. inputFactors
  // / computedOutputs / uncertaintyMetadata persist NIST profile-defined keys
  // (threat_event_relevance, legacy threat_source_relevance,
  // likelihood_initiation, likelihood_adverse_impact, likelihood_overall,
  // impact_level, ...) that must reach the caller verbatim.
  // inputSchema / outputSchema carry the methodology JSON Schema bodies whose
  // properties keys are likewise methodology-defined (see preflight note
  // architecture/notes/nist-sp800-30-risk-assessment-preflight.md).
  "inputFactors",
  "input_factors",
  "computedOutputs",
  "computed_outputs",
  "uncertaintyMetadata",
  "uncertainty_metadata",
  "inputSchema",
  "input_schema",
  "outputSchema",
  "output_schema",
  "treatmentStrategyVocabulary",
  "treatment_strategy_vocabulary",
  // #1106 — ControlRequest/UpdateControlRequest: methodologyFactors and
  // effectiveness are Map<String,Object> bags whose inner keys are
  // methodology-defined and must not be camel-cased.
  "methodologyFactors",
  "methodology_factors",
  "effectiveness",
  // #1106 — RiskRegisterRecordRequest.decisionMetadata is Map<String,Object>.
  "decisionMetadata",
  "decision_metadata",
  // GC-I017 — RiskControlMapping.methodologyInfluence is a Map<String,Object>
  // whose inner keys are methodology-defined (FAIR-CAM domain, effect dimensions,
  // and any profile-defined influence factors). These must not be camel/snake-rewritten.
  "methodologyInfluence",
  "methodology_influence",
  // NOTE: VerificationResultRequest.evidence is also a Map<String,Object>, but
  // "evidence" is also used as a structured array field in analysis responses
  // (toSnakeCase path). OPAQUE_VALUE_KEYS is consulted by both directions, so
  // adding "evidence" here would block toSnakeCase from recursing into response
  // evidence arrays. Instead, createVerificationResult/updateVerificationResult
  // build the camelCase body explicitly (rawBody) to preserve evidence inner keys.
]);
function copyShallow(value) {
  if (value === null || value === undefined || typeof value !== "object") return value;
  if (Array.isArray(value)) return value.slice();
  return { ...value };
}
export function toCamelCase(obj) {
  if (obj === null || obj === undefined || typeof obj !== "object") return obj;
  if (Array.isArray(obj)) return obj.map(toCamelCase);
  const out = {};
  for (const [k, v] of Object.entries(obj)) {
    const renamed = TO_CAMEL[k] || k;
    out[renamed] = OPAQUE_VALUE_KEYS.has(k) || OPAQUE_VALUE_KEYS.has(renamed) ? copyShallow(v) : toCamelCase(v);
  }
  return out;
}
export function toSnakeCase(obj) {
  if (obj === null || obj === undefined || typeof obj !== "object") return obj;
  if (Array.isArray(obj)) return obj.map(toSnakeCase);
  const out = {};
  for (const [k, v] of Object.entries(obj)) {
    const renamed = TO_SNAKE[k] || k;
    // Symmetric to toCamelCase: free-form user-defined maps (metadata,
    // schemaBody) must reach the caller verbatim. Without this guard,
    // response normalization would rewrite an inner key like `assetType`
    // (a project-defined metadata field) into `asset_type`, mutating the
    // persisted contract round-trip. See codex over-cap finding 5 on #722.
    out[renamed] = OPAQUE_VALUE_KEYS.has(k) || OPAQUE_VALUE_KEYS.has(renamed) ? copyShallow(v) : toSnakeCase(v);
  }
  return out;
}
export function validateDevStartPlanGate(planBody, gateConfig) {
  const normalized = normalizeDevStartGateConfig(gateConfig);
  if (!normalized.ok) return devStartGateConfigFailure(normalized.errors);
  const config = normalized.value;
  if (config.enabled !== true) return { ok: true, checked: false, source_bearing: null };

  const planFields = readDevStartPlanFields(planBody, config);
  if (!planFields.ok) return planFields;

  const sourceDecision = readSourceBearingDecision(planFields.fields);
  if (!sourceDecision.ok) {
    return devStartGateFailure({
      planSection: config.plan_section,
      missing: sourceDecision.missing,
      invalid: sourceDecision.invalid,
    });
  }
  if (!sourceDecision.sourceBearing) return validateNonSourceDevStartGate(planFields.fields, config);
  return validateSourceDevStartGate(planFields.fields, config);
}
export async function readPriorCodexReviewCycleCount(repoRoot, owner, name, prNumber) {
  const bodies = await readIssueCommentBodies(repoRoot, owner, name, prNumber);
  return parseCodexReviewCycleMarkers(bodies, prNumber);
}
export async function postCodexReviewCycleMarker(repoRoot, owner, name, prNumber, cycleNumber, extras = {}) {
  const body = buildCodexReviewCycleMarker({
    prNumber,
    cycleNumber,
    override: extras.override === true,
    overrideReason: extras.overrideReason ?? null,
  });
  await execFile(
    "gh",
    [
      "api",
      "--method",
      "POST",
      `/repos/${owner}/${name}/issues/${prNumber}/comments`,
      "-f",
      `body=${body}`,
    ],
    { cwd: repoRoot },
  );
}
async function findPrForIssue(repoRoot, owner, name, issueNumber) {
  // Look up linked PRs via GraphQL — REST's /issues endpoint doesn't expose
  // the issue→PR link directly. We restrict to merged or open PRs since a
  // closed-but-not-merged PR can't satisfy the gate.
  const query = `
    query($owner: String!, $name: String!, $number: Int!) {
      repository(owner: $owner, name: $name) {
        issue(number: $number) {
          timelineItems(first: 50, itemTypes: [CROSS_REFERENCED_EVENT, CONNECTED_EVENT, MARKED_AS_DUPLICATE_EVENT]) {
            nodes {
              __typename
              ... on CrossReferencedEvent {
                source { __typename ... on PullRequest { number state mergedAt url } }
              }
              ... on ConnectedEvent {
                subject { __typename ... on PullRequest { number state mergedAt url } }
              }
            }
          }
        }
      }
    }
  `;
  let stdout;
  try {
    ({ stdout } = await execFile(
      "gh",
      [
        "api", "graphql",
        "-f", `query=${query}`,
        "-F", `owner=${owner}`,
        "-F", `name=${name}`,
        "-F", `number=${issueNumber}`,
      ],
      { cwd: repoRoot },
    ));
  } catch (error) {
    throw new Error(`gh api graphql failed: ${extractGhErrorMessage(error)}`);
  }
  let payload;
  try {
    payload = JSON.parse(stdout);
  } catch {
    return [];
  }
  const nodes = payload?.data?.repository?.issue?.timelineItems?.nodes ?? [];
  const prs = new Map();
  for (const node of nodes) {
    let pr = null;
    if (node?.__typename === "CrossReferencedEvent" && node?.source?.__typename === "PullRequest") pr = node.source;
    if (node?.__typename === "ConnectedEvent" && node?.subject?.__typename === "PullRequest") pr = node.subject;
    if (!pr || typeof pr.number !== "number") continue;
    if (!prs.has(pr.number)) prs.set(pr.number, pr);
  }
  return [...prs.values()];
}
export async function resolvePrForClose({ repoRoot, owner, name, issueNumber, prNumber }) {
  if (prNumber == null) {
    let prs;
    try {
      prs = await findPrForIssue(repoRoot, owner, name, issueNumber);
    } catch (error) {
      return {
        earlyReturn: {
          ok: false,
          error: "close_pr_lookup_failed",
          message: error.message,
          issue_number: issueNumber,
        },
      };
    }
    const merged = prs.find((p) => p.state === "MERGED" && p.mergedAt);
    if (merged) return { pr: merged };
    if (prs.length === 0) {
      return {
        earlyReturn: {
          ok: false,
          error: "close_no_linked_pr",
          message: `gc_close_issue_after_merge could not find any PR linked to issue #${issueNumber}; expected a merged PR with the issue cross-referenced.`,
          issue_number: issueNumber,
          next_action: "open_or_link_a_pr_first",
        },
      };
    }
    return { pr: prs[0] };
  }

  // Codex review cycle 1 (issue #1058): a caller-supplied pr_number must
  // be verified as linked to the issue before being used as the close
  // gate. Trusting the caller's pr_number for the issue→PR relationship
  // would defeat the gate's purpose: a stale cached PR number or a
  // malicious caller could pass any merged PR from this repo paired with
  // an unrelated open issue and cause the wrong issue to close. Resolve
  // the issue's actual linked PRs from the timeline and require the
  // supplied PR number to appear in that set; only then proceed to
  // fetch the PR's merged_at / state for the merge-status gate.
  let linkedPrs;
  try {
    linkedPrs = await findPrForIssue(repoRoot, owner, name, issueNumber);
  } catch (error) {
    return {
      earlyReturn: {
        ok: false,
        error: "close_pr_lookup_failed",
        message: error.message,
        issue_number: issueNumber,
        pr_number: prNumber,
      },
    };
  }
  const matched = linkedPrs.find((p) => p.number === prNumber);
  if (!matched) {
    return {
      earlyReturn: {
        ok: false,
        error: "close_pr_not_linked_to_issue",
        message:
          `gc_close_issue_after_merge refuses to close issue #${issueNumber} via PR #${prNumber}: ` +
          `that PR is not in the issue's linked-PR set ${JSON.stringify(linkedPrs.map((p) => p.number))}. ` +
          `Supplied pr_number must reference a PR that the issue's GitHub timeline already links to; ` +
          `pass no pr_number to let the tool resolve the linked PR from the issue timeline instead.`,
        issue_number: issueNumber,
        pr_number: prNumber,
        linked_pr_numbers: linkedPrs.map((p) => p.number),
        next_action: "omit_pr_number_or_pass_a_linked_pr",
      },
    };
  }
  return { pr: matched };
}
export async function runCloseIssueAfterMerge({ repoPath, issueNumber, prNumber = null }) {
  if (issueNumber == null || !Number.isInteger(issueNumber) || issueNumber <= 0) {
    throw new Error("gc_close_issue_after_merge requires a positive integer issue_number");
  }
  if (prNumber != null && (!Number.isInteger(prNumber) || prNumber <= 0)) {
    throw new Error("gc_close_issue_after_merge pr_number must be a positive integer when supplied");
  }

  const repoRoot = await ensureGitRepo(repoPath);
  const { owner, name } = await getOwnerRepo(repoRoot);

  const resolved = await resolvePrForClose({ repoRoot, owner, name, issueNumber, prNumber });
  if (resolved.earlyReturn) return resolved.earlyReturn;
  const pr = resolved.pr;

  if (!pr?.mergedAt || pr.state !== "MERGED") {
    return {
      ok: false,
      error: "close_pr_not_merged",
      message:
        `gc_close_issue_after_merge refuses to close issue #${issueNumber}: ` +
        `linked PR #${pr?.number ?? "?"} state=${pr?.state ?? "unknown"}, merged_at=${pr?.mergedAt ?? "null"}. ` +
        `The post-merge close gate requires merged_at non-null AND state='MERGED'.`,
      issue_number: issueNumber,
      pr_state: pr?.state ?? null,
      pr_merged_at: pr?.mergedAt ?? null,
      next_action: "wait_for_user_to_merge_the_pr",
    };
  }

  return closeIssueIdempotently({ repoRoot, owner, name, issueNumber, pr });
}
async function closeIssueIdempotently({ repoRoot, owner, name, issueNumber, pr }) {
  // Check current issue state to support idempotent re-runs.
  let issueState;
  try {
    const { stdout } = await execFile(
      "gh",
      ["api", `/repos/${owner}/${name}/issues/${issueNumber}`],
      { cwd: repoRoot },
    );
    const parsed = JSON.parse(stdout);
    issueState = typeof parsed.state === "string" ? parsed.state : null;
  } catch (error) {
    return {
      ok: false,
      error: "close_issue_lookup_failed",
      message: `gc_close_issue_after_merge could not fetch issue #${issueNumber}: ${extractGhErrorMessage(error)}`,
      issue_number: issueNumber,
    };
  }

  if (issueState === "closed") {
    return {
      ok: true,
      already_closed: true,
      issue_number: issueNumber,
      pr_number: pr.number,
      pr_merged_at: pr.mergedAt,
      next_action: "no_op",
    };
  }

  try {
    await execFile(
      "gh",
      [
        "api", "--method", "PATCH",
        `/repos/${owner}/${name}/issues/${issueNumber}`,
        "-f", "state=closed",
        "-f", "state_reason=completed",
      ],
      { cwd: repoRoot },
    );
  } catch (error) {
    return {
      ok: false,
      error: "close_issue_patch_failed",
      message: `gc_close_issue_after_merge could not close issue #${issueNumber}: ${extractGhErrorMessage(error)}`,
      issue_number: issueNumber,
      pr_number: pr.number,
    };
  }

  return {
    ok: true,
    already_closed: false,
    issue_number: issueNumber,
    pr_number: pr.number,
    pr_merged_at: pr.mergedAt,
    pr_url: pr.url,
  };
}
export const REVIEW_JOB_TTL_MS = 30 * 60 * 1000;
const _reviewJobs = new Map();
let _reviewJobSeq = 0;
function _reapExpiredReviewJobs() {
  const now = Date.now();
  for (const [id, job] of _reviewJobs) {
    if (job.finishedAt != null && now - job.finishedAt > REVIEW_JOB_TTL_MS) {
      _reviewJobs.delete(id);
    }
  }
}
export function startReviewJob(kind, runFn) {
  if (typeof runFn !== "function") {
    throw new Error("startReviewJob: runFn must be a function");
  }
  _reapExpiredReviewJobs();
  _reviewJobSeq += 1;
  const id = `rjob-${Date.now().toString(36)}-${_reviewJobSeq}`;
  const controller = new AbortController();
  const job = {
    id,
    kind: typeof kind === "string" && kind.length > 0 ? kind : "review",
    status: "running",
    startedAt: Date.now(),
    finishedAt: null,
    result: null,
    error: null,
    controller,
  };
  _reviewJobs.set(id, job);
  Promise.resolve()
    .then(() => runFn(controller.signal))
    .then((result) => {
      job.result = result;
      job.status = "done";
    })
    .catch((e) => {
      job.error = e;
      job.status = controller.signal.aborted ? "cancelled" : "failed";
    })
    .finally(() => {
      job.finishedAt = Date.now();
    });
  return { ok: true, status: "running", job_id: id, kind: job.kind };
}
export function pollReviewJob(jobId) {
  _reapExpiredReviewJobs();
  const job = _reviewJobs.get(jobId);
  if (!job) {
    return {
      ok: false,
      error: "job_not_found",
      message:
        `No review job '${jobId}'. It may have finished and expired (terminal jobs are ` +
        `reaped ${REVIEW_JOB_TTL_MS} ms after completion), or the MCP server restarted. ` +
        `Re-run the review with async=true to start a fresh job.`,
    };
  }
  const base = {
    job_id: job.id,
    kind: job.kind,
    elapsed_ms: (job.finishedAt ?? Date.now()) - job.startedAt,
  };
  if (job.status === "running") {
    return { ok: true, status: "running", ...base };
  }
  if (job.status === "done") {
    return { ok: true, status: "done", ...base, result: job.result };
  }
  if (job.status === "cancelled") {
    return {
      ok: false,
      status: "cancelled",
      error: "job_cancelled",
      message: "Review job was cancelled via gc_codex_job before it completed.",
      ...base,
    };
  }
  return {
    ok: false,
    status: "failed",
    error: "job_failed",
    message: String(job.error?.message ?? job.error ?? "review job failed"),
    ...base,
  };
}
export function cancelReviewJob(jobId) {
  const job = _reviewJobs.get(jobId);
  if (!job) {
    return {
      ok: false,
      error: "job_not_found",
      message: `No review job '${jobId}' to cancel.`,
    };
  }
  if (job.status !== "running") {
    return {
      ok: true,
      status: job.status,
      job_id: job.id,
      kind: job.kind,
      message: `Review job '${jobId}' is already terminal (${job.status}); nothing to cancel.`,
    };
  }
  job.controller.abort();
  return {
    ok: true,
    status: "cancelling",
    job_id: job.id,
    kind: job.kind,
    message:
      "Abort signalled; the codex/claude child is being terminated. Poll once more to confirm the cancelled state.",
  };
}
export function _resetReviewJobsForTest() {
  _reviewJobs.clear();
  _reviewJobSeq = 0;
}

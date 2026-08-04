// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { collectPrBodyErrors, renderPrBodyLines } from "./pr-body-render.js";
import { detectSensitiveBodyContent } from "./grc-legacy-compat-2.js";
import { ensureGitRepo } from "./grc-legacy-compat-4.js";
import { getRepoGroundControlContext } from "./repo-vocabulary-2.js";
import { PR_BODY_MAX } from "./repo-vocabulary.js";
import { buildStepObservationEvent } from "./step-telemetry.js";
import { getOwnerRepo } from "./grc-legacy-compat-3.js";
import { createWorkflowRun, recordWorkflowRunEvent } from "./api-workflow-run.js";
import { checkPrBodyShape } from "./runtime-primitives.js";

export const MAPPING_CONTROL_ROLES = [
  "PREVENTIVE", "DETECTIVE", "CORRECTIVE", "DETERRENT",
  "COMPENSATING", "RECOVERY", "DIRECTIVE",
];
const TRACEABILITY_TESTABLE_SURFACE_PREFIXES = [
  "backend/src/main/",
  "frontend/src/",
  "mcp/",
  "tools/policy/",
];
export function hasTestableSurfaceTarget(linksOfTypeImplements) {
  if (!Array.isArray(linksOfTypeImplements) || linksOfTypeImplements.length === 0) return false;
  for (const link of linksOfTypeImplements) {
    if (!link || typeof link !== "object") continue;
    const id = typeof link.artifact_identifier === "string" ? link.artifact_identifier : "";
    if (!id) continue;
    for (const prefix of TRACEABILITY_TESTABLE_SURFACE_PREFIXES) {
      if (id.startsWith(prefix)) return true;
    }
  }
  return false;
}
export function validatePrBodyInput(input) {
  if (input == null || typeof input !== "object") {
    return { ok: false, errors: ["input must be an object"] };
  }
  const errors = collectPrBodyErrors(input);
  return errors.length ? { ok: false, errors } : { ok: true };
}
export function buildPrBody(input) {
  const validation = validatePrBodyInput(input);
  if (!validation.ok) {
    throw new Error(`buildPrBody input invalid: ${validation.errors.join("; ")}`);
  }
  return renderPrBodyLines(input).join("\n");
}
export async function runRenderPrBody(input) {
  const validation = validatePrBodyInput(input);
  if (!validation.ok) {
    return {
      ok: false,
      error: "pr_body_input_invalid",
      message: validation.errors.join("; "),
      issue_number: input?.issueNumber ?? null,
    };
  }
  // NB: JS-side deferral detection is intentionally NOT applied here (codex
  // cycle-4 F1). The previous Tier-1 regex was a partial subset of the
  // canonical classifier in `tools/policy/checks.py::run_no_deferral_disposition_check`
  // (which itself loads cases from `tools/policy/deferral_cases.json`).
  // A partial JS detector gives false confidence: a caller-supplied string
  // could pass the JS check and then fail `make policy`/CI. Authoritative
  // enforcement lives in two places that DO catch the rendered body:
  //   (a) the `block-defer-language.py` PreToolUse hook, which fires on
  //       `gh pr {create,edit,comment}` invocations carrying deferral text
  //       in body or title;
  //   (b) `bin/policy` (`tools/policy/checks.py::check_pr_body` →
  //       `run_no_deferral_disposition_check`) at completion-gate / CI time.
  // The downstream MCP record posters (runPostDecisionRecord,
  // runPostFinalReport) keep a Tier-1 check because they call `gh api` rather
  // than `gh pr create`, and the PreToolUse hook only fires on the latter.
  const body = buildPrBody(input);
  // Enforce the GitHub PR-body cap at the renderer boundary (issue #1199) so a
  // render success can never produce an artifact gc_create_synchronized_implement_pr
  // must reject at its own 65,535-byte limit.
  if (Buffer.byteLength(body, "utf8") > PR_BODY_MAX) {
    return {
      ok: false,
      error: "pr_body_too_large",
      message: `rendered PR body is ${Buffer.byteLength(body, "utf8")} bytes; GitHub's PR-body cap is ${PR_BODY_MAX} bytes. Trim summary, changes, or test_notes.`,
      issue_number: input.issueNumber,
      next_action: "trim_inputs_and_retry",
    };
  }
  const sensitiveError = detectSensitiveBodyContent(body);
  if (sensitiveError) {
    return {
      ok: false,
      error: "pr_body_rejected",
      message: sensitiveError,
      issue_number: input.issueNumber,
      next_action: "scrub_secrets_from_inputs_and_retry",
    };
  }
  // Final structural check — mirrors the Python check_pr_body predicates so the
  // tool's contract holds at the boundary, not in agent prose. If the
  // renderer drifts from the policy or a caller-provided field smuggles
  // deferral language past the per-field check, this catches it before the
  // body is handed back for `gh pr create --body`. The Python policy at
  // `bin/policy` remains the canonical check at CI time; this is defense in
  // depth.
  const shape = checkPrBodyShape(body);
  if (!shape.ok) {
    return {
      ok: false,
      error: "pr_body_policy_violation",
      message: shape.errors.join("; "),
      issue_number: input.issueNumber,
      next_action: "fix_inputs_or_renderer_and_retry",
    };
  }
  return {
    ok: true,
    issue_number: input.issueNumber,
    change_class: input.changeClass,
    body,
    byte_length: Buffer.byteLength(body, "utf8"),
  };
}
export async function runLogStepTelemetry(
  {
    repoPath,
    issueNumber,
    branch,
    stage,
    step = null,
    tier,
    model,
    wallTimeMs,
    inputTokens = null,
    outputTokens = null,
    outcome,
    attempt,
    ts = null,
  },
  deps = {},
) {
  const createRun = deps.createRun ?? createWorkflowRun;
  const recordEvent = deps.recordEvent ?? recordWorkflowRunEvent;
  const resolveContext = deps.getContext ?? getRepoGroundControlContext;
  const resolveOwnerRepo = deps.getOwnerRepo ?? getOwnerRepo;

  // Build the durable event first (ADR-090 amendment, issue #1354). This is the successor to the
  // forward JSONL record: the same step/tier/model/wall-time/outcome, mapped onto the ADR-061
  // phase-event write path instead of a gitignored file. Existing local JSONL files stay historical;
  // nothing is written to disk from here anymore. An invalid record is a caller bug, so it is
  // surfaced structurally rather than swallowed by the fail-open backend path below.
  let event;
  try {
    event = buildStepObservationEvent({
      stage,
      step,
      tier,
      model,
      wallTimeMs,
      inputTokens,
      outputTokens,
      outcome,
      attempt,
      ts,
    });
  } catch (error) {
    return { ok: false, error: "telemetry_input_invalid", message: error.message, issue_number: issueNumber ?? null };
  }
  if (!Number.isInteger(issueNumber) || issueNumber <= 0) {
    return { ok: false, error: "telemetry_input_invalid", message: "issueNumber must be positive integer", issue_number: null };
  }
  if (typeof branch !== "string" || branch.trim() === "") {
    return { ok: false, error: "telemetry_input_invalid", message: "branch must be non-empty string", issue_number: issueNumber };
  }

  let repoRoot;
  try {
    repoRoot = await ensureGitRepo(repoPath);
  } catch (error) {
    return { ok: false, error: "telemetry_repo_not_git", message: error.message, issue_number: issueNumber };
  }

  // Tool-boundary opt-in gate (ADR-036): resolve the canonical repository context and refuse unless
  // the repo turned telemetry on. Reusing gc_get_repo_ground_control_context is the one context path
  // (ADR-027) and also yields the authoritative project id the run correlation needs.
  const ctx = await resolveContext(repoRoot);
  if (!ctx || ctx.status !== "ok") {
    return {
      ok: false,
      error: "telemetry_config_invalid",
      message: ctx && ctx.status ? `repository context status: ${ctx.status}` : "could not resolve repository context",
      issue_number: issueNumber,
    };
  }
  if (!ctx.telemetry || ctx.telemetry.enabled !== true) {
    return {
      ok: false,
      error: "telemetry_disabled",
      message: "telemetry.enabled is false (or absent) in .ground-control.yaml; flip it to true to opt this repo into durable per-step telemetry",
      issue_number: issueNumber,
      next_action: "set_telemetry_enabled_true_or_omit_call",
    };
  }
  const project = ctx.project;

  // The durable write is strictly fail-open (ADR-090 amendment): the workflow result is already
  // determined, so a backend outage, an unresolved repository identity, or a rejected write returns a
  // bounded diagnostic carrying safe identifiers and a stable failure class only, and never blocks or
  // alters the step. There is no local JSONL fallback — a durable record is guaranteed only when the
  // authenticated backend is reachable and telemetry is enabled.
  try {
    // Origin-derived, fail-closed repository identity — never the GH_REPO-sensitive fallback for this
    // identity-bearing run key.
    const repo = await resolveOwnerRepo(repoRoot);
    // Upsert the IMPLEMENT run by the RAW (project, repo, issue, branch) natural key. No final_state
    // is sent, so an open observation can never overwrite a terminal one the merge protects.
    const run = await createRun(
      { repo, issue_number: issueNumber, branch, workflow_type: "IMPLEMENT", provenance: "LIVE_EMISSION" },
      project,
    );
    const runId = run?.id ?? null;
    if (!runId) {
      return { ok: false, error: "telemetry_run_upsert_no_id", issue_number: issueNumber };
    }
    const recorded = await recordEvent(runId, event, project);
    return {
      ok: true,
      run_id: runId,
      event_id: recorded?.id ?? null,
      phase: event.phase,
      station_id: recorded?.station_id ?? null,
      emitter: event.emitter,
    };
  } catch (error) {
    const code = error?.code ?? error?.name;
    return {
      ok: false,
      error: "telemetry_durable_write_failed",
      failure_class: typeof code === "string" && /^[A-Za-z0-9_.-]{1,60}$/.test(code) ? code : "unknown",
      issue_number: issueNumber,
    };
  }
}

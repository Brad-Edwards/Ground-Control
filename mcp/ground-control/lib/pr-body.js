// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { join } from "node:path";
import { renderDocumentationSection, validateDocumentationOutcome } from "./doc-coverage.js";
import { detectSensitiveBodyContent } from "./grc-legacy-compat-2.js";
import { ensureGitRepo } from "./grc-legacy-compat-4.js";
import { devStartFieldValue, extractMarkdownHeadingSection, parseDevStartGateFields } from "./grc-legacy-compat.js";
import { DEFAULT_DEV_START_GATE_PLAN_SECTION } from "./repo-context.js";
import { getRepoGroundControlContext } from "./repo-vocabulary-2.js";
import { PR_BODY_SUMMARY_MAX } from "./repo-vocabulary.js";
import { buildStepObservationEvent } from "./step-telemetry.js";
import { getOwnerRepo } from "./grc-legacy-compat-3.js";
import { createWorkflowRun, recordWorkflowRunEvent } from "./api-workflow-run.js";
import { EXACT_REQUIREMENT_UID_RE, PR_BODY_CHANGE_CLASSES, PR_BODY_GC_CHECK_LINES, REQUIREMENT_UID_CONTRACT_DESCRIPTION, checkPrBodyShape, execFile } from "./runtime-primitives.js";

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
  const errors = [];
  if (input == null || typeof input !== "object") {
    return { ok: false, errors: ["input must be an object"] };
  }
  const { issueNumber, changeClass, requirementUids, adrRefs, summary, changes, traceability, changelogFragment, testNotes } = input;
  const changelogMode = input.changelogMode == null ? "fragments" : input.changelogMode;
  if (changelogMode !== "fragments" && changelogMode !== "release-please") {
    errors.push('changelogMode must be "fragments" or "release-please" when set');
  }
  if (!Number.isInteger(issueNumber) || issueNumber <= 0) {
    errors.push("issueNumber must be a positive integer");
  }
  if (!PR_BODY_CHANGE_CLASSES.includes(changeClass)) {
    errors.push(`changeClass must be one of: ${PR_BODY_CHANGE_CLASSES.join(", ")}`);
  }
  if (!Array.isArray(requirementUids)) {
    errors.push("requirementUids must be an array (may be empty for requirement-free runs)");
  } else {
    requirementUids.forEach((u, i) => {
      // The renderer takes the same identity corpus as every other structured
      // field. The section-scoped policy gate accepts exactly this corpus, so a
      // UID that reconciles and reports can always be rendered too (issue
      // #1425). The entire input must BE one UID, not merely contain one.
      if (typeof u !== "string" || !EXACT_REQUIREMENT_UID_RE.test(u)) {
        errors.push(`requirementUids[${i}] must be ${REQUIREMENT_UID_CONTRACT_DESCRIPTION}`);
      }
    });
  }
  if (!Array.isArray(adrRefs)) {
    errors.push("adrRefs must be an array (may be empty; renderer emits 'No ADR required' when empty)");
  } else {
    adrRefs.forEach((a, i) => {
      if (typeof a !== "string" || a.trim() === "") errors.push(`adrRefs[${i}] must be a non-empty string`);
    });
  }
  if (typeof summary !== "string" || summary.trim() === "") {
    errors.push("summary must be a non-empty string");
  } else if (Buffer.byteLength(summary, "utf8") > PR_BODY_SUMMARY_MAX) {
    errors.push(
      `summary exceeds the PR-body summary cap of ${PR_BODY_SUMMARY_MAX} bytes (got ${Buffer.byteLength(summary, "utf8")}). A PR-body summary is one tight paragraph — restated context and hedging are the usual offenders.`,
    );
  }
  if (!Array.isArray(changes)) {
    errors.push("changes must be an array of bullet strings");
  } else {
    changes.forEach((c, i) => {
      if (typeof c !== "string" || c.trim() === "") errors.push(`changes[${i}] must be a non-empty string`);
    });
  }
  if (traceability == null || typeof traceability !== "object" || Array.isArray(traceability)) {
    errors.push("traceability must be a mapping with 'implements' and 'tests' arrays");
  } else {
    for (const k of ["implements", "tests"]) {
      if (!Array.isArray(traceability[k])) {
        errors.push(`traceability.${k} must be an array (may be empty)`);
      }
    }
  }
  // Validate `changelogFragment` against the towncrier-style fragment path
  // shape: `changelog.d/<issue>.<type>.md` OR `changelog.d/+<slug>.<type>.md`
  // where <type> ∈ {security, added, changed, deprecated, removed, fixed}.
  // Mirrors tools/policy/checks.py::run_changelog_fragment_check's filename
  // predicate so a body that claims "Changelog fragment added at <path>"
  // can't get rendered with a non-fragment path (codex cycle-4 F4).
  if (changelogMode === "release-please") {
    // Release Please repos (#1399 / #1336, GC-P027): Release Please owns
    // CHANGELOG.md, generated from the Conventional Commit PR title, so there is
    // no per-PR changelog.d fragment. Reject a stray fragment and never require one.
    if (changelogFragment != null) {
      errors.push(
        "changelogFragment is not accepted when changelogMode is 'release-please' (Release Please owns CHANGELOG.md; there is no changelog.d fragment)",
      );
    }
  } else {
    // Towncrier fragment mode (default). Mirrors the historical filename predicate
    // so a body claiming "Changelog fragment added at <path>" cannot render a
    // non-fragment path.
    if (changelogFragment != null) {
      if (typeof changelogFragment !== "string" || changelogFragment.trim() === "") {
        errors.push("changelogFragment must be a non-empty string when set");
      } else if (!/^changelog\.d\/(?:[A-Za-z0-9._-]+|\+[A-Za-z0-9._-]+)\.(?:security|added|changed|deprecated|removed|fixed)\.md$/.test(changelogFragment)) {
        errors.push(`changelogFragment must match changelog.d/<issue>.<type>.md or changelog.d/+<slug>.<type>.md where <type> ∈ {security,added,changed,deprecated,removed,fixed}; got: ${changelogFragment}`);
      }
    }
    if (changeClass === "source" || changeClass === "source+migration") {
      if (changelogFragment == null) {
        errors.push(`changeClass='${changeClass}' requires a changelogFragment (path under changelog.d/)`);
      }
    }
  }
  if (testNotes != null && typeof testNotes !== "string") {
    errors.push("testNotes must be a string when set");
  }
  if (input.devStartGate != null) {
    if (typeof input.devStartGate !== "string" || input.devStartGate.trim() === "") {
      errors.push("devStartGate must be a non-empty Markdown string when set");
    } else {
      const section = extractMarkdownHeadingSection(input.devStartGate, DEFAULT_DEV_START_GATE_PLAN_SECTION);
      if (section == null) {
        errors.push(`devStartGate must include a ## ${DEFAULT_DEV_START_GATE_PLAN_SECTION} section`);
      } else if (devStartFieldValue(parseDevStartGateFields(section), "Source-bearing") == null) {
        errors.push("devStartGate must include a Source-bearing field");
      }
    }
  }
  // Optional documentation_outcome field (issue #896, ADR-054).
  if (input.documentation_outcome != null) {
    const docResult = validateDocumentationOutcome(input.documentation_outcome);
    if (!docResult.ok) {
      for (const e of docResult.errors) errors.push(`documentation_outcome: ${e}`);
    }
  }
  if (errors.length) return { ok: false, errors };
  return { ok: true };
}
export function buildPrBody(input) {
  const validation = validatePrBodyInput(input);
  if (!validation.ok) {
    throw new Error(`buildPrBody input invalid: ${validation.errors.join("; ")}`);
  }
  const { issueNumber, changeClass, requirementUids, adrRefs, summary, changes, traceability, changelogFragment, testNotes, devStartGate } = input;
  const changelogMode = input.changelogMode == null ? "fragments" : input.changelogMode;
  const lines = [];
  lines.push("## Summary");
  lines.push("");
  lines.push(summary.trim());
  lines.push("");
  lines.push("## Requirement UIDs");
  lines.push("");
  if (requirementUids.length === 0) {
    // Requirement-free runs (bug/refactor/maintenance) render an explicit
    // "(none)" marker rather than a synthetic UID placeholder. Codex cycle-2
    // flagged the previous placeholder injection as fabricated traceability —
    // a placeholder `GC-O007` would have tied an unrelated bug-fix PR to the
    // workflow requirement in the durable record. The PR-body policy gate
    // reads this section structurally, so the marker satisfies it on its own
    // (issue #1425) — a requirement-free change no longer needs an incidental
    // `ADR-NNN` token elsewhere in the body to pass a requirement check. ADR
    // impact remains gated separately by the ADR Impact predicate.
    lines.push("- (none — bug/refactor/maintenance run; see Traceability section below)");
  } else {
    for (const u of requirementUids) lines.push(`- \`${u}\``);
  }
  lines.push("");
  lines.push("## Related Issues");
  lines.push("");
  lines.push(`Closes #${issueNumber}`);
  lines.push("");
  lines.push("## ADR Impact");
  lines.push("");
  if (adrRefs.length === 0) {
    lines.push("- No ADR required");
  } else {
    for (const a of adrRefs) lines.push(`- ${a}`);
  }
  lines.push("");
  lines.push("## Changes");
  lines.push("");
  if (changes.length === 0) {
    lines.push("- See summary above.");
  } else {
    for (const c of changes) lines.push(`- ${c}`);
  }
  if (changeClass === "source+migration") {
    lines.push("- **Migration reminder:** update version lists in `MigrationSmokeTest.java` and `RequirementsE2EIntegrationTest.java` (per `.gc/plan-rules.md`).");
  }
  if (devStartGate != null && devStartGate.trim() !== "") {
    lines.push("");
    for (const l of devStartGate.trim().split(/\r?\n/)) lines.push(l);
  }
  lines.push("");
  lines.push("## Test Plan");
  lines.push("");
  // Named semantically for the same reason as the policy line: the completion
  // and policy commands are repo configuration (`workflow.completion_command`,
  // `workflow.policy_command`), so a rendered Make target would be a false
  // claim in any consuming repo that runs something else (issue #1429).
  if (changeClass === "doc-only") {
    lines.push("- [x] Configured completion command passes");
    lines.push("- [x] Configured repository policy command passes (documentation/workflow guardrails)");
    lines.push("- Unit tests / integration tests: N/A — docs-only change");
  } else {
    lines.push("- [x] Unit tests pass");
    lines.push("- [x] Integration tests pass if applicable");
    lines.push("- [x] Configured completion command passes");
    lines.push("- [x] No coverage regression");
  }
  if (testNotes && testNotes.trim() !== "") {
    lines.push("");
    lines.push(testNotes.trim());
  }
  lines.push("");
  lines.push("## Ground Control Checks");
  lines.push("");
  for (const l of PR_BODY_GC_CHECK_LINES) lines.push(l);
  lines.push("");
  lines.push("## Traceability");
  lines.push("");
  const tImpl = Array.isArray(traceability.implements) ? traceability.implements : [];
  const tTest = Array.isArray(traceability.tests) ? traceability.tests : [];
  if (tImpl.length === 0) {
    lines.push("- IMPLEMENTS: (none — bug/refactor/maintenance run)");
  } else {
    lines.push(`- IMPLEMENTS: ${tImpl.join(", ")}`);
  }
  if (tTest.length === 0) {
    lines.push("- TESTS: (none — documentation/configuration/structural-invariant run)");
  } else {
    lines.push(`- TESTS: ${tTest.join(", ")}`);
  }
  lines.push("");
  lines.push("## Checklist");
  lines.push("");
  lines.push("- [x] Code follows project coding standards (`docs/CODING_STANDARDS.md`)");
  lines.push("- [x] No business logic in API layer");
  lines.push("- [x] Domain layer has no framework imports");
  lines.push("- [x] Envers `@Audited` on new entities if applicable");
  if (changelogMode === "release-please") {
    lines.push("- [x] Changelog: owned by Release Please (generated from the Conventional Commit PR title; no per-PR fragment)");
  } else if (changeClass === "doc-only") {
    lines.push("- Changelog fragment: N/A — docs-only change");
  } else {
    lines.push(`- [x] Changelog fragment added at \`${changelogFragment}\``);
  }
  lines.push("- [x] Architectural docs updated if stack, package structure, or key behaviors changed");
  // Optional documentation outcome section (issue #896, ADR-054).
  if (input.documentation_outcome != null) {
    lines.push("");
    for (const l of renderDocumentationSection(input.documentation_outcome)) lines.push(l);
  }
  return lines.join("\n");
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

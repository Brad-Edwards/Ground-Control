// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { realpathSync } from "node:fs";
import { dirname, relative, resolve as resolvePath } from "node:path";
import { isPathStrictlyInside } from "./api-requirements.js";
import { DEV_START_GATE_REQUIRED_FOR, emptyDevStartGateConfig, emptyWorkflowConfig, isSafeGitRefName, normalizeIntegrationManagerConfig, normalizePrTitleConfig, normalizeReviewDispositionConfig, normalizeReviewerConfig } from "./repo-context.js";
import { CLAUDE_MODEL_BY_TIER, ROUTING_PROVIDERS, ROUTING_STAGE_NAME_RE, ROUTING_TIERS } from "./repo-vocabulary.js";
import { EXACT_REQUIREMENT_UID_RE, REQUIREMENT_UID_CONTRACT_DESCRIPTION } from "./runtime-primitives.js";

export function assertRealpathInRepo(repoRootReal, targetAbs, fieldName) {
  let cursor = targetAbs;
  let canonical = null;
  for (;;) {
    try {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- cursor originates from an already-validated repo-relative path
      canonical = realpathSync(cursor);
      break;
    } catch (error) {
      // ENOENT means the path itself (or the ancestor we're currently at)
      // does not exist yet — walk up one level and keep looking.
      // ENOTDIR means we descended *through* a regular file, e.g.
      // `docs/knowledge/SCHEMA.md/capture`. The offending component is still
      // somewhere above `cursor`; walk up the same way so the helper always
      // returns a structured validation error instead of letting the
      // exception escape and hard-fail the whole tool call.
      if (error.code !== "ENOENT" && error.code !== "ENOTDIR") throw error;
      const parent = dirname(cursor);
      if (parent === cursor) {
        return {
          ok: false,
          error: `${fieldName} could not be canonicalized — no valid ancestor of '${targetAbs}' (${error.code})`,
        };
      }
      cursor = parent;
    }
  }

  // If we walked up the tree, append the unresolved tail back so the
  // "resolved" path reflects what the caller will actually use. The tail
  // cannot re-introduce symlink escapes because it does not yet exist.
  const tail = relative(cursor, targetAbs);
  const effective = tail === "" ? canonical : resolvePath(canonical, tail);
  if (!isPathStrictlyInside(repoRootReal, effective)) {
    return {
      ok: false,
      error: `${fieldName} resolves outside the repository root via a symlink (canonical path '${effective}')`,
    };
  }
  return { ok: true, canonical: effective };
}
function validateStringList(raw, fieldName, { uid = false } = {}) {
  const errors = [];
  if (!Array.isArray(raw)) {
    return { ok: false, errors: [`${fieldName} must be a list when set`] };
  }
  const seen = new Set();
  const value = [];
  raw.forEach((entry, i) => {
    if (typeof entry !== "string" || entry.trim() === "") {
      errors.push(`${fieldName}[${i}] must be a non-empty string`);
      return;
    }
    const trimmed = entry.trim();
    if (trimmed.includes("\n") || trimmed.includes("\r")) {
      errors.push(`${fieldName}[${i}] must be a single-line string`);
      return;
    }
    if (uid && !EXACT_REQUIREMENT_UID_RE.test(trimmed)) {
      errors.push(`${fieldName}[${i}] must be ${REQUIREMENT_UID_CONTRACT_DESCRIPTION}`);
      return;
    }
    if (seen.has(trimmed)) {
      errors.push(`${fieldName}[${i}] duplicates '${trimmed}'`);
      return;
    }
    seen.add(trimmed);
    value.push(trimmed);
  });
  if (errors.length) return { ok: false, errors };
  return { ok: true, value };
}
export function normalizeDevStartGateConfig(raw) {
  const value = emptyDevStartGateConfig();
  if (raw == null) {
    return { ok: true, value };
  }
  if (typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, errors: ["workflow.dev_start_gate must be a mapping when set"] };
  }
  const allowed = ["enabled", "required_for", "plan_section", "blocker_uids", "required_fields"];
  const errors = [];
  for (const key of Object.keys(raw)) {
    if (!allowed.includes(key)) errors.push(`workflow.dev_start_gate has unknown key '${key}'`);
  }
  if (raw.enabled != null) {
    if (typeof raw.enabled !== "boolean") {
      errors.push("workflow.dev_start_gate.enabled must be a boolean when set");
    } else {
      value.enabled = raw.enabled;
    }
  }
  if (raw.required_for != null) {
    if (!DEV_START_GATE_REQUIRED_FOR.includes(raw.required_for)) {
      errors.push(`workflow.dev_start_gate.required_for must be one of: ${DEV_START_GATE_REQUIRED_FOR.join(", ")}`);
    } else {
      value.required_for = raw.required_for;
    }
  }
  if (raw.plan_section != null) {
    if (typeof raw.plan_section !== "string" || raw.plan_section.trim() === "") {
      errors.push("workflow.dev_start_gate.plan_section must be a non-empty string when set");
    } else if (raw.plan_section.includes("\n") || raw.plan_section.includes("\r")) {
      errors.push("workflow.dev_start_gate.plan_section must be a single-line string");
    } else {
      value.plan_section = raw.plan_section.trim();
    }
  }
  if (raw.blocker_uids != null) {
    const r = validateStringList(raw.blocker_uids, "workflow.dev_start_gate.blocker_uids", { uid: true });
    if (!r.ok) errors.push(...r.errors);
    else value.blocker_uids = r.value;
  }
  if (raw.required_fields != null) {
    const r = validateStringList(raw.required_fields, "workflow.dev_start_gate.required_fields");
    if (!r.ok) errors.push(...r.errors);
    else if (r.value.length === 0) errors.push("workflow.dev_start_gate.required_fields must not be empty when set");
    else value.required_fields = r.value;
  }
  if (errors.length) return { ok: false, errors };
  return { ok: true, value };
}
export function normalizeVerificationConfig(raw) {
  if (raw == null) {
    return { ok: true, value: { toolchain_fingerprint_command: null } };
  }
  if (typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, errors: ["workflow.verification must be a mapping when set"] };
  }
  const allowed = ["toolchain_fingerprint_command"];
  const errors = [];
  for (const key of Object.keys(raw)) {
    if (!allowed.includes(key)) {
      errors.push(`workflow.verification has unknown key '${key}'`);
    }
  }
  let command = null;
  if (raw.toolchain_fingerprint_command != null) {
    if (
      typeof raw.toolchain_fingerprint_command !== "string"
      || raw.toolchain_fingerprint_command.trim() === ""
    ) {
      errors.push(
        "workflow.verification.toolchain_fingerprint_command must be a non-empty string when set",
      );
    } else {
      command = raw.toolchain_fingerprint_command;
    }
  }
  if (errors.length) return { ok: false, errors };
  return { ok: true, value: { toolchain_fingerprint_command: command } };
}
export function normalizeWorkflowConfig(raw) {
  if (raw == null || typeof raw !== "object") {
    return { ok: true, value: emptyWorkflowConfig() };
  }
  if (Array.isArray(raw)) {
    return { ok: false, errors: ["workflow must be a mapping, not a list"] };
  }
  // Scalar string-typed keys handled inline; nested-mapping keys delegated to
  // their own normalizers below.
  const allowedScalar = ["test_command", "completion_command", "lint_command", "format_command", "policy_command", "precommit_command", "base_branch"];
  const allowedNested = ["codex_review", "test_quality_review", "pr_title", "integration_manager", "dev_start_gate", "review_disposition", "verification"];
  const allowed = [...allowedScalar, ...allowedNested];
  const value = emptyWorkflowConfig();
  const errors = [];
  for (const key of Object.keys(raw)) {
    if (!allowed.includes(key)) {
      errors.push(`workflow has unknown key '${key}'`);
      continue;
    }
    if (allowedNested.includes(key)) continue; // handled below
    const v = raw[key];
    if (v == null) continue;
    if (typeof v !== "string" || v.trim() === "") {
      errors.push(`workflow.${key} must be a non-empty string when set`);
      continue;
    }
    if (key === "base_branch" && !isSafeGitRefName(v)) {
      errors.push(
        `workflow.base_branch '${v}' is not a safe Git ref name; allowed characters are [A-Za-z0-9._/-] and the value must satisfy git check-ref-format`,
      );
      continue;
    }
    value[key] = v;
  }
  const codexResult = normalizeReviewerConfig(raw.codex_review, "workflow.codex_review");
  if (!codexResult.ok) errors.push(...codexResult.errors);
  else value.codex_review = codexResult.value;
  const testQualityResult = normalizeReviewerConfig(raw.test_quality_review, "workflow.test_quality_review");
  if (!testQualityResult.ok) errors.push(...testQualityResult.errors);
  else value.test_quality_review = testQualityResult.value;
  const prTitleResult = normalizePrTitleConfig(raw.pr_title);
  if (!prTitleResult.ok) errors.push(...prTitleResult.errors);
  else value.pr_title = prTitleResult.value;
  const integrationManagerResult = normalizeIntegrationManagerConfig(raw.integration_manager);
  if (!integrationManagerResult.ok) errors.push(...integrationManagerResult.errors);
  else value.integration_manager = integrationManagerResult.value;
  const devStartGateResult = normalizeDevStartGateConfig(raw.dev_start_gate);
  if (!devStartGateResult.ok) errors.push(...devStartGateResult.errors);
  else value.dev_start_gate = devStartGateResult.value;
  const reviewDispositionResult = normalizeReviewDispositionConfig(raw.review_disposition);
  if (!reviewDispositionResult.ok) errors.push(...reviewDispositionResult.errors);
  else value.review_disposition = reviewDispositionResult.value;
  const verificationResult = normalizeVerificationConfig(raw.verification);
  if (!verificationResult.ok) errors.push(...verificationResult.errors);
  else value.verification = verificationResult.value;
  if (errors.length) return { ok: false, errors };
  return { ok: true, value };
}
export function normalizeRoutingConfig(raw) {
  if (raw == null) {
    return { ok: true, value: { enabled: false, default_provider: "claude", stages: {} } };
  }
  if (typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, errors: ["routing must be a mapping, not a list or scalar"] };
  }
  const allowed = ["enabled", "default_provider", "stages"];
  const errors = [];
  for (const key of Object.keys(raw)) {
    if (!allowed.includes(key)) {
      errors.push(`routing has unknown key '${key}'`);
    }
  }
  let enabled = false;
  if (raw.enabled != null) {
    if (typeof raw.enabled !== "boolean") {
      errors.push("routing.enabled must be a boolean when set");
    } else {
      enabled = raw.enabled;
    }
  }
  let defaultProvider = "claude";
  if (raw.default_provider != null) {
    if (!ROUTING_PROVIDERS.includes(raw.default_provider)) {
      errors.push(`routing.default_provider must be one of: ${ROUTING_PROVIDERS.join(", ")}`);
    } else {
      defaultProvider = raw.default_provider;
    }
  }
  const stages = {};
  if (raw.stages != null) {
    if (typeof raw.stages !== "object" || Array.isArray(raw.stages)) {
      errors.push("routing.stages must be a mapping from stage name to route config");
    } else {
      for (const [stage, route] of Object.entries(raw.stages)) {
        const normalized = normalizeRoutingStageConfig(stage, route, { defaultProvider });
        if (!normalized.ok) {
          errors.push(...normalized.errors);
        } else {
          stages[stage] = normalized.value;
        }
      }
    }
  }
  if (errors.length) return { ok: false, errors };
  return { ok: true, value: { enabled, default_provider: defaultProvider, stages } };
}
function normalizeRoutingStageConfig(stage, raw, { defaultProvider }) {
  const prefix = `routing.stages.${stage}`;
  const errors = [];
  if (!ROUTING_STAGE_NAME_RE.test(stage)) {
    errors.push(`${prefix} key must match ${ROUTING_STAGE_NAME_RE}`);
  }
  if (raw == null || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, errors: [...errors, `${prefix} must be a mapping`] };
  }
  const allowed = ["tier", "provider", "model"];
  for (const key of Object.keys(raw)) {
    if (!allowed.includes(key)) {
      errors.push(`${prefix} has unknown key '${key}'`);
    }
  }
  const tier = raw.tier;
  if (!ROUTING_TIERS.includes(tier)) {
    errors.push(`${prefix}.tier must be one of: ${ROUTING_TIERS.join(", ")}`);
  }
  const provider = raw.provider ?? defaultProvider;
  if (!ROUTING_PROVIDERS.includes(provider)) {
    errors.push(`${prefix}.provider must be one of: ${ROUTING_PROVIDERS.join(", ")}`);
  }
  const model = raw.model ?? CLAUDE_MODEL_BY_TIER[tier];
  if (provider === "claude" && typeof model === "string" && !/^claude-(haiku|sonnet|opus)-[0-9]+(-[0-9]+)?$/.test(model)) {
    errors.push(`${prefix}.model must be a canonical Claude model id like claude-sonnet-5`);
  } else if (typeof model !== "string" || model.trim() === "") {
    errors.push(`${prefix}.model must be a non-empty string`);
  }
  if (errors.length) return { ok: false, errors };
  return { ok: true, value: { tier, provider, model } };
}
export const CODEX_REVIEW_HARD_CAP = 3;
export const CODEX_REVIEW_CYCLE_MARKER_PREFIX = "<!-- gc:codex-review-cycle";
const CODEX_REVIEW_CYCLE_MARKER_RE =
  /<!--\s*gc:codex-review-cycle\s+cycle="(\d+)"\s+pr="(\d+)"[^]*?-->/;
export function parseCodexReviewCycleMarkers(commentBodies, prNumber) {
  if (!Array.isArray(commentBodies)) return 0;
  let count = 0;
  for (const body of commentBodies) {
    if (typeof body !== "string") continue;
    const match = body.match(CODEX_REVIEW_CYCLE_MARKER_RE);
    if (!match) continue;
    const markerPr = Number.parseInt(match[2], 10);
    if (markerPr === prNumber) count += 1;
  }
  return count;
}
export function evaluateCodexReviewCycleCap({
  priorCount,
  prNumber,
  hardCap = CODEX_REVIEW_HARD_CAP,
  overrideCap = false,
  overrideReason = null,
}) {
  if (typeof priorCount !== "number" || !Number.isFinite(priorCount) || priorCount < 0) {
    throw new Error(`evaluateCodexReviewCycleCap: priorCount must be a non-negative number, got ${priorCount}`);
  }

  if (overrideCap === true) {
    if (typeof overrideReason !== "string" || overrideReason.trim() === "") {
      return {
        ok: false,
        error: "codex_review_override_missing_reason",
        message:
          "override_cap=true requires a non-empty override_reason quoting the user's authorization. " +
          "Audits cannot distinguish legitimate overrides from accidental ones without a reason.",
        pr_number: prNumber,
        prior_cycles: priorCount,
        cap: hardCap,
      };
    }
    return {
      ok: true,
      nextCycle: priorCount + 1,
      cap: hardCap,
      override: true,
      override_reason: overrideReason.trim(),
      next_action: "fix_findings_then_summarize_and_escalate",
    };
  }

  if (priorCount >= hardCap) {
    return {
      ok: false,
      error: "codex_review_cap_reached",
      message:
        `gc_codex_review hard cap reached (${hardCap} cycles) for PR #${prNumber}. ` +
        `Per GC-O007 / ADR-029, after cycle ${hardCap} you must (a) post a summary of findings + fixes ` +
        `to the issue thread, then (b) escalate to the user and ask whether to run cycle ${hardCap + 1} ` +
        `or ship as-is. Do not address findings by silently re-invoking codex. If the user authorizes ` +
        `another cycle, retry with override_cap=true and override_reason="<their authorization>".`,
      pr_number: prNumber,
      prior_cycles: priorCount,
      cap: hardCap,
      next_action: "post_summary_and_escalate_to_user",
    };
  }

  // Cycle 1 returns next_action that nudges toward "fix findings"; cycle 2
  // returns the stronger nudge that includes the summarize-and-escalate
  // discipline (the gap that #794 was specifically filed to close).
  const nextCycle = priorCount + 1;
  return {
    ok: true,
    nextCycle,
    cap: hardCap,
    next_action:
      nextCycle === hardCap
        ? "fix_all_findings_then_summarize_and_escalate"
        : "fix_all_findings_and_push",
  };
}
export function buildCodexReviewCycleMarker({ prNumber, cycleNumber, override = false, overrideReason = null }) {
  const overrideAttr = override === true ? ' override="true"' : "";
  const reasonAttr =
    override === true && typeof overrideReason === "string" && overrideReason.trim() !== ""
      ? ` reason=${JSON.stringify(overrideReason.trim())}`
      : "";
  const headline = override
    ? `_gc_codex_review cycle ${cycleNumber} (USER-AUTHORIZED OVERRIDE past cap ${CODEX_REVIEW_HARD_CAP}) complete for PR #${prNumber}._`
    : `_gc_codex_review cycle ${cycleNumber} of ${CODEX_REVIEW_HARD_CAP} complete for PR #${prNumber}._`;
  const reasonLine =
    override && typeof overrideReason === "string" && overrideReason.trim() !== ""
      ? `\nOverride reason: ${overrideReason.trim()}`
      : "";
  return [
    `${CODEX_REVIEW_CYCLE_MARKER_PREFIX} cycle="${cycleNumber}" pr="${prNumber}"${overrideAttr}${reasonAttr} -->`,
    "",
    headline +
      ` Posted by the MCP server to enforce the hard-cap-${CODEX_REVIEW_HARD_CAP} contract (issues #794, #804). ` +
      "Do not edit or delete — used by the next `gc_codex_review` invocation to count cycles." +
      reasonLine,
  ].join("\n");
}

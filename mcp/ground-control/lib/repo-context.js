// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { isAbsolute, relative, resolve as resolvePath } from "node:path";

export const SUPPORTED_GROUND_CONTROL_SCHEMA_VERSIONS = [1];
export function resolveRepoRelativePath(repoRoot, rawPath, fieldName) {
  if (typeof rawPath !== "string" || rawPath.trim() === "") {
    return { ok: false, error: `${fieldName} must be a non-empty string when set` };
  }
  if (isAbsolute(rawPath)) {
    return {
      ok: false,
      error: `${fieldName} must be a repo-relative path (got absolute path '${rawPath}')`,
    };
  }
  const abs = resolvePath(repoRoot, rawPath);
  const rel = relative(repoRoot, abs);
  if (rel === "" || rel.startsWith("..") || isAbsolute(rel)) {
    return {
      ok: false,
      error: `${fieldName} must stay inside the repository root (got '${rawPath}')`,
    };
  }
  return { ok: true, rel, abs };
}
export const DEFAULT_POLICY_COMMAND = "make policy";
export function resolveWorkflowPolicyCommand(context) {
  const configured = context?.workflow?.policy_command;
  if (typeof configured === "string" && configured.trim() !== "") return configured;
  return DEFAULT_POLICY_COMMAND;
}
export const DEFAULT_PRECOMMIT_COMMAND = "pre-commit run --all-files";
export function resolveWorkflowPrecommitCommand(context) {
  const configured = context?.workflow?.precommit_command;
  if (typeof configured === "string" && configured.trim() !== "") return configured;
  return DEFAULT_PRECOMMIT_COMMAND;
}
export function emptyWorkflowConfig() {
  return {
    test_command: null,
    completion_command: null,
    lint_command: null,
    format_command: null,
    policy_command: DEFAULT_POLICY_COMMAND,
    precommit_command: DEFAULT_PRECOMMIT_COMMAND,
    base_branch: null,
    // Per-reviewer pre-push cap defaults. `null` means "use the MCP tool
    // default" (issue #906 lowered the tool default from 3 to 1; repos that
    // want the old behavior set `pre_push_cap: 3` explicitly).
    codex_review: { pre_push_cap: null, non_verdict_retry_limit: null },
    test_quality_review: { pre_push_cap: null, non_verdict_retry_limit: null },
    // PR title validation config (issue #896). `null` means "use the canonical
    // defaults declared in step-09-pr-body.md".
    pr_title: null,
    // Integration manager config (issue #989). All fields null means "use the
    // tool-layer defaults at call time".
    integration_manager: { approval_label: null, ordering: null, max_queue_size: null, merge_strategy: null },
    // Dev-start plan gate. Disabled by default so existing repositories keep
    // their current /implement flow until they opt in through repo config.
    dev_start_gate: emptyDevStartGateConfig(),
    // Review-cap auto-disposition (gc_review_cap_disposition). Disabled by
    // default so existing repos keep the human-only override_cap path until
    // they opt in. `mode: shadow` records a disposition without authorizing an
    // over-cap cycle; `authoritative` lets a recorded one_more_cycle grant
    // unblock an auto_grant cycle. `max_auto_overrides` caps how many over-cap
    // cycles the auto path can ever grant per (issue, reviewer).
    review_disposition: { enabled: false, mode: "shadow", max_auto_overrides: 1, judge: { enabled: false, model: null } },
  };
}
export const DEV_START_GATE_REQUIRED_FOR = Object.freeze(["source-bearing"]);
export const DEFAULT_DEV_START_GATE_PLAN_SECTION = "Dev-Start Gate";
export const DEFAULT_DEV_START_GATE_REQUIRED_FIELDS = Object.freeze([
  "Requirement wave or gate",
  "Boundary owner",
  "Contract or seam",
  "Tenant/principal/authz/audit/evidence/provenance context",
  "Connectivity/offline behavior",
  "Security relevance decision",
  "Framework/control-family impact",
  "Verification risk score",
  "Verification plan",
  "Supply chain/provenance impact",
  "Sovereignty/FOCI impact",
  "Quality-gate readiness",
  "Dev-start gate satisfied",
]);
export const DEV_START_GATE_SECURITY_DECISIONS = Object.freeze([
  "security-relevant",
  "not security-relevant",
  "no security baseline",
]);
export function emptyDevStartGateConfig() {
  return {
    enabled: false,
    required_for: "source-bearing",
    plan_section: DEFAULT_DEV_START_GATE_PLAN_SECTION,
    blocker_uids: [],
    required_fields: [...DEFAULT_DEV_START_GATE_REQUIRED_FIELDS],
  };
}
export function normalizePrTitleConfig(raw) {
  if (raw == null) {
    return { ok: true, value: null };
  }
  if (typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, errors: ["workflow.pr_title must be a mapping when set"] };
  }
  const allowed = ["types", "subject_pattern", "require_scope"];
  const errors = [];
  for (const key of Object.keys(raw)) {
    if (!allowed.includes(key)) {
      errors.push(`workflow.pr_title has unknown key '${key}'`);
    }
  }
  const value = {};
  if (raw.types != null) {
    if (!Array.isArray(raw.types)) {
      errors.push("workflow.pr_title.types must be a list of strings");
    } else {
      for (const t of raw.types) {
        if (typeof t !== "string" || t.trim() === "") {
          errors.push("workflow.pr_title.types entries must be non-empty strings");
          break;
        }
      }
      if (!errors.some((e) => e.includes("types"))) {
        value.types = [...raw.types];
      }
    }
  } else {
    value.types = null;
  }
  if (raw.subject_pattern != null) {
    if (typeof raw.subject_pattern !== "string" || raw.subject_pattern.trim() === "") {
      errors.push("workflow.pr_title.subject_pattern must be a non-empty string when set");
    } else {
      value.subject_pattern = raw.subject_pattern;
    }
  } else {
    value.subject_pattern = null;
  }
  if (raw.require_scope != null) {
    if (typeof raw.require_scope !== "boolean") {
      errors.push("workflow.pr_title.require_scope must be a boolean when set");
    } else {
      value.require_scope = raw.require_scope;
    }
  } else {
    value.require_scope = null;
  }
  if (errors.length) return { ok: false, errors };
  return { ok: true, value };
}
const REVIEWER_PRE_PUSH_CAP_MIN = 1;
const REVIEWER_PRE_PUSH_CAP_MAX = 10;
// Additional attempts after the first when a station renders no verdict (issue #1476). Zero is
// meaningful — it opts out of automatic re-attempts — which is why the lower bound is 0 and not 1
// as it is for pre_push_cap. The upper bound is deliberately small: a station that cannot be
// observed in three total attempts is a hard external dependency, not something to keep hammering.
const REVIEWER_NON_VERDICT_RETRY_MIN = 0;
const REVIEWER_NON_VERDICT_RETRY_MAX = 2;
export function normalizeReviewerConfig(rawBlock, blockName) {
  if (rawBlock == null) {
    return { ok: true, value: { pre_push_cap: null, non_verdict_retry_limit: null } };
  }
  if (typeof rawBlock !== "object" || Array.isArray(rawBlock)) {
    return { ok: false, errors: [`${blockName} must be a mapping when set`] };
  }
  const allowed = ["pre_push_cap", "non_verdict_retry_limit"];
  const errors = [];
  for (const key of Object.keys(rawBlock)) {
    if (!allowed.includes(key)) {
      errors.push(`${blockName} has unknown key '${key}'`);
    }
  }
  const pre_push_cap = normalizeBoundedReviewerInteger(
    rawBlock.pre_push_cap,
    `${blockName}.pre_push_cap`,
    REVIEWER_PRE_PUSH_CAP_MIN,
    REVIEWER_PRE_PUSH_CAP_MAX,
    errors,
  );
  const non_verdict_retry_limit = normalizeBoundedReviewerInteger(
    rawBlock.non_verdict_retry_limit,
    `${blockName}.non_verdict_retry_limit`,
    REVIEWER_NON_VERDICT_RETRY_MIN,
    REVIEWER_NON_VERDICT_RETRY_MAX,
    errors,
  );
  if (errors.length) return { ok: false, errors };
  return { ok: true, value: { pre_push_cap, non_verdict_retry_limit } };
}
/** Null means "unset — use the tool-layer default"; an out-of-range value is an error, not a clamp. */
function normalizeBoundedReviewerInteger(value, label, min, max, errors) {
  if (value == null) return null;
  if (typeof value !== "number" || !Number.isInteger(value)) {
    errors.push(`${label} must be an integer`);
    return null;
  }
  if (value < min || value > max) {
    errors.push(`${label} must be between ${min} and ${max} inclusive`);
    return null;
  }
  return value;
}
export const INTEGRATION_MANAGER_MAX_QUEUE_SIZE_MIN = 1;
export const INTEGRATION_MANAGER_MAX_QUEUE_SIZE_MAX = 100;
export const INTEGRATION_MANAGER_ORDERINGS = ["pr_number_asc", "pr_number_desc", "approved_at_asc"];
export const INTEGRATION_MANAGER_MERGE_STRATEGIES = ["merge", "squash", "rebase"];
export function isSafeLabelName(s) {
  if (typeof s !== "string" || s.length === 0) return false;
  if (s.length > 50) return false;
  if (s.length === 1) {
    // Single character: must be a non-space printable ASCII character.
    return /^[\x21-\x7E]$/.test(s);
  }
  // Length ≥ 2: first and last chars must be non-space printable ASCII;
  // interior chars may include spaces (\x20) but no control chars or non-ASCII.
  return /^[\x21-\x7E][\x20-\x7E]*[\x21-\x7E]$/.test(s);
}
export function normalizeIntegrationManagerConfig(raw) {
  if (raw == null) {
    return { ok: true, value: { approval_label: null, ordering: null, max_queue_size: null, merge_strategy: null } };
  }
  if (typeof raw !== "object" || Array.isArray(raw)) {
    return {
      ok: false,
      errors: ["workflow.integration_manager must be a mapping when set"],
    };
  }
  const allowed = ["approval_label", "ordering", "max_queue_size", "merge_strategy"];
  const errors = [];
  for (const key of Object.keys(raw)) {
    if (!allowed.includes(key)) {
      errors.push(`workflow.integration_manager has unknown key '${key}'`);
    }
  }
  let approval_label = null;
  if (raw.approval_label != null) {
    if (typeof raw.approval_label !== "string" || !isSafeLabelName(raw.approval_label)) {
      errors.push(
        "workflow.integration_manager.approval_label must be a 1–50 character printable ASCII string without leading or trailing whitespace",
      );
    } else {
      approval_label = raw.approval_label;
    }
  }
  let ordering = null;
  if (raw.ordering != null) {
    if (!INTEGRATION_MANAGER_ORDERINGS.includes(raw.ordering)) {
      errors.push(
        `workflow.integration_manager.ordering must be one of: ${INTEGRATION_MANAGER_ORDERINGS.join(", ")}`,
      );
    } else {
      ordering = raw.ordering;
    }
  }
  let max_queue_size = null;
  if (raw.max_queue_size != null) {
    const v = raw.max_queue_size;
    if (typeof v !== "number" || !Number.isInteger(v)) {
      errors.push("workflow.integration_manager.max_queue_size must be an integer");
    } else if (v < INTEGRATION_MANAGER_MAX_QUEUE_SIZE_MIN || v > INTEGRATION_MANAGER_MAX_QUEUE_SIZE_MAX) {
      errors.push(
        `workflow.integration_manager.max_queue_size must be between ${INTEGRATION_MANAGER_MAX_QUEUE_SIZE_MIN} and ${INTEGRATION_MANAGER_MAX_QUEUE_SIZE_MAX} inclusive`,
      );
    } else {
      max_queue_size = v;
    }
  }
  let merge_strategy = null;
  if (raw.merge_strategy != null) {
    if (!INTEGRATION_MANAGER_MERGE_STRATEGIES.includes(raw.merge_strategy)) {
      errors.push(
        `workflow.integration_manager.merge_strategy must be one of: ${INTEGRATION_MANAGER_MERGE_STRATEGIES.join(", ")}`,
      );
    } else {
      merge_strategy = raw.merge_strategy;
    }
  }
  if (errors.length) return { ok: false, errors };
  return { ok: true, value: { approval_label, ordering, max_queue_size, merge_strategy } };
}
export function isSafeGitRefName(s) {
  if (typeof s !== "string" || s === "") return false;
  if (!/^[A-Za-z0-9._/-]+$/.test(s)) return false;
  if (s.startsWith("-")) return false;
  if (s.startsWith("/") || s.endsWith("/")) return false;
  if (s.startsWith(".") || s.endsWith(".")) return false;
  if (s.endsWith(".lock")) return false;
  if (s.includes("..")) return false;
  if (s.includes("//")) return false;
  const components = s.split("/");
  if (components.some((part) => (
    part === ""
    || part.startsWith(".")
    || part.endsWith(".")
    || part.endsWith(".lock")
  ))) return false;
  return true;
}
export const REVIEW_DISPOSITION_MODES = ["shadow", "authoritative"];
export const REVIEW_DISPOSITION_MAX_AUTO_OVERRIDES_MIN = 0;
export const REVIEW_DISPOSITION_MAX_AUTO_OVERRIDES_MAX = 5;
function emptyReviewDispositionConfig() {
  return { enabled: false, mode: "shadow", max_auto_overrides: 1, judge: { enabled: false, model: null } };
}
export function normalizeReviewDispositionConfig(rawBlock) {
  if (rawBlock == null) {
    return { ok: true, value: emptyReviewDispositionConfig() };
  }
  if (typeof rawBlock !== "object" || Array.isArray(rawBlock)) {
    return { ok: false, errors: ["workflow.review_disposition must be a mapping when set"] };
  }
  const allowed = ["enabled", "mode", "max_auto_overrides", "judge"];
  const errors = [];
  for (const key of Object.keys(rawBlock)) {
    if (!allowed.includes(key)) {
      errors.push(`workflow.review_disposition has unknown key '${key}'`);
    }
  }
  const value = emptyReviewDispositionConfig();
  if (rawBlock.enabled != null) {
    if (typeof rawBlock.enabled !== "boolean") {
      errors.push("workflow.review_disposition.enabled must be a boolean when set");
    } else {
      value.enabled = rawBlock.enabled;
    }
  }
  if (rawBlock.mode != null) {
    if (!REVIEW_DISPOSITION_MODES.includes(rawBlock.mode)) {
      errors.push(`workflow.review_disposition.mode must be one of: ${REVIEW_DISPOSITION_MODES.join(", ")}`);
    } else {
      value.mode = rawBlock.mode;
    }
  }
  if (rawBlock.max_auto_overrides != null) {
    const v = rawBlock.max_auto_overrides;
    if (typeof v !== "number" || !Number.isInteger(v)) {
      errors.push("workflow.review_disposition.max_auto_overrides must be an integer");
    } else if (
      v < REVIEW_DISPOSITION_MAX_AUTO_OVERRIDES_MIN ||
      v > REVIEW_DISPOSITION_MAX_AUTO_OVERRIDES_MAX
    ) {
      errors.push(
        `workflow.review_disposition.max_auto_overrides must be between ${REVIEW_DISPOSITION_MAX_AUTO_OVERRIDES_MIN} and ${REVIEW_DISPOSITION_MAX_AUTO_OVERRIDES_MAX} inclusive`,
      );
    } else {
      value.max_auto_overrides = v;
    }
  }
  if (rawBlock.judge != null) {
    if (typeof rawBlock.judge !== "object" || Array.isArray(rawBlock.judge)) {
      errors.push("workflow.review_disposition.judge must be a mapping when set");
    } else {
      const judgeAllowed = ["enabled", "model"];
      for (const key of Object.keys(rawBlock.judge)) {
        if (!judgeAllowed.includes(key)) {
          errors.push(`workflow.review_disposition.judge has unknown key '${key}'`);
        }
      }
      if (rawBlock.judge.enabled != null) {
        if (typeof rawBlock.judge.enabled !== "boolean") {
          errors.push("workflow.review_disposition.judge.enabled must be a boolean when set");
        } else {
          value.judge.enabled = rawBlock.judge.enabled;
        }
      }
      if (rawBlock.judge.model != null) {
        if (typeof rawBlock.judge.model !== "string" || rawBlock.judge.model.trim() === "") {
          errors.push("workflow.review_disposition.judge.model must be a non-empty string when set");
        } else {
          value.judge.model = rawBlock.judge.model.trim();
        }
      }
    }
  }
  if (errors.length) return { ok: false, errors };
  return { ok: true, value };
}
export function normalizeSonarcloudConfig(raw) {
  if (raw == null) {
    return { ok: true, value: null };
  }
  if (typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, errors: ["sonarcloud must be a mapping, not a list or scalar"] };
  }
  const allowed = ["project_key", "organization", "quality_gate"];
  const errors = [];
  for (const key of Object.keys(raw)) {
    if (!allowed.includes(key)) {
      errors.push(`sonarcloud has unknown key '${key}'`);
    }
  }
  const project_key = raw.project_key;
  const organization = raw.organization;
  const quality_gate = raw.quality_gate;
  if (typeof project_key !== "string" || project_key.trim() === "") {
    errors.push("sonarcloud.project_key must be a non-empty string when sonarcloud is set");
  }
  if (typeof organization !== "string" || organization.trim() === "") {
    errors.push("sonarcloud.organization must be a non-empty string when sonarcloud is set");
  }
  if (quality_gate != null) {
    if (typeof quality_gate !== "string" || quality_gate.trim() === "") {
      errors.push("sonarcloud.quality_gate must be a non-empty string when set");
    }
  }
  if (errors.length) return { ok: false, errors };
  const value = { project_key, organization };
  if (quality_gate != null && typeof quality_gate === "string" && quality_gate.trim() !== "") {
    value.quality_gate = quality_gate.trim();
  }
  return { ok: true, value };
}
export function normalizeRulesConfig(raw) {
  if (raw == null) {
    return { ok: true, value: { plan_rules_path: null } };
  }
  if (typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, errors: ["rules must be a mapping"] };
  }
  const allowed = ["plan_rules"];
  const errors = [];
  for (const key of Object.keys(raw)) {
    if (!allowed.includes(key)) {
      errors.push(`rules has unknown key '${key}'`);
    }
  }
  if (errors.length) return { ok: false, errors };

  const planRules = raw.plan_rules;
  if (planRules == null) {
    return { ok: true, value: { plan_rules_path: null } };
  }
  if (typeof planRules !== "string" || planRules.trim() === "") {
    return { ok: false, errors: ["rules.plan_rules must be a non-empty string when set"] };
  }
  return { ok: true, value: { plan_rules_path: planRules } };
}
function emptyDocsConfig() {
  return {
    adr_dir: null,
    architecture_overview: null,
    coding_standards: null,
    workflow_reference: null,
    knowledge_base: null,
  };
}
export function normalizeDocsConfig(raw) {
  if (raw == null) {
    return { ok: true, value: emptyDocsConfig() };
  }
  if (typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, errors: ["docs must be a mapping, not a list or scalar"] };
  }
  const allowed = ["adr_dir", "architecture_overview", "coding_standards", "workflow_reference", "knowledge_base"];
  const value = emptyDocsConfig();
  const errors = [];
  for (const key of Object.keys(raw)) {
    if (!allowed.includes(key)) {
      errors.push(`docs has unknown key '${key}'`);
      continue;
    }
    const v = raw[key];
    if (v == null) continue;
    if (typeof v !== "string" || v.trim() === "") {
      errors.push(`docs.${key} must be a non-empty string when set`);
      continue;
    }
    value[key] = v;
  }
  if (errors.length) return { ok: false, errors };
  return { ok: true, value };
}
export function emptyExamplePathsConfig() {
  return { source: null, test: null };
}

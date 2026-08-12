// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { execFile as execFileCb } from "node:child_process";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import { CLAUDE_MODEL_BY_TIER, DEFAULT_IMPLEMENT_ROUTING_STAGES, ROUTING_STAGE_NAME_RE, ROUTING_TIERS } from "./repo-vocabulary.js";

// execFileWithInput and the GC_CODEX_TIMEOUT_MS parsing/bounds live in
// model-subprocess.js (issue #1518, split out to stay under the 500-LOC file
// gate). Re-exported here so this remains the single import path every
// existing caller already uses.
export {
  CODEX_TIMEOUT_MS_DEFAULT,
  CODEX_TIMEOUT_MS_MAX,
  CODEX_TIMEOUT_MS_MIN,
  getDefaultCodexTimeoutMs,
  execFileWithInput,
  parseCodexTimeoutMs,
} from "./model-subprocess.js";

export const execFile = promisify(execFileCb);
export const GROUND_CONTROL_PROJECT_RE = /^[a-z0-9][a-z0-9-]*$/;
// Shared with the .ground-control.yaml parser and the repo-identity resolver. It lives beside its
// sibling rather than privately in whichever module happened to need it first: a second copy is how
// two validators of the same value drift into disagreeing about what is well-formed.
export const GITHUB_REPO_RE = /^[a-zA-Z0-9][a-zA-Z0-9._-]*\/[a-zA-Z0-9][a-zA-Z0-9._-]*$/;
export function formatCommandFailure(command, error) {
  const details = [];
  if (error.code === "ENOENT") {
    details.push(`${command} is not installed or not available on PATH`);
  } else if (error.message) {
    details.push(error.message);
  }

  const stderr = error.stderr?.trim();
  const stdout = error.stdout?.trim();
  if (stderr) {
    details.push(`stderr: ${stderr}`);
  } else if (stdout) {
    details.push(`stdout: ${stdout}`);
  }

  return details.join(" | ");
}
export function buildGroundControlContextSnippet(project = "your-project-id") {
  return [
    "## Ground Control Context",
    "",
    "This repo's Ground Control project id, workflow commands, SonarCloud",
    "settings, and plan rules live in `.ground-control.yaml` at repo root.",
    "Agents read it via the `gc_get_repo_ground_control_context` MCP tool.",
  ].join("\n");
}
export function buildSuggestedGroundControlYaml(project = "your-project-id") {
  return [
    "schema_version: 1",
    `project: ${project}`,
    "",
    "# Optional fields:",
    "# github_repo: owner/repo",
    "# short_code: GC  # Optional: short project code for tmux session renaming (1-8 uppercase alphanumeric)",
    "# workflow:",
    "#   test_command: <how to run tests>",
    "#   completion_command: <how to run the full CI gate>",
    "#   lint_command: <how to run the linter>",
    "#   format_command: <how to run the formatter>",
    "#   # Repo-native policy/governance gate. Defaults to `make policy`; set it",
    "#   # when your gate is named differently. It is never skipped.",
    "#   policy_command: make policy",
    "#   # Pre-publish hook boundary. Defaults to `pre-commit run --all-files`;",
    "#   # set it for lefthook, husky, or a bespoke script.",
    "#   precommit_command: pre-commit run --all-files",
    "#   # Per-reviewer pre-push caps (issue #906). Omit to use MCP-tool defaults.",
    "#   codex_review:",
    "#     pre_push_cap: 1",
    "#   test_quality_review:",
    "#     pre_push_cap: 1",
    "#   # PR title validation (issue #896). Omit to use /implement skill defaults.",
    "#   pr_title:",
    "#     types: [security, added, changed, deprecated, removed, fixed,",
    "#             feat, fix, chore, docs, refactor, test, ci, build, perf, revert]",
    "#     subject_pattern: \"^[a-z].*$\"",
    "#     require_scope: false",
    "#   # Optional dev-start plan gate. Disabled unless a repo opts in.",
    "#   dev_start_gate:",
    "#     enabled: false",
    "#     required_for: source-bearing",
    "#     plan_section: Dev-Start Gate",
    "#     blocker_uids: []",
    "#     required_fields:",
    "#       - Requirement wave or gate",
    "#       - Boundary owner",
    "#       - Contract or seam",
    "#   # Optional review-cap auto-disposition (gc_review_cap_disposition).",
    "#   # Disabled unless a repo opts in; with enabled:false every existing",
    "#   # review-cap behavior is unchanged.",
    "#   review_disposition:",
    "#     enabled: false",
    "#     mode: shadow            # shadow | authoritative",
    "#     max_auto_overrides: 1",
    "#     judge:",
    "#       enabled: false",
    "#       model: null",
    "#   # Optional tiered publish verification (issue #1497). When a toolchain",
    "#   # fingerprint command is set, verify posts a content-addressed",
    "#   # attestation that the publish band reuses instead of re-verifying an",
    "#   # unchanged tree; any tree/base/config/toolchain change re-runs the full",
    "#   # gate. Absent (default) = no reuse, every gate runs in full (fail-closed).",
    "#   verification:",
    "#     toolchain_fingerprint_command: <command emitting one lowercase sha256>",
    "# sonarcloud:",
    "#   project_key: <sonar-project-key>",
    "#   organization: <sonar-org>",
    "#   quality_gate: <sonar-quality-gate-name>  # optional; SonarCloud association is server-side",
    "# rules:",
    "#   plan_rules: .gc/plan-rules.md",
    "# knowledge:",
    "#   dir: docs/knowledge",
    "#   # optional overrides (default to <dir>/SCHEMA.md and <dir>/inbox):",
    "#   # schema: docs/knowledge/SCHEMA.md",
    "#   # inbox: docs/knowledge/inbox",
    "",
    "# Workflow-packaging fields (ADR-027). The canonical /implement skill",
    "# renders prose against these via {cfg.X|default Y} placeholders.",
    "# docs:",
    "#   adr_dir: architecture/adrs/",
    "#   architecture_overview: docs/architecture/ARCHITECTURE.md",
    "#   coding_standards: docs/CODING_STANDARDS.md",
    "#   workflow_reference: docs/DEVELOPMENT_WORKFLOW.md",
    "#   knowledge_base: docs/knowledge/",
    "# example_paths:",
    "#   source: backend/src/main/java/com/keplerops/groundcontrol/",
    "#   test:   backend/src/test/java/com/keplerops/groundcontrol/",
    "# requirements:",
    "#   uid_examples: [\"GC-X001\", \"OBS-042\"]",
    "# cross_cutting_concerns:",
    "#   description: |",
    "#     Logger: <project's logging library>",
    "#     Validation: <project's validation approach>",
    "#     Errors: <error envelope / handler>",
    "#     Tests: <fixture / test-slice patterns>",
    "# routing:",
    "#   enabled: false",
    "#   # Optional stage/purpose overrides. Omitted stages use the",
    "#   # built-in /implement defaults when routing is enabled.",
    "#   # stages:",
    "#   #   implementation:",
    "#   #     tier: medium",
    "#   #     model: claude-sonnet-5",
    "# telemetry:",
    "#   enabled: false",
    "",
    "# Repo design vocabulary (issue #931). Optional. Codex preflight and the",
    "# pre-push reviewers anchor their architectural_read on this vocabulary",
    "# when present, so 'use the canonical helper' findings name a real helper.",
    "# architecture:",
    "#   vocabulary:",
    "#     patterns:",
    "#       - name: Repository",
    "#         applies_to: data access",
    "#         example_path: backend/src/main/java/.../FooRepository.java",
    "#     canonical_helpers:",
    "#       - name: ErrorResponse",
    "#         path: backend/src/main/java/.../shared/web/ErrorResponse.java",
    "#         purpose: standard error envelope routed via GlobalExceptionHandler",
    "#     boundary_contract:",
    "#       description: api/ → domain/ ← infrastructure/ (ArchUnit-enforced)",
    "#     binding_adrs:",
    "#       - id: ADR-027",
    "#         one_liner: .ground-control.yaml is the agent-neutral context contract",
    "#     anti_recommendations:",
    "#       - Do not introduce new abstractions below 3 call-sites",
    "",
  ].join("\n");
}
// Default fallback-auth file (issue #1500). A launcher — Codex especially, or
// any non-interactive shell — may hand the MCP an environment with NO Claude
// auth (no Vertex/Bedrock vars, no CLAUDE_CONFIG_DIR, no key), so the review
// `claude` falls through to the default profile, which is frequently expired
// and surfaces as `test_quality_review_engine_failed`. This user-owned,
// NON-secret file (`KEY=VALUE` lines) supplies auth in that case.
export const REVIEW_ENGINE_ENV_FALLBACK = join(homedir(), ".config", "ground-control", "review-env");

function hasClaudeAuth(env) {
  return Boolean(
    env.CLAUDE_CODE_USE_VERTEX ||
      env.CLAUDE_CODE_USE_BEDROCK ||
      env.CLAUDE_CONFIG_DIR ||
      env.ANTHROPIC_API_KEY ||
      env.ANTHROPIC_AUTH_TOKEN,
  );
}

// Build the environment for the review engine (`claude`), which runs as a
// separate process from the agent. Two rules keep the review authenticated
// across any repo, folder, tmux session, and launcher (codex or claude):
//   1. If the inherited environment carries no Claude auth at all, load it from
//      REVIEW_ENGINE_ENV_FALLBACK (never overriding a value already present, so
//      an active Vertex or personal mode still wins).
//   2. Strip ANTHROPIC_API_KEY only when another auth path survives, so the key
//      can serve as the sole auth when it is all that is present.
// Parse one `KEY=VALUE` line from a dotenv-style fallback file, stripping a
// single matching quote pair. Returns [key, value] or null for blank/comment/
// malformed lines. Extracted so reviewEngineEnv stays flat (Sonar S3776).
function parseEnvFileLine(line) {
  const trimmed = line.trim();
  if (!trimmed || trimmed.startsWith("#")) return null;
  const eq = trimmed.indexOf("=");
  if (eq <= 0) return null;
  const key = trimmed.slice(0, eq).trim();
  let value = trimmed.slice(eq + 1).trim();
  const quoted = value.length >= 2
    && ((value[0] === '"' && value.at(-1) === '"') || (value[0] === "'" && value.at(-1) === "'"));
  if (quoted) value = value.slice(1, -1);
  return [key, value];
}
export function reviewEngineEnv(baseEnv = process.env, fallbackPath = REVIEW_ENGINE_ENV_FALLBACK) {
  const env = { ...baseEnv };

  if (!hasClaudeAuth(env)) {
    try {
      for (const line of readFileSync(fallbackPath, "utf8").split(/\r?\n/)) {
        const parsed = parseEnvFileLine(line);
        if (parsed && env[parsed[0]] === undefined) env[parsed[0]] = parsed[1];
      }
    } catch {
      // No fallback file, or unreadable: leave the environment as inherited.
    }
  }

  if (env.CLAUDE_CODE_USE_VERTEX || env.CLAUDE_CODE_USE_BEDROCK || env.CLAUDE_CONFIG_DIR) {
    delete env.ANTHROPIC_API_KEY;
  }
  return env;
}
export function resolveWorkflowRouteFromConfig({ routing, stage, tier = null }) {
  if (typeof stage !== "string" || stage.trim() === "") {
    return { ok: false, error: "routing_stage_invalid", message: "stage must be a non-empty string" };
  }
  const normalizedStage = stage.trim();
  if (!ROUTING_STAGE_NAME_RE.test(normalizedStage)) {
    return {
      ok: false,
      error: "routing_stage_invalid",
      message: `stage must match ${ROUTING_STAGE_NAME_RE}`,
      stage: normalizedStage,
    };
  }
  if (routing == null || routing.enabled !== true) {
    return {
      ok: true,
      enabled: false,
      stage: normalizedStage,
      outcome: "disabled",
      message: "routing.enabled is false (or absent) in .ground-control.yaml",
    };
  }
  const configured = routing.stages?.[normalizedStage];
  const defaultStage = DEFAULT_IMPLEMENT_ROUTING_STAGES[normalizedStage];
  const resolvedTier = configured?.tier ?? tier ?? defaultStage?.tier ?? null;
  if (!ROUTING_TIERS.includes(resolvedTier)) {
    return {
      ok: false,
      error: "routing_stage_unconfigured",
      message: `No route is configured for stage '${normalizedStage}' and no valid tier was supplied`,
      stage: normalizedStage,
    };
  }
  const provider = configured?.provider ?? routing.default_provider ?? "claude";
  const model = configured?.model ?? CLAUDE_MODEL_BY_TIER[resolvedTier];
  let source = "tier";
  if (configured) source = "config";
  else if (defaultStage) source = "default";
  return {
    ok: true,
    enabled: true,
    stage: normalizedStage,
    tier: resolvedTier,
    provider,
    model,
    source,
  };
}
export const PR_BODY_CHANGE_CLASSES = Object.freeze(["doc-only", "source", "source+migration"]);
export const PR_REQUIREMENT_RE = /\b[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-[A-Z0-9]*[0-9]\b/;
export const REQUIREMENT_UID_MAX_LENGTH = 50;
export const EXACT_REQUIREMENT_UID_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$/;
export function isRequirementUidToken(token) {
  if (typeof token !== "string" || !EXACT_REQUIREMENT_UID_RE.test(token)) return false;
  const match = token.match(PR_REQUIREMENT_RE);
  return match != null && match[0] === token;
}
export function findRequirementUidTokens(text) {
  if (typeof text !== "string" || text === "") return [];
  // Derived from PR_REQUIREMENT_RE so the shape has exactly one definition; the
  // `g` flag is needed for matchAll and the source is a module-local literal.
  // eslint-disable-next-line security/detect-non-literal-regexp
  const scan = new RegExp(PR_REQUIREMENT_RE.source, "g");
  const found = [];
  for (const match of text.matchAll(scan)) {
    const token = match[0];
    if (isRequirementUidToken(token) && !found.includes(token)) found.push(token);
  }
  return found;
}
export const REQUIREMENT_UID_CONTRACT_DESCRIPTION =
  `a single requirement UID: 1-${REQUIREMENT_UID_MAX_LENGTH} characters, starting with a letter or digit, `
  + "containing only letters, digits, '.', '_', or '-'";
export const PR_BODY_POLICY_CHECK_LINE = "- [x] Configured repository policy command passes";
// Repo-neutral Ground Control Checks (issue #1199): the section attests only
// gates the /implement workflow actually enforces for every repository, named
// semantically. The previous lines named `gc_evaluate_quality_gates` /
// `gc_run_sweep`, tools removed with the #1500 backend teardown. The pre-push
// review gates (code review + test-quality review, Steps 6.5/6.6) run before
// gc_render_pr_body, so this attestation is accurate at render time. Keep this
// byte-identical to tools/policy/authz_matrix.py::check_pr_body's required set —
// the renderer-vs-policy compose fixture is the parity contract.
export const PR_BODY_GC_CHECK_LINES = Object.freeze([
  PR_BODY_POLICY_CHECK_LINE,
  "- [x] Pre-push code review and test-quality review completed; all findings fixed or dispositioned",
]);
const PR_BODY_REQUIRED_HEADERS = Object.freeze([
  "## Requirement UIDs",
  "## ADR Impact",
  "## Ground Control Checks",
  "## Traceability",
]);
const DEFERRAL_TIER1_PATTERNS = Object.freeze([
  /\bdeferred to (?:a |the )?(?:follow[- ]?up|subsequent|later|next)\b/i,
  /\bdefer(?:red)? (?:to |until )?(?:a |the )?(?:follow[- ]?up|subsequent|later iteration)\b/i,
  /\b(?:will be |is |are )?addressed in (?:a |the )?follow[- ]?up\b/i,
  /\b(?:will be |is |are |gets? |get )?(?:fixed|handled|landed?|done) (?:in|as) (?:a |the )?(?:follow[- ]?up|subsequent) (?:PR|issue|pull request)\b/i,
  /\bTBD later\b/i,
  /\bto be (?:done|filed|landed?) (?:later|separately)\b/i,
  /\b(?:not|won'?t|will\s+not|cannot|can'?t|skip(?:ping)?)\s+(?:be\s+)?(?:fix|fixing|address|addressing|repair|repairing|resolve|resolving|handle|handling)\b[^.\n]{0,80}\b(?:because|since|as)\b[^.\n]{0,60}\b(?:pre-existing|unrelated|outside\s+(?:this\s+)?(?:PR'?s?\s+)?scope|out\s+of\s+scope|owned\s+by)\b/i,
  /\b(?:pre-existing|unrelated|outside\s+(?:this\s+)?(?:PR'?s?\s+)?scope|out\s+of\s+scope|owned\s+by[^,.;\n]{0,40})\b[^.\n]{0,80}(?:\b(?:so|therefore|means)\b|[;:])[^.\n]{0,60}\b(?:not|won'?t|will\s+not|skip(?:ping)?|left\s+unresolved|leave\s+unresolved)\b/i,
]);
export function detectDeferralDisposition(text) {
  if (typeof text !== "string" || text === "") return null;
  for (const re of DEFERRAL_TIER1_PATTERNS) {
    const m = text.match(re);
    if (m) return `deferral-disposition phrase '${m[0]}' detected (ADR-029 forbids deferral)`;
  }
  return null;
}
function extractRequirementUidsSection(body) {
  const start = body.indexOf("## Requirement UIDs");
  if (start === -1) return "";
  const after = body.slice(start + "## Requirement UIDs".length);
  const nextHeader = after.search(/\n## /);
  return nextHeader === -1 ? after : after.slice(0, nextHeader);
}
export function extractRequirementUidTokensFromSection(body) {
  if (typeof body !== "string") return [];
  const tokens = [];
  for (const line of extractRequirementUidsSection(body).split(/\r?\n/)) {
    const bullet = line.match(/^\s*[-*+]\s+(.+?)\s*$/);
    if (!bullet) continue;
    if (/^\(none\b/i.test(bullet[1])) continue;
    // The WHOLE bullet must be a single token in the corpus. Scanning a bullet
    // for any corpus-shaped word would count ordinary prose — `- (no real UID
    // here)` contains `no`, a syntactically valid identifier — because the
    // corpus cannot distinguish a UID from a word without a lookup. Requiring
    // the bullet to be exactly one token keeps the gate decidable while still
    // accepting every UID the structured path accepts.
    const candidate = bullet[1].replace(/^[`]+|[`]+$/g, "").trim();
    if (!EXACT_REQUIREMENT_UID_RE.test(candidate)) continue;
    if (!tokens.includes(candidate)) tokens.push(candidate);
  }
  return tokens;
}
export function checkPrBodyShape(body) {
  const errors = [];
  if (typeof body !== "string" || body === "") {
    return { ok: false, errors: ["body must be a non-empty string"] };
  }
  for (const h of PR_BODY_REQUIRED_HEADERS) {
    if (!body.includes(h)) errors.push(`missing required header: ${h}`);
  }
  // Section-scoped UID check — see extractRequirementUidsSection for rationale.
  // The section is machine-rendered one UID per bullet, so it is parsed
  // structurally and each token is held to the identity corpus. That keeps the
  // gate's accepted set exactly equal to what gc_render_pr_body accepts, so a
  // UID that reconciles and reports can always be rendered (issue #1425).
  const uidSection = extractRequirementUidsSection(body);
  const sectionHasUid = extractRequirementUidTokensFromSection(body).length > 0;
  const sectionHasNoneMarker = /-\s*\(none\b/i.test(uidSection);
  if (!sectionHasUid && !sectionHasNoneMarker) {
    errors.push(
      "## Requirement UIDs section must contain at least one Ground Control UID " +
      "(" + REQUIREMENT_UID_CONTRACT_DESCRIPTION + ") OR the explicit '- (none — ...)' " +
      "marker for requirement-free runs. ADR references in other sections do NOT " +
      "satisfy the requirement-UID gate — that is concept confusion between ADR " +
      "impact and requirement traceability.",
    );
  }
  if (!body.includes("ADR-") && !body.includes("No ADR required")) {
    errors.push("ADR Impact must reference an ADR ('ADR-...') or contain 'No ADR required'");
  }
  for (const line of PR_BODY_GC_CHECK_LINES) {
    if (!body.includes(line)) errors.push(`missing Ground Control Checks line: ${line}`);
  }
  if (!body.includes("- IMPLEMENTS:")) errors.push("missing '- IMPLEMENTS:' marker under Traceability");
  if (!body.includes("- TESTS:")) errors.push("missing '- TESTS:' marker under Traceability");
  // NB: deferral-language enforcement is intentionally NOT done here (codex
  // cycle-4 F1). Authoritative enforcement: `block-defer-language.py`
  // PreToolUse hook on `gh pr create` AND `bin/policy` /
  // `check_pr_body::run_no_deferral_disposition_check` at CI time. The JS
  // classifier was a partial subset of the Python `deferral_cases.json`
  // matcher and gave false confidence ("ok:true" from a body that would
  // later fail policy). The structural check (headers / markers / GC checks
  // / UID section) is what this function owns; deferral is owned downstream.
  if (errors.length) return { ok: false, errors };
  return { ok: true };
}

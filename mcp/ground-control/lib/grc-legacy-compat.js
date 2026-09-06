// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { isAbsolute, relative, resolve as resolvePath } from "node:path";

const GRC_SCREENING_MARKER_RE = /<!--\s*gc:grc-screening(?!-data)\s+issue="(\d+)"[^]*?-->/g;
const GRC_SCREENING_DATA_RE = /<!--\s*gc:grc-screening-data\s+(\{[^]*?\})\s*-->/g;
export function parseGrcScreeningData(commentBodies, issueNumber) {
  if (!Array.isArray(commentBodies)) return null;
  let latest = null;
  for (const body of commentBodies) {
    if (typeof body !== "string") continue;
    let hasMarkerForIssue = false;
    GRC_SCREENING_MARKER_RE.lastIndex = 0;
    for (const m of body.matchAll(GRC_SCREENING_MARKER_RE)) {
      if (Number.parseInt(m[1], 10) === issueNumber) {
        hasMarkerForIssue = true;
        break;
      }
    }
    if (!hasMarkerForIssue) continue;
    GRC_SCREENING_DATA_RE.lastIndex = 0;
    for (const dm of body.matchAll(GRC_SCREENING_DATA_RE)) {
      try {
        latest = JSON.parse(dm[1]); // last match wins within a body
      } catch {
        // malformed JSON — skip this block
      }
    }
  }
  return latest;
}
export function evaluatePhasePrerequisite({ completed, nextPhase, requires, issueNumber }) {
  if (!(completed instanceof Set)) {
    throw new Error("evaluatePhasePrerequisite: completed must be a Set");
  }
  if (typeof nextPhase !== "string" || nextPhase === "") {
    throw new Error("evaluatePhasePrerequisite: nextPhase must be a non-empty string");
  }
  const required = Array.isArray(requires) ? requires : [];
  const missing = required.filter((p) => !completed.has(p));
  if (missing.length > 0) {
    return {
      ok: false,
      error: "phase_prerequisite_missing",
      next_phase: nextPhase,
      missing,
      completed: [...completed],
      issue_number: issueNumber,
      message:
        `Cannot enter phase '${nextPhase}' for issue #${issueNumber}: prerequisite phase(s) ` +
        `[${missing.join(", ")}] have not been recorded. Run them first; then retry.`,
    };
  }
  return { ok: true, next_phase: nextPhase };
}
export function buildPhaseMarker({ phase, issueNumber }) {
  return [
    `<!-- gc:phase phase="${phase}" issue="${issueNumber}" -->`,
    "",
    `_gc workflow phase recorded: \`${phase}\` (issue #${issueNumber})._ ` +
      "Posted by the MCP server to enforce ordering between workflow steps (issue #794 MVP-2). " +
      "Do not edit or delete — used by downstream tools to gate phase prerequisites.",
  ].join("\n");
}
function normalizeMarkdownHeadingText(lineText) {
  return lineText.replace(/\s+#+\s*$/, "").trim().toLowerCase();
}
export function extractMarkdownHeadingSection(body, sectionName) {
  const target = sectionName.trim().toLowerCase();
  const lines = body.split(/\r?\n/);
  let start = -1;
  let level = null;
  for (let i = 0; i < lines.length; i += 1) {
    const m = lines[i].match(/^(#{1,6})\s+(.+?)\s*$/);
    if (!m) continue;
    if (normalizeMarkdownHeadingText(m[2]) === target) {
      start = i;
      level = m[1].length;
      break;
    }
  }
  if (start === -1) return null;
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i += 1) {
    const m = lines[i].match(/^(#{1,6})\s+(.+?)\s*$/);
    if (m && m[1].length <= level) {
      end = i;
      break;
    }
  }
  return lines.slice(start + 1, end).join("\n");
}
function normalizeDevStartFieldLabel(label) {
  return label.replace(/\s+/g, " ").trim().toLowerCase();
}
export function parseDevStartGateFields(sectionBody) {
  const fields = new Map();
  const lines = sectionBody.split(/\r?\n/);
  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (line === "") continue;
    const match =
      line.match(/^(?:[-*]\s*)?\*\*(.+?):\*\*\s*(.*)$/) ||
      line.match(/^(?:[-*]\s*)?\*\*(.+?)\*\*\s*:\s*(.*)$/) ||
      line.match(/^(?:[-*]\s*)?([^:]+):\s*(.*)$/);
    if (!match) continue;
    const label = match[1].replace(/\*\*/g, "").trim();
    const value = match[2].trim();
    const key = normalizeDevStartFieldLabel(label);
    if (!fields.has(key)) fields.set(key, { label, value });
  }
  return fields;
}
export function devStartFieldValue(fields, label) {
  return fields.get(normalizeDevStartFieldLabel(label))?.value ?? null;
}
export function isConcreteDevStartValue(value, { allowBareNotApplicable = false } = {}) {
  if (typeof value !== "string") return false;
  const trimmed = value.trim();
  if (trimmed === "") return false;
  const lower = trimmed.toLowerCase();
  if (["tbd", "todo", "yes/no", "y/n", "unknown", "-", "_"].includes(lower)) return false;
  if (!allowBareNotApplicable && /^(?:n\/a|na|none|not[- ]?applicable)$/i.test(trimmed)) return false;
  if (/^<[^>]+>$/.test(trimmed) || /^\[[^\]]+\]$/.test(trimmed)) return false;
  return true;
}
function parseDevStartRiskTotal(value) {
  if (typeof value !== "string") return null;
  const match = value.match(/\btotal\s*=\s*(\d+)\b/i);
  if (!match) return null;
  const total = Number.parseInt(match[1], 10);
  if (!Number.isInteger(total)) return null;
  return total;
}
export function devStartGateFailure({ planSection, missing, invalid, sourceBearing = null }) {
  const parts = [];
  if (missing.length) parts.push(`missing or non-concrete field(s): ${missing.join(", ")}`);
  if (invalid.length) parts.push(`invalid field(s): ${invalid.join(", ")}`);
  return {
    ok: false,
    checked: true,
    error: "dev_start_gate_invalid",
    message: `Plan is missing a valid ## ${planSection} section: ${parts.join("; ")}`,
    plan_section: planSection,
    source_bearing: sourceBearing,
    missing,
    invalid,
  };
}
export function devStartGateConfigFailure(errors) {
  return {
    ok: false,
    checked: false,
    error: "dev_start_gate_config_invalid",
    message: errors.join("; "),
    missing: [],
    invalid: errors,
  };
}
export function devStartGateSuccess({ sourceBearing, planSection, riskTotal = null }) {
  const result = {
    ok: true,
    checked: true,
    source_bearing: sourceBearing,
    plan_section: planSection,
  };
  if (riskTotal != null) result.risk_score_total = riskTotal;
  return result;
}
export function readDevStartPlanFields(planBody, config) {
  if (typeof planBody !== "string" || planBody.trim() === "") {
    return devStartGateFailure({
      planSection: config.plan_section,
      missing: ["plan body"],
      invalid: [],
    });
  }
  const sectionBody = extractMarkdownHeadingSection(planBody, config.plan_section);
  if (sectionBody == null) {
    return devStartGateFailure({
      planSection: config.plan_section,
      missing: [`## ${config.plan_section}`],
      invalid: [],
    });
  }
  return { ok: true, fields: parseDevStartGateFields(sectionBody) };
}
export function readSourceBearingDecision(fields) {
  const missing = [];
  const invalid = [];
  const sourceRaw = devStartFieldValue(fields, "Source-bearing");
  if (!isConcreteDevStartValue(sourceRaw)) missing.push("Source-bearing");
  const source = typeof sourceRaw === "string" ? sourceRaw.trim().toLowerCase() : null;
  if (source && !["yes", "no"].includes(source)) invalid.push("Source-bearing must be 'yes' or 'no'");
  return {
    ok: missing.length === 0 && invalid.length === 0,
    sourceBearing: source === "yes",
    missing,
    invalid,
  };
}
export function validateNonSourceDevStartGate(fields, config) {
  const rationale =
    devStartFieldValue(fields, "Non-source rationale") ??
    devStartFieldValue(fields, "Source-bearing rationale");
  if (isConcreteDevStartValue(rationale)) {
    return devStartGateSuccess({ sourceBearing: false, planSection: config.plan_section });
  }
  return devStartGateFailure({
    planSection: config.plan_section,
    missing: ["Non-source rationale"],
    invalid: [],
    sourceBearing: false,
  });
}
export function missingDevStartRequiredFields(fields, requiredFields) {
  return requiredFields.filter((field) => !isConcreteDevStartValue(devStartFieldValue(fields, field)));
}
export function readDevStartRiskTotal(fields) {
  const riskScore = devStartFieldValue(fields, "Verification risk score");
  if (!isConcreteDevStartValue(riskScore)) return { riskTotal: null, invalid: null };
  const riskTotal = parseDevStartRiskTotal(riskScore);
  if (riskTotal == null || riskTotal < 0 || riskTotal > 6) {
    return {
      riskTotal: null,
      invalid: "Verification risk score must include total=<0..6>",
    };
  }
  return { riskTotal, invalid: null };
}
export function collectDevStartBlockerFailures(fields, blockerUids) {
  const missing = [];
  const invalid = [];
  for (const uid of blockerUids) {
    const label = `${uid} applicability`;
    const value = devStartFieldValue(fields, label);
    if (!isConcreteDevStartValue(value, { allowBareNotApplicable: true })) {
      missing.push(label);
      continue;
    }
    if (!/^(?:applies|not[- ]applicable|not applicable)\b/i.test(value.trim())) {
      invalid.push(`${label} must start with applies, not-applicable, or not applicable`);
    }
  }
  return { missing, invalid };
}
export const DEFAULT_CODEX_REVIEW_MAX_DIFF_BYTES = (() => {
  const raw = Number.parseInt(process.env.GC_CODEX_REVIEW_MAX_DIFF_BYTES || "", 10);
  if (!Number.isInteger(raw)) return 256 * 1024; // 256 KiB
  return raw;
})();
export function buildDiffBlock({
  diffText,
  mode = "inline",
  manifest = null,
  baseRefDescriptor = null,
  slice = null,
}) {
  const diffLines =
    !diffText || diffText.trim() === ""
      ? ["<<<DIFF", "(empty diff — nothing changed against the base branch)", "DIFF>>>"]
      : ["<<<DIFF", diffText, "DIFF>>>"];
  if (mode !== "manifest") return diffLines;

  const against = baseRefDescriptor ? ` (against \`${baseRefDescriptor}\`)` : "";
  const sliceOf =
    slice && Number.isInteger(slice.total) && slice.total > 1
      ? ` The remaining ${slice.total - 1} slice(s) are reviewed separately inside this same review cycle, so leave files outside this slice to those slices.`
      : "";
  return [
    "<<<DIFF-MANIFEST",
    manifest && manifest.trim() !== "" ? manifest : "(empty manifest)",
    "DIFF-MANIFEST>>>",
    "",
    `The manifest above lists every file in the complete change${against} with its added/deleted line counts, followed by a block stating each file's change kind (\`A\` added, \`D\` deleted, \`M\` modified, \`R\` renamed). It is CONTEXT ONLY — never infer content or behavior from a filename or a numstat row. The change kind is stated, so read it rather than guessing the direction of a change from line counts: a deleted file and an emptied one produce the same numstat row.${sliceOf}`,
    "",
    ...diffLines,
  ];
}
export const CODEX_FINDING_FIELDS_DESCRIPTION = [
  "    `path`   — repo-relative file path (string, no leading `/`, no `..` segments).",
  "    `line`   — line number in the new (RIGHT) side of the diff, as a positive integer. File-level comments are not yet supported; anchor every finding to a specific line in the diff.",
  "    `title`  — one-line summary, ≤200 characters, non-empty.",
  "    `body`   — detailed explanation, ≤65322 characters, non-empty. Self-contained — do NOT reference 'see above'. Do NOT paste full file contents, secret values, or environment variables into the body.",
  '    `classification` — exactly "one-off" or "class".',
  "    `sweep_evidence` — REQUIRED when classification is \"one-off\". One-line statement of what you swept and what you did NOT find (e.g. \"grepped for `*Repository` calls across `backend/src/main` — 12 sites, all use the scoped helper; this site is the only bypass\"). Forbidden when classification is \"class\" (class findings document evidence via category.instances).",
  '    `category` — REQUIRED when classification is "class"; forbidden when "one-off". Object: `shape` (≤300 chars, the pattern), `instances` (non-empty array of "<path>:<line>" strings including this finding\'s own site).',
  "    `structural_blocker` — optional boolean. Set to true on a one-off finding that warrants verdict=don't-ship (e.g. missing security boundary at a unique site). Implicit on class findings.",
].join("\n");
export const CODEX_CORE_FINDING_EXAMPLE = '{"path":"backend/src/main/java/com/keplerops/groundcontrol/api/foo/FooController.java","line":42,"title":"Bypasses canonical ErrorResponse envelope","body":"Returns ResponseEntity<String> with a hand-rolled JSON shape instead of routing through GlobalExceptionHandler + ErrorResponse. Three call-sites in this diff do the same — fix at GlobalExceptionHandler not site-by-site.","classification":"class","category":{"shape":"controller method returning ResponseEntity<String> for error cases instead of throwing through GlobalExceptionHandler","instances":["backend/src/main/java/com/keplerops/groundcontrol/api/foo/FooController.java:42","backend/src/main/java/com/keplerops/groundcontrol/api/bar/BarController.java:55","backend/src/main/java/com/keplerops/groundcontrol/api/baz/BazController.java:88"]}}';
export const CODEX_SECURITY_FINDING_EXAMPLE = '{"path":"deploy/scripts/sync.sh","line":99,"title":"Bearer token in curl argv","body":"Attacker model: other local users on the runner. Path: token is interpolated into curl -H argv; readable via /proc/<pid>/cmdline. Fix: pass through --config <(printf ...) or env-only header.","classification":"one-off","sweep_evidence":"grepped \\"curl -H\\" across deploy/scripts — 4 sites, 3 use --config; this site is the only one putting a secret in argv.","structural_blocker":true}';
export function buildCodexReviewExecArgs({ repoPath, outputPath }) {
  return [
    "exec",
    "--sandbox",
    "read-only",
    "-C",
    repoPath,
    "--output-last-message",
    outputPath,
    "-",
  ];
}
export const FINDING_TITLE_MAX = 200;
const FINDING_PREFIX_MAX = 213; // `[security] ` (11) + 200-char title + `\n\n` (2)
export const FINDING_CLASSIFICATION_NOTE_MAX = 800;
export const FINDING_BODY_MAX = 65535 - FINDING_PREFIX_MAX - FINDING_CLASSIFICATION_NOTE_MAX;
export const FINDING_CATEGORY_SHAPE_MAX = 300;
export const FINDING_CLASSIFICATIONS = new Set(["one-off", "class"]);
export const FINDING_SWEEP_EVIDENCE_MAX = 500;
export const REVIEW_NOTE_TEXT_MAX = 300;
export function truncateReviewProse(text, max) {
  if (text.length <= max) return text;
  return text.slice(0, max - 1) + "…";
}
export const CODEX_REVIEW_TAIL_RE = /===REVIEW===\s*\n([\s\S]*?)\n===END===\s*$/;
export function validateFindingPath(rawPath, repoRoot) {
  if (typeof rawPath !== "string" || rawPath.trim() === "") {
    throw new Error("finding path must be a non-empty string");
  }
  if (isAbsolute(rawPath)) {
    throw new Error(`finding path must be a repo-relative path (got absolute path '${rawPath}')`);
  }
  // Lexical reject on any `..` segment. Splitting on both `/` and `\` covers
  // POSIX and Windows-style separators in case codex emits either.
  const segments = rawPath.split(/[/\\]/);
  if (segments.some((seg) => seg === "..")) {
    throw new Error(`finding path must not contain '..' segments (got '${rawPath}')`);
  }
  const abs = resolvePath(repoRoot, rawPath);
  const rel = relative(repoRoot, abs);
  if (rel === "" || rel.startsWith("..") || isAbsolute(rel)) {
    throw new Error(`finding path must stay inside the repository root (got '${rawPath}')`);
  }
  return rawPath;
}
export function checkVerdictBlockingConsistency({ verdict, blocking, blockingHasStructural }) {
  const errs = [];
  if (verdict === "ship" && blocking.length > 0) {
    errs.push(
      `verdict='ship' is inconsistent with non-empty blocking[] (${blocking.length}). Choose ship-with-fixes when blockers are present.`,
    );
  }
  if (verdict !== "ship" && blocking.length === 0) {
    errs.push(
      `verdict='${verdict}' requires non-empty blocking[]. A clean review must use verdict='ship'.`,
    );
  }
  if (verdict === "don't-ship") {
    const hasStructural = blocking.some(blockingHasStructural);
    if (!hasStructural) {
      errs.push(
        "verdict='don't-ship' requires at least one structural blocker — either a class finding or a one-off with structural_blocker=true (preflight rule).",
      );
    }
  }
  return errs;
}

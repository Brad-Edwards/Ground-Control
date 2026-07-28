// Gate-finding source adapters (issue #1355, ADR-090).
//
// Each gate already produces machine-readable output. These adapters map that output to the
// bounded measurement projection defined by contracts/schemas/measurement/gate-finding.v1.schema.json
// and nothing else. They are pure: parsing lives here, execution stays at the gate's own boundary,
// and no adapter runs, re-runs, or interprets a gate.
//
// Boundaries every adapter respects:
//   - Bounded facts only. No title, body, message, remediation text, file path, line number, raw
//     tool output, or stack trace reaches a record. The ADR-029 issue thread stays the narrative.
//   - Source-native category and severity, preserved exactly. A source that does not express one
//     omits the field; absence is a fact, and a guessed severity would fabricate a distribution.
//   - Deterministic identity. A retry of the same attempt must produce the same keys, or the
//     append-only projection would count one finding twice.
//   - Detection is not disposition. Every newly observed finding is `open`; a terminal disposition
//     only ever arrives from a later tool-layer boundary that can attest it.

import { createHash } from "node:crypto";

/** Newly detected findings are open. `fixed` is never inferred from detection. */
export const FINDING_DISPOSITION_OPEN = "open";

/** Reviewers exercise judgement; detectors run a rule set. A gate is never given a fake reviewer. */
export const SOURCE_KIND_REVIEWER = "reviewer";
export const SOURCE_KIND_DETECTOR = "detector";

/**
 * Upper bound on findings persisted for one attempt. A gate that produces more is a signal in its
 * own right, but an unbounded batch would let one pathological run dominate the store. The overflow
 * is reported by the caller as a count, never silently dropped.
 */
export const MAX_FINDINGS_PER_ATTEMPT = 500;

const MAX_CATEGORY_LENGTH = 300;
const MAX_SEVERITY_LENGTH = 60;
const MAX_SOURCE_ID_LENGTH = 100;

/** Bounded, non-empty string or undefined. Undefined means "the source did not express this". */
function bounded(value, maxLength) {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  if (trimmed === "") return undefined;
  return trimmed.length <= maxLength ? trimmed : trimmed.slice(0, maxLength);
}

/**
 * Opaque deterministic identity derived from bounded structural fields.
 *
 * Path and line participate in the digest but never survive it: the key must distinguish two
 * findings of the same rule at different sites, and hashing is what lets it do that without
 * persisting a source location. Never keyed on prose, array position, or a timestamp — all three
 * differ between a live observation and its reconciliation, which is exactly when the two must
 * converge to one row.
 */
function deriveFindingKey(...parts) {
  const digest = createHash("sha256");
  for (const part of parts) {
    digest.update(part == null ? "\u0000" : String(part));
    digest.update("\u001f");
  }
  return digest.digest("hex").slice(0, 32);
}

/** Assemble one record, omitting every dimension the source could not attest. */
function findingRecord({ findingKey, sourceKind, sourceId, category, severity, classification }) {
  return {
    findingKey,
    sourceKind,
    sourceId,
    ...(category ? { category } : {}),
    ...(severity ? { severity } : {}),
    ...(classification ? { classification } : {}),
    disposition: FINDING_DISPOSITION_OPEN,
  };
}

/**
 * Deduplicate by key and apply the batch bound.
 *
 * Two sources legitimately report the same finding key only when they are the same finding, so the
 * first record wins rather than both being appended.
 */
function collect(records) {
  const byKey = new Map();
  for (const record of records) {
    if (record && !byKey.has(record.findingKey)) byKey.set(record.findingKey, record);
  }
  const all = [...byKey.values()];
  return {
    findings: all.slice(0, MAX_FINDINGS_PER_ATTEMPT),
    dropped: Math.max(0, all.length - MAX_FINDINGS_PER_ATTEMPT),
  };
}

// ---------------------------------------------------------------------------
// Reviewers — codex core/security and test-quality
// ---------------------------------------------------------------------------

/**
 * Map validated review-envelope findings to records.
 *
 * Severity is deliberately absent: the review envelope has no severity field, and ADR-031's
 * proposed one is not an implemented source contract. Recording `unobserved` coverage beats
 * inventing a level from the reviewer's identity or the finding's title.
 *
 * A one-off finding has no recurring category shape, so its category is omitted rather than
 * filled with a synthetic "uncategorized" bucket that would pollute recurrence aggregates.
 *
 * @param {object[]} findings  Findings from the review envelope (`comments` or `findings`)
 * @param {string} defaultReviewer  Reviewer id used when a finding carries no per-finding label
 */
export function reviewGateFindings(findings, defaultReviewer) {
  const arr = Array.isArray(findings) ? findings : [];
  return collect(
    arr.map((finding) => {
      if (!finding || typeof finding !== "object") return null;
      const sourceId =
        bounded(finding.reviewer, MAX_SOURCE_ID_LENGTH)
        ?? bounded(defaultReviewer, MAX_SOURCE_ID_LENGTH);
      if (!sourceId) return null;
      const classification = finding.classification === "class" ? "class" : "one-off";
      const category =
        classification === "class" ? bounded(finding?.category?.shape, MAX_CATEGORY_LENGTH) : undefined;
      return findingRecord({
        findingKey: deriveFindingKey(
          "review",
          sourceId,
          classification,
          category ?? "",
          finding.path,
          finding.line,
          bounded(finding.title, MAX_CATEGORY_LENGTH),
        ),
        sourceKind: SOURCE_KIND_REVIEWER,
        sourceId,
        category,
        classification,
      });
    }),
  );
}

// ---------------------------------------------------------------------------
// Detectors
// ---------------------------------------------------------------------------

/**
 * SonarCloud issues and hotspots.
 *
 * Sonar assigns every issue a stable server-side key, so identity is taken rather than derived.
 * Hotspots are a distinct inspection with their own vocabulary; their vulnerability probability is
 * kept in its own namespace instead of being flattened into the issue severity scale, which would
 * merge two different ordinal systems into one meaningless distribution.
 */
export function sonarGateFindings(issues, hotspots) {
  const issueRecords = (Array.isArray(issues) ? issues : []).map((issue) => {
    if (!issue || typeof issue !== "object") return null;
    const rule = bounded(issue.rule, MAX_CATEGORY_LENGTH) ?? bounded(issue.type, MAX_CATEGORY_LENGTH);
    return findingRecord({
      findingKey:
        bounded(issue.key, 200) ?? deriveFindingKey("sonar-issue", rule, issue.component, issue.line),
      sourceKind: SOURCE_KIND_DETECTOR,
      sourceId: "sonarcloud",
      category: rule,
      severity: bounded(issue.severity, MAX_SEVERITY_LENGTH),
    });
  });

  const hotspotRecords = (Array.isArray(hotspots) ? hotspots : []).map((hotspot) => {
    if (!hotspot || typeof hotspot !== "object") return null;
    return findingRecord({
      findingKey:
        bounded(hotspot.key, 200)
        ?? deriveFindingKey("sonar-hotspot", hotspot.securityCategory, hotspot.component, hotspot.line),
      sourceKind: SOURCE_KIND_DETECTOR,
      sourceId: "sonarcloud",
      category: bounded(hotspot.securityCategory, MAX_CATEGORY_LENGTH) ?? "security_hotspot",
      severity: bounded(hotspot.vulnerabilityProbability, MAX_SEVERITY_LENGTH),
    });
  });

  return collect([...issueRecords, ...hotspotRecords]);
}

/** `<BugInstance type="..." priority="..." rank="..." category="...">` from the SpotBugs XML report. */
const SPOTBUGS_BUG_INSTANCE_RE = /<BugInstance\b([^>]*)>/g;
const SPOTBUGS_SOURCE_LINE_RE = /<SourceLine\b[^>]*\bsourcepath="([^"]*)"[^>]*(?:\bstart="(\d+)")?/;

function xmlAttribute(attributes, name) {
  const match = new RegExp(`\\b${name}="([^"]*)"`).exec(attributes);
  return match ? match[1] : undefined;
}

/**
 * SpotBugs bug instances, read from the XML report the completion command already wrote.
 *
 * The report is parsed rather than the console transcript: a combined Gradle log cannot be split
 * into per-gate facts without guessing, and re-running SpotBugs to measure it would execute a
 * canonical gate twice. Rank and priority are SpotBugs' own ordinals and are kept as it expressed
 * them.
 */
export function spotbugsGateFindings(reportXml) {
  if (typeof reportXml !== "string" || reportXml === "") return collect([]);
  const records = [];
  for (const match of reportXml.matchAll(SPOTBUGS_BUG_INSTANCE_RE)) {
    const attributes = match[1] ?? "";
    const pattern = bounded(xmlAttribute(attributes, "type"), MAX_CATEGORY_LENGTH);
    if (!pattern) continue;
    const instanceHash = bounded(xmlAttribute(attributes, "instanceHash"), 200);
    const tail = reportXml.slice(match.index, match.index + 2000);
    const sourceLine = SPOTBUGS_SOURCE_LINE_RE.exec(tail);
    records.push(
      findingRecord({
        // instanceHash is SpotBugs' own stable identity for a bug instance; it survives
        // reformatting that would move a line number, so it is preferred when present.
        findingKey:
          instanceHash
          ?? deriveFindingKey("spotbugs", pattern, sourceLine?.[1], sourceLine?.[2]),
        sourceKind: SOURCE_KIND_DETECTOR,
        sourceId: "spotbugs",
        category: pattern,
        severity: bounded(xmlAttribute(attributes, "rank"), MAX_SEVERITY_LENGTH),
      }),
    );
  }
  return collect(records);
}

/**
 * Vale results, from `--output=JSON`: `{ "<file>": [ { Check, Severity, Line, Message } ] }`.
 *
 * The check name is the stable category; the message is prose and never persisted.
 */
export function valeGateFindings(valeJson) {
  if (!valeJson || typeof valeJson !== "object" || Array.isArray(valeJson)) return collect([]);
  const records = [];
  for (const [file, alerts] of Object.entries(valeJson)) {
    for (const alert of Array.isArray(alerts) ? alerts : []) {
      if (!alert || typeof alert !== "object") continue;
      const check = bounded(alert.Check, MAX_CATEGORY_LENGTH);
      if (!check) continue;
      records.push(
        findingRecord({
          findingKey: deriveFindingKey("vale", check, file, alert.Line, alert.Span?.[0]),
          sourceKind: SOURCE_KIND_DETECTOR,
          sourceId: "vale",
          category: check,
          severity: bounded(alert.Severity, MAX_SEVERITY_LENGTH),
        }),
      );
    }
  }
  return collect(records);
}

/**
 * Repo policy violations, from `bin/policy --json`.
 *
 * `Violation.code` is the stable category. Policy expresses no severity — every violation is
 * blocking — so the field is omitted rather than given an invented level. Detail lines participate
 * in the key so two violations of the same code at different sites stay distinct, but they are
 * hashed rather than stored: details routinely carry repository paths.
 */
export function policyGateFindings(policyJson) {
  const violations = Array.isArray(policyJson?.violations) ? policyJson.violations : [];
  return collect(
    violations.map((violation) => {
      if (!violation || typeof violation !== "object") return null;
      const code = bounded(violation.code, MAX_CATEGORY_LENGTH);
      if (!code) return null;
      const details = Array.isArray(violation.details) ? violation.details.join("") : "";
      return findingRecord({
        findingKey: deriveFindingKey("policy", code, details),
        sourceKind: SOURCE_KIND_DETECTOR,
        sourceId: "policy",
        category: code,
      });
    }),
  );
}

/** Conclusions that mean the CI run did not pass. `skipped` and `neutral` are not failures. */
const CI_FAILING_CONCLUSIONS = new Set([
  "failure",
  "timed_out",
  "cancelled",
  "action_required",
  "startup_failure",
  "queued_too_long",
]);

/**
 * Failed CI steps, from the run envelope's own `failed_steps` export.
 *
 * `job/step` is the category — CI publishes no rule vocabulary, and its step conclusions are the
 * only structured verdict it exposes. The log summary is prose and is never parsed: guessing at a
 * failure category from log text would invent a taxonomy CI does not have.
 *
 * A run that failed without extractable steps (startup failure, timeout, queued too long) still
 * rendered a verdict, so its conclusion is recorded as one finding. Otherwise those runs would
 * report a failing gate with zero findings and read as unexplained.
 */
export function ciGateFindings(ciResult) {
  const steps = Array.isArray(ciResult?.failed_steps) ? ciResult.failed_steps : [];
  const records = steps
    .map((step) => {
      if (!step || typeof step !== "object") return null;
      const job = bounded(step.job_name, MAX_CATEGORY_LENGTH);
      const name = bounded(step.step_name, MAX_CATEGORY_LENGTH);
      const category = bounded([job, name].filter(Boolean).join("/"), MAX_CATEGORY_LENGTH);
      if (!category) return null;
      return findingRecord({
        findingKey: deriveFindingKey("ci", category),
        sourceKind: SOURCE_KIND_DETECTOR,
        sourceId: "ci",
        category,
        severity: "failure",
      });
    })
    .filter(Boolean);

  const conclusion = bounded(ciResult?.conclusion, MAX_SEVERITY_LENGTH);
  if (records.length === 0 && conclusion && CI_FAILING_CONCLUSIONS.has(conclusion)) {
    records.push(
      findingRecord({
        findingKey: deriveFindingKey("ci-run", conclusion),
        sourceKind: SOURCE_KIND_DETECTOR,
        sourceId: "ci",
        category: conclusion,
        severity: conclusion,
      }),
    );
  }

  return collect(records);
}

// gc_workflow_run_ingest: bridge ingestion from GitHub issue thread.
//
// Reads a GitHub issue thread via runGetIssueThread (reuses the existing
// cached gh-api client — no new GitHub client introduced), parses ONLY
// canonical gc: markers (gc:phase / gc:final-report / gc:grc-screening),
// derives a workflow run record plus phase events from the parsed state,
// and POSTs them to the backend with provenance="ISSUE_THREAD".
//
// Security constraints:
//   - Trust ONLY canonical HTML-comment markers (gc:phase, gc:final-report,
//     gc:grc-screening). Marker-shaped plain text that does not parse as a
//     valid HTML comment is counted as a bounded skip and never forwarded.
//   - Never store or forward: prompts, completions, raw comment bodies,
//     reviewer prose, bearer tokens, or any free-form text.
//   - The closed ingest field set is: repo, issue_number, branch,
//     workflow_type, requirement_uids[], started_at, ended_at, final_state,
//     outcome, provenance, phases[]. No PII, no payload text.

import { z } from "zod";
import {
  runGetIssueThread,
  parsePhaseMarkers,
  parseGrcScreeningData,
  createWorkflowRun,
  recordWorkflowRunEvent,
  EXACT_REQUIREMENT_UID_RE,
} from "./lib.js";

// ---------------------------------------------------------------------------
// Canonical marker regexes
// ---------------------------------------------------------------------------

// gc:final-report — matches: <!-- gc:final-report issue="N" pr="M" -->
// Only the exact HTML-comment shape is accepted. Tolerates extra whitespace.
const FINAL_REPORT_INGEST_RE =
  /<!--\s*gc:final-report\s+issue="(\d+)"\s+pr="(\d+)"[^>]*-->/g;

// gc:grc-screening — already exported by lib.js as parseGrcScreeningData;
// the regex here is a lightweight presence-check so we can count skips without
// re-implementing the full parser.
const GRC_SCREENING_PRESENCE_RE =
  /<!--\s*gc:grc-screening(?!-data)\s+issue="(\d+)"[^>]*-->/g;

// Marker-shaped text that is NOT an HTML comment (common LLM hallucination:
// plain text "gc:phase phase=..."). Used only for skip counting.
const FORGED_MARKER_SHAPE_RE =
  /(?:^|\s)gc:(?:phase|final-report|grc-screening)\s+/g;

// ---------------------------------------------------------------------------
// Pure helpers (fully testable without I/O)
// ---------------------------------------------------------------------------

/**
 * Count HTML-comment markers that LOOK like gc: markers but fail to match
 * any canonical pattern. Used to build the bounded skip count.
 *
 * A "malformed" marker: starts with `<!--` and contains `gc:phase`,
 * `gc:final-report`, or `gc:grc-screening` but does NOT match the strict
 * canonical attribute pattern for any family.
 */
export function countMalformedMarkers(commentBodies) {
  // Canonical patterns (same REs as the parsers use, but reset each call).
  const phaseOk = /<!--\s*gc:phase\s+phase="[a-z_]+"\s+issue="\d+"[^>]*-->/g;
  const finalOk = /<!--\s*gc:final-report\s+issue="\d+"\s+pr="\d+"[^>]*-->/g;
  const grcOk = /<!--\s*gc:grc-screening(?!-data)\s+issue="\d+"[^>]*-->/g;
  // Any HTML comment that contains a gc: family keyword
  const anyGc = /<!--[^>]*gc:(?:phase|final-report|grc-screening)(?!-data)[^>]*-->/g;

  if (!Array.isArray(commentBodies)) return 0;
  let malformed = 0;
  for (const body of commentBodies) {
    if (typeof body !== "string") continue;
    const allGc = Array.from(body.matchAll(anyGc));
    const okPhase = (body.match(phaseOk) ?? []).length;
    const okFinal = (body.match(finalOk) ?? []).length;
    const okGrc = (body.match(grcOk) ?? []).length;
    const totalOk = okPhase + okFinal + okGrc;
    if (allGc.length > totalOk) malformed += allGc.length - totalOk;
  }
  return malformed;
}

/**
 * Extract requirement UIDs from the issue body. Scans the `## Requirements`
 * section (if present) first, then falls back to a full-body scan. Returns
 * only strings matching EXACT_REQUIREMENT_UID_RE (one-UID-per-token).
 * Deduplicates; order preserved (first-seen wins).
 *
 * This is a structural extraction, not free-form text forwarding: only valid
 * UID tokens are emitted.
 */
export function extractRequirementUids(issueBody) {
  if (typeof issueBody !== "string" || issueBody.length === 0) return [];
  // Try to narrow scope to the Requirements section first.
  const lines = issueBody.split(/\r?\n/);
  let inSection = false;
  let sectionLevel = null;
  const sectionLines = [];
  for (const line of lines) {
    const hm = line.match(/^(#{1,6})\s+(.+?)\s*$/);
    if (hm) {
      if (!inSection) {
        if (hm[2].trim().toLowerCase() === "requirements") {
          inSection = true;
          sectionLevel = hm[1].length;
        }
      } else if (hm[1].length <= sectionLevel) {
        break;
      } else {
        sectionLines.push(line);
      }
    } else if (inSection) {
      sectionLines.push(line);
    }
  }
  const scanText = sectionLines.length > 0 ? sectionLines.join("\n") : issueBody;

  const seen = new Set();
  const result = [];
  // Match UID-shaped tokens (word-boundary anchored via lookahead/behind in
  // the global scan, then validate with EXACT_REQUIREMENT_UID_RE).
  const uidRe = /\b[A-Z][A-Z0-9]+-[A-Z0-9]+(?:-\d+|\d+)\b/g;
  for (const m of scanText.matchAll(uidRe)) {
    const token = m[0];
    if (EXACT_REQUIREMENT_UID_RE.test(token) && !seen.has(token)) {
      seen.add(token);
      result.push(token);
    }
  }
  return result;
}

/**
 * Derive a WorkflowType enum value from the issue labels or title heuristics.
 * Returns "OTHER" when no strong signal is present — never null, so the
 * backend always receives a valid workflowType.
 *
 * Only the closed enum vocabulary is returned; no free-form strings.
 */
export function deriveWorkflowType(labels, issueTitle) {
  if (!Array.isArray(labels)) labels = [];
  const labelSet = new Set(labels.map((l) => (typeof l === "string" ? l.toLowerCase() : "")));
  const title = typeof issueTitle === "string" ? issueTitle.toLowerCase() : "";

  if (labelSet.has("quickfix")) return "QUICKFIX";
  if (labelSet.has("grc") || labelSet.has("grc-review")) return "GRC_REVIEW";
  if (labelSet.has("preflight") || title.includes("preflight")) return "PREFLIGHT";
  if (labelSet.has("implement") || labelSet.has("feature") || labelSet.has("enhancement"))
    return "IMPLEMENT";
  if (labelSet.has("codex-review") || labelSet.has("review")) return "CODEX_REVIEW";
  if (labelSet.has("test") || labelSet.has("test-quality")) return "TEST_QUALITY_REVIEW";
  // Title heuristics (case-insensitive)
  if (title.includes("quickfix") || title.includes("quick fix")) return "QUICKFIX";
  if (title.includes("grc")) return "GRC_REVIEW";
  // Default: treat all undifferentiated issues as IMPLEMENT (the dominant workflow)
  return "IMPLEMENT";
}

/**
 * Parse the final-report marker for a given issue number from a list of
 * comment bodies.  Returns { found: true, prNumber } or { found: false }.
 *
 * Only the canonical HTML-comment shape is accepted.
 */
export function parseFinalReportMarker(commentBodies, issueNumber) {
  if (!Array.isArray(commentBodies)) return { found: false };
  for (const body of commentBodies) {
    if (typeof body !== "string") continue;
    FINAL_REPORT_INGEST_RE.lastIndex = 0;
    for (const m of body.matchAll(FINAL_REPORT_INGEST_RE)) {
      if (Number.parseInt(m[1], 10) === issueNumber) {
        return { found: true, prNumber: Number.parseInt(m[2], 10) };
      }
    }
  }
  return { found: false };
}

/**
 * Map the set of recorded phase names to a FINAL_STATE enum value that
 * approximates the run's lifecycle position.
 *
 * Priority order (highest specificity first):
 *   post_merge   → MERGED
 *   ready_for_review → READY_FOR_REVIEW
 *   traceability_reconciled or grc_reconciled → READY_FOR_REVIEW
 *   (any phase) → RUNNING (still in progress)
 *   (no phases)  → RUNNING
 *
 * Only the closed backend enum vocabulary is returned.
 */
export function deriveFinalState(phases) {
  if (!(phases instanceof Set)) return "RUNNING";
  if (phases.has("post_merge")) return "MERGED";
  if (phases.has("ready_for_review")) return "READY_FOR_REVIEW";
  if (phases.has("traceability_reconciled") || phases.has("grc_reconciled"))
    return "READY_FOR_REVIEW";
  return "RUNNING";
}

/**
 * Map a FINAL_STATE value to a corresponding OUTCOME enum value.
 * Only MERGED → MERGED; RUNNING / READY_FOR_REVIEW → NONE.
 * Closed values only; never null.
 */
export function deriveOutcome(finalState) {
  if (finalState === "MERGED") return "MERGED";
  return "NONE";
}

// ---------------------------------------------------------------------------
// Zod schema
// ---------------------------------------------------------------------------

export const gcWorkflowRunIngestZodShape = {
  repo_path: z
    .string()
    .min(1)
    .describe("Absolute path to the Git repository (used to resolve owner/repo and fetch the thread)"),
  issue_number: z
    .number()
    .int()
    .positive()
    .describe("GitHub issue number to ingest"),
  project: z
    .string()
    .optional()
    .describe("Ground Control project identifier (required by multi-project backends)"),
  branch: z
    .string()
    .optional()
    .describe("Feature branch name associated with this run"),
  workflow_type: z
    .enum([
      "IMPLEMENT",
      "QUICKFIX",
      "CODEX_REVIEW",
      "TEST_QUALITY_REVIEW",
      "GRC_REVIEW",
      "PREFLIGHT",
      "OTHER",
    ])
    .optional()
    .describe("Override the derived workflow type (default: derived from labels/title)"),
  dry_run: z
    .boolean()
    .optional()
    .describe("When true, parse and return the derived facts without posting to the backend"),
};

export const GC_WORKFLOW_RUN_INGEST_DESCRIPTION =
  "Bridge ingestion: read a GitHub issue thread, parse canonical gc: markers " +
  "(gc:phase, gc:final-report, gc:grc-screening), derive workflow-run facts, and " +
  "POST them to the backend with provenance=ISSUE_THREAD. " +
  "Only canonical HTML-comment markers are trusted; marker-shaped plain text is " +
  "counted as a bounded skip and never forwarded. " +
  "Returns {ok, run_id, phases_recorded, events_posted, skipped_malformed_markers, dry_run}.";

// ---------------------------------------------------------------------------
// Main ingest handler
// ---------------------------------------------------------------------------

/**
 * Bridge ingestion handler. Returns a structured result object; index.js
 * wraps it in the MCP ok() envelope.
 *
 * @param {object} args - validated tool args
 * @param {object} [opts] - optional overrides for testing
 * @param {Function} [opts.threadFetch] - override for runGetIssueThread
 * @param {Function} [opts.runCreate] - override for createWorkflowRun
 * @param {Function} [opts.eventCreate] - override for recordWorkflowRunEvent
 */
export async function gcWorkflowRunIngestHandler(args, opts = {}) {
  const {
    repo_path,
    issue_number,
    project,
    branch,
    workflow_type: workflowTypeOverride,
    dry_run: dryRun = false,
  } = args;

  const threadFetch = opts.threadFetch ?? runGetIssueThread;
  const runCreate = opts.runCreate ?? createWorkflowRun;
  const eventCreate = opts.eventCreate ?? recordWorkflowRunEvent;

  // ── Step 1: Fetch the issue thread ──────────────────────────────────────
  const thread = await threadFetch({ repoPath: repo_path, issueNumber: issue_number });
  if (!thread.ok) {
    return {
      ok: false,
      error: thread.error ?? "thread_fetch_failed",
      message: thread.message ?? "Failed to fetch issue thread",
      issue_number,
    };
  }

  const commentBodies = Array.isArray(thread.comments)
    ? thread.comments.map((c) => (c && typeof c.body === "string" ? c.body : null)).filter(Boolean)
    : [];
  const allBodies = [
    typeof thread.body === "string" ? thread.body : null,
    ...commentBodies,
  ].filter(Boolean);

  // ── Step 2: Parse canonical markers ─────────────────────────────────────
  const phases = parsePhaseMarkers(allBodies, issue_number);
  const finalReport = parseFinalReportMarker(allBodies, issue_number);
  // parseGrcScreeningData is used only for presence detection (no payload
  // forwarding — we never send the screening verdict text to the backend).
  const hasGrcScreening = parseGrcScreeningData(allBodies, issue_number) !== null;

  // Count malformed (marker-shaped but non-canonical) HTML comments.
  const skippedMalformedMarkers = countMalformedMarkers(allBodies);

  // ── Step 3: Derive run dimensions ───────────────────────────────────────
  const requirementUids = extractRequirementUids(thread.body ?? "");
  const workflowType =
    workflowTypeOverride ??
    deriveWorkflowType(thread.labels ?? [], thread.title ?? "");
  const finalState = deriveFinalState(phases);
  const outcome = deriveOutcome(finalState);

  // Derive started_at from the issue's labels or fall back to null (the
  // backend accepts null; it means "unknown").  We do NOT forward timestamps
  // from raw comment bodies — only structured fields are safe.
  // The issue's created_at is not available via runGetIssueThread; leave
  // started_at absent so the backend defaults it.

  const runBody = {
    issue_number,
    branch: branch ?? null,
    workflow_type: workflowType,
    requirement_uids: requirementUids.length > 0 ? requirementUids : undefined,
    final_state: finalState,
    outcome,
    provenance: "ISSUE_THREAD",
    ...(finalReport.found ? { pr_number: finalReport.prNumber } : {}),
  };

  // Build phase events from the parsed phase set. Timestamps are not
  // recoverable from markers alone; occurred_at uses a synthetic ISO string
  // derived from the ingest time. Only the STARTED sentinel uses the
  // issue's thread rather than a specific comment timestamp.
  const now = new Date().toISOString();
  const phasesRecorded = [...phases].sort(); // deterministic order

  const events = phasesRecorded.map((phase) => ({
    phase,
    event_type: "COMPLETED",
    occurred_at: now,
    provenance: "ISSUE_THREAD",
  }));

  // ── Step 4: Dry-run short circuit ───────────────────────────────────────
  if (dryRun) {
    return {
      ok: true,
      dry_run: true,
      issue_number,
      workflow_type: workflowType,
      final_state: finalState,
      outcome,
      phases_recorded: phasesRecorded,
      requirement_uids: requirementUids,
      has_final_report: finalReport.found,
      has_grc_screening: hasGrcScreening,
      events_to_post: events.length,
      skipped_malformed_markers: skippedMalformedMarkers,
    };
  }

  // ── Step 5: POST the workflow run ────────────────────────────────────────
  let run;
  try {
    run = await runCreate(runBody, project);
  } catch (e) {
    return {
      ok: false,
      error: e.code ?? "create_run_failed",
      message: e.message,
      issue_number,
      phases_recorded: phasesRecorded,
      skipped_malformed_markers: skippedMalformedMarkers,
    };
  }

  const runId = run?.id ?? run?.id ?? null;
  if (!runId) {
    return {
      ok: false,
      error: "create_run_no_id",
      message: "Backend did not return a run id",
      issue_number,
      phases_recorded: phasesRecorded,
      skipped_malformed_markers: skippedMalformedMarkers,
    };
  }

  // ── Step 6: POST phase events ────────────────────────────────────────────
  // The run was created in `project`, and the backend scopes the phase-event run lookup by project
  // (issue #859 security review), so each event must carry the same project or it is rejected.
  let eventsPosted = 0;
  let eventsFailed = 0;
  for (const evt of events) {
    try {
      await eventCreate(runId, evt, project);
      eventsPosted += 1;
    } catch {
      // Fail-open: partial event posting is still useful. Failures are counted (not hidden as a
      // zero-posted success) so the caller can decide whether to retry.
      eventsFailed += 1;
    }
  }

  return {
    ok: true,
    run_id: runId,
    issue_number,
    workflow_type: workflowType,
    final_state: finalState,
    outcome,
    phases_recorded: phasesRecorded,
    events_posted: eventsPosted,
    events_failed: eventsFailed,
    has_final_report: finalReport.found,
    has_grc_screening: hasGrcScreening,
    skipped_malformed_markers: skippedMalformedMarkers,
  };
}

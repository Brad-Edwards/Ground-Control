// gc_workflow_run: action-discriminated MCP adapter for the workflow-run
// telemetry REST surface (issue #859). Mirrors gc-observation.js — handler
// logic stays testable in isolation while index.js registers the tool and
// wraps the return value in the MCP ok() envelope.
//
// Security constraints:
//   - Record ONLY the closed field set defined by the backend DTO.
//   - Never forward prompts, completions, raw review bodies, or bearer material.
//   - The cross_project_aggregate action requires ROLE_ADMIN; it is registered
//     unconditionally but the backend enforces the role at the HTTP layer.
//
// Deliberately NOT an action here: GET /api/v1/workflow-runs/stream, the live SSE
// transport added by issue #1436. It is a browser-facing, session-authenticated
// connection that stays open indefinitely, which has no request/response tool shape —
// an agent wanting current state calls `list` or `list_events`, which return bounded
// snapshots of the same committed facts. The exclusion is enforced, not just
// documented: gc-query.js denylists the path and lib.js refuses any text/event-stream
// response. Adding a `stream` action here would require solving cancellation,
// backpressure, and result framing that the MCP tool contract does not have.

import { z } from "zod";
import {
  createWorkflowRun,
  recordWorkflowRunEvent,
  importWorkflowRunCost,
  listWorkflowRuns,
  listWorkflowRunEvents,
  aggregateWorkflowRuns,
  crossProjectAggregateWorkflowRuns,
  measureWorkflowRuns,
  recordWorkflowFindingDisposition,
  pick,
  reqArg,
} from "./lib.js";

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

export const GC_WORKFLOW_RUN_ACTIONS = [
  "record",
  "record_event",
  "import_cost",
  "list",
  "list_events",
  "aggregate",
  "cross_project_aggregate",
  "measurement",
  "record_finding_disposition",
];

// ---------------------------------------------------------------------------
// Field enumerations (closed vocabularies matching backend enums)
// ---------------------------------------------------------------------------

export const WORKFLOW_RUN_WORKFLOW_TYPES = [
  "IMPLEMENT",
  "QUICKFIX",
  "CODEX_REVIEW",
  "TEST_QUALITY_REVIEW",
  "GRC_REVIEW",
  "PREFLIGHT",
  "OTHER",
];

export const WORKFLOW_RUN_FINAL_STATES = [
  "RUNNING",
  "READY_FOR_REVIEW",
  "MERGED",
  "CLOSED",
  "ESCALATED",
  "ABANDONED",
  "SUPERSEDED",
  "FAILED",
];

export const WORKFLOW_RUN_OUTCOMES = ["MERGED", "CLOSED_WITHOUT_MERGE", "NONE"];

export const WORKFLOW_RUN_PROVENANCES = [
  "ISSUE_THREAD",
  "TEMPORAL_VISIBILITY",
  "MANUAL_IMPORT",
  "LIVE_EMISSION",
];

/** ADR-090 station-result vocabulary (issue #1355). Shares no value with the event type. */
export const WORKFLOW_STATION_RESULTS = [
  "PASS",
  "FAIL",
  "SKIPPED_STATION",
  "CANCELLED",
  "NOT_EVALUABLE",
  "UNOBSERVED",
];

export const WORKFLOW_RUN_EVENT_TYPES = [
  "STARTED",
  "COMPLETED",
  "FAILED",
  "ESCALATED",
  "SKIPPED",
];

// Which emitter produced a phase-event row (ADR-090 amendment, issue #1354). The discriminator that
// keeps a durable ADR-036 step observation from being counted as a lifecycle/station attempt.
export const WORKFLOW_PHASE_EMITTERS = ["ADR061_WORKFLOW_TELEMETRY", "ADR036_STEP_JSONL"];

// ADR-036 provider-neutral capability tier carried by a durable step observation.
export const WORKFLOW_CAPABILITY_TIERS = ["LOW", "MEDIUM", "HIGH", "NOT_APPLICABLE", "UNOBSERVED"];

// ---------------------------------------------------------------------------
// Field lists for pick() — closed body sets; no free-form fields accepted
// ---------------------------------------------------------------------------

export const WORKFLOW_RUN_CREATE_FIELDS = [
  "repo",
  "issue_number",
  "pr_number",
  "branch",
  "workflow_type",
  "runtime_driver",
  "requirement_uids",
  "started_at",
  "ended_at",
  "final_state",
  "outcome",
  "provenance",
  "provider",
  "model",
  "model_invocation_count",
  "wall_clock_minutes",
  "cost_proxy",
  "cost_currency",
  "token_usage",
];

export const WORKFLOW_RUN_EVENT_FIELDS = [
  "station_id",
  "station_result",
  "findings",
  "findings_dropped",
  "phase",
  "event_type",
  "cycle_index",
  "occurred_at",
  "duration_ms",
  "outcome",
  "provenance",
  // Deterministic identity of the logical fact (issue #1435). Supplied when the emitter can attest
  // it; the backend derives phase:eventType:cycleIndex otherwise.
  "source_id",
  // Durable ADR-036 step observation (ADR-090 amendment, issue #1354). Present only on an
  // ADR036_STEP_JSONL row; `model` above is reused for the step's reported model.
  "emitter",
  "measurement_version",
  "step_alias",
  "tier",
  "model",
  "expected_model",
  "model_matches_expected",
  "input_tokens",
  "output_tokens",
];

export const WORKFLOW_RUN_COST_FIELDS = [
  "provider",
  "model",
  "model_invocation_count",
  "wall_clock_minutes",
  "cost_proxy",
  "cost_currency",
  "token_usage",
];

// ---------------------------------------------------------------------------
// Zod shape for the consolidated tool
// ---------------------------------------------------------------------------

export const gcWorkflowRunZodShape = {
  action: z.enum(GC_WORKFLOW_RUN_ACTIONS),
  project: z.string().optional(),
  // run identity
  run_id: z.string().uuid().optional().describe("Workflow run UUID (required for record_event and import_cost)"),
  // create fields
  repo: z.string().optional(),
  issue_number: z.number().int().positive().optional(),
  pr_number: z.number().int().positive().optional(),
  branch: z.string().optional(),
  workflow_type: z.enum(WORKFLOW_RUN_WORKFLOW_TYPES).optional(),
  runtime_driver: z.string().optional(),
  requirement_uids: z.array(z.string()).optional(),
  started_at: z.string().optional().describe("ISO-8601 datetime"),
  ended_at: z.string().optional().describe("ISO-8601 datetime"),
  final_state: z.enum(WORKFLOW_RUN_FINAL_STATES).optional(),
  outcome: z.enum(WORKFLOW_RUN_OUTCOMES).optional(),
  provenance: z.enum(WORKFLOW_RUN_PROVENANCES).optional(),
  // cost / model fields (shared by create, import_cost)
  provider: z.string().optional(),
  model: z.string().optional(),
  model_invocation_count: z.number().int().nonnegative().optional(),
  wall_clock_minutes: z.number().nonnegative().optional(),
  cost_proxy: z.number().nonnegative().optional(),
  cost_currency: z.string().optional(),
  token_usage: z.number().int().nonnegative().optional(),
  // event fields
  phase: z.string().optional(),
  event_type: z.enum(WORKFLOW_RUN_EVENT_TYPES).optional(),
  cycle_index: z.number().int().nonnegative().optional(),
  occurred_at: z.string().optional().describe("ISO-8601 datetime (required for record_event)"),
  duration_ms: z.number().int().nonnegative().optional(),
  source_id: z
    .string()
    .max(200)
    .optional()
    .describe("Deterministic identity of the logical phase fact; derived by the backend when absent"),
  // ADR-090 measurement projection (issue #1355)
  station_id: z
    .string()
    .max(100)
    .optional()
    .describe("Authoritative station id from the catalogue; `phase` remains its alias"),
  station_result: z
    .enum(WORKFLOW_STATION_RESULTS)
    .optional()
    .describe(
      "The inspected gate's verdict. Separate from event_type: COMPLETED means the phase finished, "
        + "not that its inspection passed. Omit when no verdict was observed — the backend records "
        + "UNOBSERVED rather than inferring one",
    ),
  findings: z
    .array(
      z.object({
        finding_key: z.string().max(200),
        source_kind: z.enum(["REVIEWER", "DETECTOR"]),
        source_id: z.string().max(100),
        category: z.string().max(300).optional(),
        severity: z.string().max(60).optional(),
        classification: z.string().max(20).optional(),
      }),
    )
    .max(500)
    .optional()
    .describe(
      "Findings this attempt observed. An empty array means the gate ran and found nothing, which "
        + "is a different fact from omitting the field. Carries no prose: no title, body, path, or line",
    ),
  findings_dropped: z
    .number()
    .int()
    .nonnegative()
    .optional()
    .describe(
      "How many findings the emitter's cap discarded before sending. Without it a truncated batch "
        + "is indistinguishable from a complete one and the defect signal is understated by exactly "
        + "the amount hidden",
    ),
  // ADR-036 durable step observation (ADR-090 amendment, issue #1354)
  emitter: z
    .enum(WORKFLOW_PHASE_EMITTERS)
    .optional()
    .describe(
      "Which emitter produced the row. ADR036_STEP_JSONL marks a durable step observation; omit (or "
        + "ADR061_WORKFLOW_TELEMETRY) for a lifecycle/station attempt. Lifecycle/graph aggregates "
        + "exclude the step emitter",
    ),
  measurement_version: z.string().max(40).optional().describe("Measurement contract version, e.g. gc.measurement/v1"),
  step_alias: z.string().max(40).optional().describe("Numbered SKILL step; a non-identity diagnostic alias"),
  tier: z
    .enum(WORKFLOW_CAPABILITY_TIERS)
    .optional()
    .describe("Capability tier of a durable ADR-036 step observation; separate from provider/model"),
  expected_model: z.string().max(200).optional().describe("Tier's canonical model, for the consistency assertion"),
  model_matches_expected: z.boolean().optional().describe("Whether the reported model matched the tier's canonical model"),
  input_tokens: z.number().int().nonnegative().optional(),
  output_tokens: z.number().int().nonnegative().optional(),
  finding_id: z.string().uuid().optional().describe("Gate finding UUID (record_finding_disposition)"),
  disposition: z
    .enum(["FIXED", "WONTFIX", "NOT_APPLICABLE"])
    .optional()
    .describe(
      "Terminal disposition. OPEN is absent by construction: this records a decision, and "
        + "'still open' is the absence of one",
    ),
  // list/aggregate filters
  limit: z.number().int().positive().optional(),
  runtime: z.string().optional(),
  requirement: z.string().optional(),
  from: z.string().optional().describe("ISO-8601 date/datetime filter start"),
  to: z.string().optional().describe("ISO-8601 date/datetime filter end"),
};

export const GC_WORKFLOW_RUN_DESCRIPTION =
  `Workflow-run telemetry (issue #859). ` +
  `Actions: ${GC_WORKFLOW_RUN_ACTIONS.join(", ")}. ` +
  `record: create-or-update (idempotent upsert keyed by project/repo/issue/branch) a workflow run; requires workflow_type and provenance. ` +
  `record_event: append a phase event; requires run_id, project, phase, event_type, occurred_at, provenance (project scopes the run lookup). ` +
  `import_cost: attach cost/token metadata; requires run_id and project (project scopes the run lookup). ` +
  `list: list recent runs for a project; accepts project, limit. ` +
  `list_events: phase events for one run, oldest first; requires run_id and project, accepts limit. ` +
  `measurement: ADR-090 process variables for one project — per-station first-pass yield, iterations to green, rework, and finding counts by reviewer/detector, category, severity, and disposition; requires project, accepts from/to. Every ratio ships with its numerator, denominator, and unresolved count. ` +
  `record_finding_disposition: move one gate finding to a terminal disposition (FIXED / WONTFIX / NOT_APPLICABLE); requires finding_id, project, disposition. ` +
  `aggregate: project-scoped aggregate statistics; accepts project plus optional filters (repo, runtime, requirement, workflow_type, outcome, from, to). ` +
  `cross_project_aggregate: ADMIN-only cross-project aggregate; accepts the same filters minus project. ` +
  `Reads (list, aggregate) are also reachable via gc_query against /api/v1/workflow-runs.`;

// ---------------------------------------------------------------------------
// Handler
// ---------------------------------------------------------------------------

/**
 * Pure adapter handler for gc_workflow_run. Returns the raw lib.js value; the
 * index.js registration wraps it in the MCP ok() envelope.
 */
export async function gcWorkflowRunToolHandler(args, { adminEnabled = false } = {}) {
  switch (args.action) {
    case "record": {
      reqArg(args, "workflow_type", "record");
      reqArg(args, "provenance", "record");
      return createWorkflowRun(pick(args, WORKFLOW_RUN_CREATE_FIELDS), args.project);
    }
    case "record_event": {
      reqArg(args, "run_id", "record_event");
      reqArg(args, "project", "record_event");
      reqArg(args, "phase", "record_event");
      reqArg(args, "event_type", "record_event");
      reqArg(args, "occurred_at", "record_event");
      reqArg(args, "provenance", "record_event");
      return recordWorkflowRunEvent(args.run_id, pick(args, WORKFLOW_RUN_EVENT_FIELDS), args.project);
    }
    case "import_cost": {
      reqArg(args, "run_id", "import_cost");
      reqArg(args, "project", "import_cost");
      return importWorkflowRunCost(args.run_id, pick(args, WORKFLOW_RUN_COST_FIELDS), args.project);
    }
    case "list": {
      return listWorkflowRuns({ project: args.project, limit: args.limit });
    }
    case "list_events": {
      // Event-level retrieval for one run (issue #1435). The aggregate only reports per-phase hot
      // spots across a window, so without this there is no way to see which gate an in-flight run
      // is sitting in. project scopes the run lookup, so a run id alone never authorizes the read.
      reqArg(args, "run_id", "list_events");
      reqArg(args, "project", "list_events");
      return listWorkflowRunEvents(args.run_id, { project: args.project, limit: args.limit });
    }
    case "measurement": {
      // Project-scoped: this reports one project's own production line, so it is not gated
      // behind the admin boundary the cross-project rollup uses.
      reqArg(args, "project", "measurement");
      return measureWorkflowRuns({ project: args.project, from: args.from, to: args.to });
    }
    case "record_finding_disposition": {
      reqArg(args, "finding_id", "record_finding_disposition");
      reqArg(args, "project", "record_finding_disposition");
      reqArg(args, "disposition", "record_finding_disposition");
      return recordWorkflowFindingDisposition(args.finding_id, {
        project: args.project,
        disposition: args.disposition,
      });
    }
    case "aggregate": {
      return aggregateWorkflowRuns({
        project: args.project,
        repo: args.repo,
        runtime: args.runtime,
        requirement: args.requirement,
        workflowType: args.workflow_type,
        outcome: args.outcome,
        from: args.from,
        to: args.to,
      });
    }
    case "cross_project_aggregate": {
      // Admin-only cross-project operational telemetry (issue #859 security review). Gated behind
      // the same GC_MCP_ADMIN boundary as the other admin-token-backed tools so a default MCP
      // session cannot reach cross-project data through this always-registered tool.
      if (!adminEnabled) {
        throw new Error(
          "cross_project_aggregate requires admin tools; start the MCP server with GC_MCP_ADMIN=1",
        );
      }
      return crossProjectAggregateWorkflowRuns({
        repo: args.repo,
        runtime: args.runtime,
        requirement: args.requirement,
        workflowType: args.workflow_type,
        outcome: args.outcome,
        from: args.from,
        to: args.to,
      });
    }
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}

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

import { z } from "zod";
import {
  createWorkflowRun,
  recordWorkflowRunEvent,
  importWorkflowRunCost,
  listWorkflowRuns,
  listWorkflowRunEvents,
  aggregateWorkflowRuns,
  crossProjectAggregateWorkflowRuns,
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

export const WORKFLOW_RUN_EVENT_TYPES = [
  "STARTED",
  "COMPLETED",
  "FAILED",
  "ESCALATED",
  "SKIPPED",
];

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

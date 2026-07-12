// gc_workflow_execution: action-discriminated MCP adapter for the workflow
// control surface (GC-O009 phase 3, issue #1278). Start /implement Temporal
// executions, read execution state from Temporal Visibility + correlation data,
// and send the closed operator-signal catalog — through the product REST
// boundary only (ADR-028: Temporal gRPC/Web are never the product surface).
//
// Security constraints:
//   - Forward ONLY the closed field set below (no headers, tokens, urls,
//     namespaces, task queues, or arbitrary signal method names).
//   - The backend enforces project scope on every action and ROLE_ADMIN on
//     `signal`; this tool is registered unconditionally and the HTTP layer gates.

import { z } from "zod";
import {
  startWorkflowExecution,
  listWorkflowExecutions,
  getWorkflowExecution,
  signalWorkflowExecution,
  pick,
  reqArg,
} from "./lib.js";

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

export const GC_WORKFLOW_EXECUTION_ACTIONS = ["start", "get", "list", "signal"];

// ---------------------------------------------------------------------------
// Field enumerations (closed vocabularies mirroring backend enums, ADR-034)
// ---------------------------------------------------------------------------

export const WORKFLOW_EXECUTION_TYPES = ["IMPLEMENT"];

export const WORKFLOW_SIGNAL_TYPES = ["CANCEL", "RETRY_FROM", "REVIEW_CAP_DISPOSITION"];

export const WORKFLOW_RETRY_PHASES = [
  "A_PLAN_IMPLEMENT",
  "B_QUALITY_GATE",
  "C_STAGE_COMMIT_PUSH",
  "D_SHIP_PIPELINE",
  "E_POST_MERGE_RECONCILE",
];

export const WORKFLOW_REVIEWERS = ["CODEX", "TEST_QUALITY"];

export const WORKFLOW_SIGNAL_DISPOSITIONS = ["PROCEED", "ONE_MORE_CYCLE", "ESCALATE_TO_HUMAN"];

// ---------------------------------------------------------------------------
// Field lists for pick() — closed body sets; no free-form fields accepted
// ---------------------------------------------------------------------------

export const WORKFLOW_EXECUTION_START_FIELDS = [
  "workflow_type",
  "issue_number",
  "sonar_project_key",
  "review_cap",
  "requirement_uids",
  "poll_interval_seconds",
];

export const WORKFLOW_EXECUTION_SIGNAL_FIELDS = [
  "signal_type",
  "reason",
  "retry_from_phase",
  "reviewer",
  "disposition",
];

// ---------------------------------------------------------------------------
// Zod shape for the consolidated tool
// ---------------------------------------------------------------------------

export const gcWorkflowExecutionZodShape = {
  action: z.enum(GC_WORKFLOW_EXECUTION_ACTIONS),
  project: z.string().optional(),
  workflow_id: z.string().optional().describe("Workflow execution id (required for get and signal)"),
  // start fields
  workflow_type: z.enum(WORKFLOW_EXECUTION_TYPES).optional(),
  issue_number: z.number().int().positive().optional(),
  sonar_project_key: z.string().optional(),
  review_cap: z.number().int().min(1).max(10).optional(),
  requirement_uids: z.array(z.string()).optional(),
  poll_interval_seconds: z.number().int().min(1).max(86400).optional(),
  // signal fields
  signal_type: z.enum(WORKFLOW_SIGNAL_TYPES).optional(),
  reason: z.string().optional(),
  retry_from_phase: z.enum(WORKFLOW_RETRY_PHASES).optional(),
  reviewer: z.enum(WORKFLOW_REVIEWERS).optional(),
  disposition: z.enum(WORKFLOW_SIGNAL_DISPOSITIONS).optional(),
  // list filter
  limit: z.number().int().positive().optional(),
};

export const GC_WORKFLOW_EXECUTION_DESCRIPTION =
  `Workflow control surface (GC-O009 #1278): start /implement Temporal executions, read execution state, and send operator signals — product REST boundary only. ` +
  `Actions: ${GC_WORKFLOW_EXECUTION_ACTIONS.join(", ")}. ` +
  `start: begin an execution; requires project, workflow_type (IMPLEMENT), issue_number. ` +
  `get: describe one execution; requires project and workflow_id. ` +
  `list: list the project's executions; accepts project, limit. ` +
  `signal: send an operator signal (ADMIN-only at the backend); requires project, workflow_id, signal_type. ` +
  `CANCEL needs reason; RETRY_FROM needs retry_from_phase; REVIEW_CAP_DISPOSITION needs reviewer + disposition. ` +
  `PR merge is observed from GitHub, never signaled.`;

// ---------------------------------------------------------------------------
// Handler
// ---------------------------------------------------------------------------

/**
 * Pure adapter handler for gc_workflow_execution. Returns the raw lib.js value;
 * the index.js registration wraps it in the MCP ok() envelope.
 */
export async function gcWorkflowExecutionToolHandler(args) {
  switch (args.action) {
    case "start": {
      reqArg(args, "project", "start");
      reqArg(args, "workflow_type", "start");
      reqArg(args, "issue_number", "start");
      return startWorkflowExecution(pick(args, WORKFLOW_EXECUTION_START_FIELDS), args.project);
    }
    case "get": {
      reqArg(args, "project", "get");
      reqArg(args, "workflow_id", "get");
      return getWorkflowExecution(args.workflow_id, args.project);
    }
    case "list": {
      reqArg(args, "project", "list");
      return listWorkflowExecutions({ project: args.project, limit: args.limit });
    }
    case "signal": {
      reqArg(args, "project", "signal");
      reqArg(args, "workflow_id", "signal");
      reqArg(args, "signal_type", "signal");
      return signalWorkflowExecution(
        args.workflow_id,
        pick(args, WORKFLOW_EXECUTION_SIGNAL_FIELDS),
        args.project,
      );
    }
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}

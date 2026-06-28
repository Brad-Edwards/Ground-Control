// gc_research_provenance: action-discriminated MCP adapter for the backend
// research provenance ledger REST surface (GC-RSCH-R004 / GC-RSCH-N002 /
// GC-RSCH-N004, ADR-069). A run-scoped, append-only directed derivation graph of
// provenance nodes and edges. Curated writes (record_node / record_edge) mirror
// REST because gc_query is read-only; reads (list_nodes / list_edges / chain)
// are exposed here as discoverable actions and also remain reachable via gc_query
// under the /api/v1/research-runs allow-list. Handler logic stays testable in
// isolation; index.js registers the tool and wraps the return in the MCP ok()
// envelope. The recording actor is server-populated from the authenticated
// context and is intentionally never a body field (ADR-026 / ADR-033).

import { z } from "zod";
import {
  PROVENANCE_EDGE_RELATIONS,
  PROVENANCE_NODE_KINDS,
  RESEARCH_ARTIFACT_TYPES,
  RESEARCH_RUN_STAGES,
  getResearchProvenanceChain,
  listResearchProvenanceEdges,
  listResearchProvenanceNodes,
  pick,
  recordResearchProvenanceEdge,
  recordResearchProvenanceNode,
  reqArg,
} from "./lib.js";

export const GC_RESEARCH_PROVENANCE_ACTIONS = [
  "record_node",
  "record_edge",
  "list_nodes",
  "list_edges",
  "chain",
];

// Snake_case body fields accepted by record_node — mirrors ProvenanceNodeRequest.
export const GC_RESEARCH_PROVENANCE_NODE_BODY_FIELDS = [
  "kind",
  "subject_key",
  "stage",
  "artifact_type",
  "artifact_id",
  "attempt_no",
  "locator",
  "content_hash",
  "external_identifier",
  "summary",
  "tool_name",
  "tool_version",
  "source_action_id",
  "idempotency_key",
];

// Snake_case body fields accepted by record_edge — mirrors ProvenanceEdgeRequest.
export const GC_RESEARCH_PROVENANCE_EDGE_BODY_FIELDS = [
  "from_node_id",
  "to_node_id",
  "relation",
  "role",
  "summary",
  "idempotency_key",
];

export const GC_RESEARCH_PROVENANCE_RECORD_NODE_REQUIRED_FIELDS = ["kind", "subject_key"];
export const GC_RESEARCH_PROVENANCE_RECORD_EDGE_REQUIRED_FIELDS = ["from_node_id", "to_node_id", "relation"];

export const gcResearchProvenanceZodShape = {
  action: z.enum(GC_RESEARCH_PROVENANCE_ACTIONS),
  project: z.string().optional(),
  run_id: z.string().uuid(),
  // record_node
  kind: z.enum(PROVENANCE_NODE_KINDS).optional(),
  subject_key: z.string().min(1).max(200).optional(),
  stage: z.enum(RESEARCH_RUN_STAGES).optional(),
  artifact_type: z.enum(RESEARCH_ARTIFACT_TYPES).optional(),
  artifact_id: z.string().uuid().optional(),
  attempt_no: z.number().int().positive().optional(),
  locator: z.string().max(500).optional(),
  content_hash: z.string().max(128).optional(),
  external_identifier: z.string().max(200).optional(),
  summary: z.string().max(2000).optional(),
  tool_name: z.string().max(200).optional(),
  tool_version: z.string().max(100).optional(),
  source_action_id: z.string().max(200).optional(),
  idempotency_key: z.string().max(200).optional(),
  // record_edge
  from_node_id: z.string().uuid().optional(),
  to_node_id: z.string().uuid().optional(),
  relation: z.enum(PROVENANCE_EDGE_RELATIONS).optional(),
  role: z.string().max(200).optional(),
  // chain
  node_id: z.string().uuid().optional(),
  depth: z.number().int().positive().optional(),
};

export const GC_RESEARCH_PROVENANCE_DESCRIPTION =
  `Research provenance ledger (GC-RSCH-R004 / GC-RSCH-N002 / GC-RSCH-N004, ADR-069). ` +
  `A run-scoped, append-only directed derivation graph of provenance nodes and edges. ` +
  `Actions: ${GC_RESEARCH_PROVENANCE_ACTIONS.join(", ")}. ` +
  `All actions require run_id (the research run UUID). ` +
  `record_node requires: ${GC_RESEARCH_PROVENANCE_RECORD_NODE_REQUIRED_FIELDS.join(", ")} ` +
  `(kind one of ${PROVENANCE_NODE_KINDS.join("/")}); re-recording the same (kind, subject_key) supersedes the prior. ` +
  `record_edge requires: ${GC_RESEARCH_PROVENANCE_RECORD_EDGE_REQUIRED_FIELDS.join(", ")} ` +
  `(relation one of ${PROVENANCE_EDGE_RELATIONS.join("/")}); edges run upstream->downstream; self-edges and cycles are rejected. ` +
  `chain requires node_id and walks incoming edges backward (optional depth) to the supporting sources/cells. ` +
  `Stores bounded references and short summaries only — never raw queries, full text, charting rows, ` +
  `manuscript prose, prompts, provider payloads, or secrets. The recording actor is server-populated ` +
  `from the authenticated context. Reads also route through gc_query under the /api/v1/research-runs allow-list.`;

/**
 * Pure adapter handler for gc_research_provenance. Validates required fields,
 * re-shapes snake_case body fields to the backend camelCase DTO wire shape, and
 * dispatches to lib.js. Returns the raw value the lib call produces; index.js
 * wraps the return in the MCP ok() envelope.
 */
export async function gcResearchProvenanceToolHandler(args) {
  reqArg(args, "run_id", args.action);
  switch (args.action) {
    case "record_node": {
      for (const key of GC_RESEARCH_PROVENANCE_RECORD_NODE_REQUIRED_FIELDS) reqArg(args, key, "record_node");
      return recordResearchProvenanceNode(args.run_id, toNodeBody(args), args.project);
    }
    case "record_edge": {
      for (const key of GC_RESEARCH_PROVENANCE_RECORD_EDGE_REQUIRED_FIELDS) reqArg(args, key, "record_edge");
      return recordResearchProvenanceEdge(args.run_id, toEdgeBody(args), args.project);
    }
    case "list_nodes":
      return listResearchProvenanceNodes(args.run_id, args.project);
    case "list_edges":
      return listResearchProvenanceEdges(args.run_id, args.project);
    case "chain": {
      reqArg(args, "node_id", "chain");
      return getResearchProvenanceChain(args.run_id, args.node_id, args.depth, args.project);
    }
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}

function toNodeBody(args) {
  const body = pick(args, GC_RESEARCH_PROVENANCE_NODE_BODY_FIELDS);
  return {
    kind: body.kind,
    subjectKey: body.subject_key,
    stage: body.stage,
    artifactType: body.artifact_type,
    artifactId: body.artifact_id,
    attemptNo: body.attempt_no,
    locator: body.locator,
    contentHash: body.content_hash,
    externalIdentifier: body.external_identifier,
    summary: body.summary,
    toolName: body.tool_name,
    toolVersion: body.tool_version,
    sourceActionId: body.source_action_id,
    idempotencyKey: body.idempotency_key,
  };
}

function toEdgeBody(args) {
  const body = pick(args, GC_RESEARCH_PROVENANCE_EDGE_BODY_FIELDS);
  return {
    fromNodeId: body.from_node_id,
    toNodeId: body.to_node_id,
    relation: body.relation,
    role: body.role,
    summary: body.summary,
    idempotencyKey: body.idempotency_key,
  };
}

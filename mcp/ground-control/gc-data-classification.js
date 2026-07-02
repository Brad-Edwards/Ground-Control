// gc_data_classification: action-discriminated MCP adapter for GC-GRC-006.
// Reads/writes the project-scoped data classification lattice and runs the
// deterministic information-flow evaluation against an architecture-model snapshot.

import { z } from "zod";
import {
  evaluateDataClassification,
  getDataClassificationLattice,
  putDataClassificationLattice,
  reqArg,
  resetDataClassificationLattice,
} from "./lib.js";

export const GC_DATA_CLASSIFICATION_ACTIONS = ["get_lattice", "set_lattice", "reset_lattice", "evaluate"];

const labelSchema = z.object({
  key: z
    .string()
    .min(1)
    .max(120)
    .regex(/^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/),
  display_name: z.string().min(1).max(200),
  description: z.string().max(2000).optional(),
  rank: z.number().int().optional(),
});

const flowSchema = z.object({
  from: z.string().min(1).max(120),
  to: z.string().min(1).max(120),
});

export const gcDataClassificationZodShape = {
  action: z.enum(GC_DATA_CLASSIFICATION_ACTIONS),
  project: z.string().optional(),
  labels: z.array(labelSchema).max(50).optional(),
  permitted_flows: z.array(flowSchema).max(2000).optional(),
  snapshot_id: z.string().uuid().optional(),
};

export const GC_DATA_CLASSIFICATION_DESCRIPTION =
  `Data classification lattice (GC-GRC-006). Actions: ${GC_DATA_CLASSIFICATION_ACTIONS.join(", ")}. ` +
  `get_lattice returns the project's active lattice (stored custom policy, or the shipped default). ` +
  `set_lattice replaces it with a custom taxonomy + permitted-flow relation (labels[].{key,display_name,description,rank} ` +
  `and permitted_flows[].{from,to}); the backend validates lattice soundness and is ROLE_ADMIN-only. ` +
  `reset_lattice (ROLE_ADMIN) reverts the project to the default lattice. ` +
  `evaluate runs the deterministic information-flow check over an architecture-model snapshot ` +
  `(latest when snapshot_id is omitted), returning policy-violating flows and limitations with no LLM judgment.`;

export async function gcDataClassificationToolHandler(args) {
  switch (args.action) {
    case "get_lattice":
      return getDataClassificationLattice({ project: args.project });
    case "set_lattice": {
      reqArg(args, "labels", "set_lattice");
      // Build the backend body in camelCase directly: request()'s toCamelCase pass
      // only renames keys in the shared TO_CAMEL map, so per-tool field names like
      // display_name / permitted_flows must be mapped here at the adapter boundary.
      const labels = args.labels.map((label) => ({
        key: label.key,
        displayName: label.display_name,
        description: label.description,
        rank: label.rank,
      }));
      const permittedFlows = (args.permitted_flows ?? []).map((flow) => ({ from: flow.from, to: flow.to }));
      return putDataClassificationLattice({ labels, permittedFlows }, args.project);
    }
    case "reset_lattice":
      return resetDataClassificationLattice({ project: args.project });
    case "evaluate":
      return evaluateDataClassification({ project: args.project, snapshotId: args.snapshot_id });
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}

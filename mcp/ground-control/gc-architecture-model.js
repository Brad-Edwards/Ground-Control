// gc_architecture_model: action-discriminated MCP adapter for GC-GRC-005.
// Writes and reads the server-side canonical architecture-model aggregate.

import { z } from "zod";
import {
  ARCHITECTURE_FLOW_DIRECTIONS,
  ARCHITECTURE_MODEL_ELEMENT_KINDS,
  ARCHITECTURE_MODEL_PROVENANCE_SOURCES,
  createArchitectureModelSnapshot,
  diffArchitectureModelSnapshots,
  getArchitectureModelElement,
  getArchitectureModelSnapshot,
  listArchitectureModelElements,
  listArchitectureModelSnapshots,
  pick,
  reqArg,
} from "./lib.js";

export const GC_ARCHITECTURE_MODEL_ACTIONS = [
  "create_snapshot",
  "list_snapshots",
  "get_snapshot",
  "list_elements",
  "get_element",
  "diff_snapshots",
];

export const GC_ARCHITECTURE_MODEL_CREATE_SNAPSHOT_FIELDS = [
  "model_version",
  "commit_sha",
  "source",
  "created_by",
  "elements",
];

export const GC_ARCHITECTURE_MODEL_ELEMENT_FIELDS = [
  "stable_key",
  "element_kind",
  "label",
  "summary",
  "source_path",
  "trust_boundary_key",
  "data_classification_key",
  "flow_source_stable_key",
  "flow_target_stable_key",
  "flow_direction",
  "provenance_source",
  "provenance_key",
  "adapter_id",
  "tool_name",
  "tool_version",
  "ruleset_name",
  "ruleset_version",
  "derivation_run_id",
  "commit_sha",
  "metadata",
];

const elementSchema = z.object({
  stable_key: z.string().min(1).max(200),
  element_kind: z.enum(ARCHITECTURE_MODEL_ELEMENT_KINDS),
  label: z.string().min(1).max(200),
  summary: z.string().max(8192).optional(),
  source_path: z.string().max(500).optional(),
  trust_boundary_key: z.string().max(120).optional(),
  data_classification_key: z.string().max(120).optional(),
  flow_source_stable_key: z.string().max(200).optional(),
  flow_target_stable_key: z.string().max(200).optional(),
  flow_direction: z.enum(ARCHITECTURE_FLOW_DIRECTIONS).optional(),
  provenance_source: z.enum(ARCHITECTURE_MODEL_PROVENANCE_SOURCES),
  provenance_key: z.string().min(1).max(200),
  adapter_id: z.string().max(100).optional(),
  tool_name: z.string().max(100).optional(),
  tool_version: z.string().max(100).optional(),
  ruleset_name: z.string().max(200).optional(),
  ruleset_version: z.string().max(100).optional(),
  derivation_run_id: z.string().uuid().optional(),
  commit_sha: z.string().regex(/^[0-9a-fA-F]{7,64}$/).optional(),
  metadata: z.record(z.any()).optional(),
});

export const gcArchitectureModelZodShape = {
  action: z.enum(GC_ARCHITECTURE_MODEL_ACTIONS),
  id: z.string().uuid().optional(),
  project: z.string().optional(),
  model_version: z.string().min(1).max(120).optional(),
  commit_sha: z.string().regex(/^[0-9a-fA-F]{7,64}$/).optional(),
  source: z.string().min(1).max(40).optional(),
  created_by: z.string().max(100).optional().default(""),
  elements: z.array(elementSchema).max(10_000).optional(),
  from_snapshot_id: z.string().uuid().optional(),
  to_snapshot_id: z.string().uuid().optional(),
};

export const GC_ARCHITECTURE_MODEL_DESCRIPTION =
  `Architecture model aggregate (GC-GRC-005). Actions: ${GC_ARCHITECTURE_MODEL_ACTIONS.join(", ")}. ` +
  `create_snapshot persists a project-scoped versioned DFD snapshot with stable graph-native elements; ` +
  `DATA_FLOW elements require source/target stable keys that are present in the same snapshot. ` +
  `list_snapshots returns snapshot summaries (metadata and element/flow counts, no element payloads); ` +
  `get_snapshot returns one snapshot with its full element state. ` +
  `diff_snapshots compares two snapshots and returns added/removed/changed/provenance-only entries.`;

export async function gcArchitectureModelToolHandler(args) {
  switch (args.action) {
    case "create_snapshot":
      reqArg(args, "model_version", "create_snapshot");
      reqArg(args, "commit_sha", "create_snapshot");
      reqArg(args, "source", "create_snapshot");
      reqArg(args, "elements", "create_snapshot");
      return createArchitectureModelSnapshot(
        pick(args, GC_ARCHITECTURE_MODEL_CREATE_SNAPSHOT_FIELDS),
        args.project,
      );
    case "list_snapshots":
      return listArchitectureModelSnapshots({ project: args.project });
    case "get_snapshot":
      reqArg(args, "id", "get_snapshot");
      return getArchitectureModelSnapshot(args.id, args.project);
    case "list_elements":
      return listArchitectureModelElements({ project: args.project });
    case "get_element":
      reqArg(args, "id", "get_element");
      return getArchitectureModelElement(args.id, args.project);
    case "diff_snapshots":
      reqArg(args, "from_snapshot_id", "diff_snapshots");
      reqArg(args, "to_snapshot_id", "diff_snapshots");
      return diffArchitectureModelSnapshots({
        project: args.project,
        fromSnapshotId: args.from_snapshot_id,
        toSnapshotId: args.to_snapshot_id,
      });
    default:
      throw new Error(`Unknown action: ${args.action}`);
  }
}

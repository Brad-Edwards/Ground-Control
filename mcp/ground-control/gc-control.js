// gc_control: entity- + action-discriminated MCP adapter for the control,
// control test, and control effectiveness assessment REST surfaces.
// Extracted from index.js so the handler logic is testable in isolation
// (mirrors the gc-risk-governance.js extraction from #878). The original
// gc_control surface (entity=control + create/update/delete/transition +
// link_create/link_delete) is preserved unchanged; new entities (control_test
// for GC-I012, control_effectiveness_assessment for GC-I013) are added under
// the same tool per ADR-035's consolidated-surface mandate.
//
// Reads (list, get, links_list) route through gc_query for every entity.

import { z } from "zod";
import {
  pick, reqArg,
  CONTROL_STATUSES, CONTROL_FUNCTIONS, CONTROL_LINK_TARGET_TYPES, CONTROL_LINK_TYPES,
  CONTROL_TEST_METHODOLOGIES, CONTROL_TEST_CONCLUSIONS, CONTROL_EFFECTIVENESS_RATINGS,
  createControl, updateControl, deleteControl,
  transitionControlStatus, createControlLink, deleteControlLink,
  createControlTest, updateControlTest, deleteControlTest,
  createControlEffectivenessAssessment, updateControlEffectivenessAssessment,
  deleteControlEffectivenessAssessment,
} from "./lib.js";
import { linkCreateOptionalSharedZodFields, performLinkCreate } from "./link-create.js";

export const GC_CONTROL_ENTITIES = [
  "control", "control_test", "control_effectiveness_assessment",
];

// Per-entity action set. Backwards-compatible: the existing entity="control"
// surface keeps every action it had (link_create/link_delete remain
// control-only — sub-entities have no separate link table).
export const GC_CONTROL_ACTIONS = [
  "create", "update", "delete", "transition", "link_create", "link_delete",
];

// Per-entity, per-action body allowlist. Mirrors the backend Request records
// under backend/src/main/java/com/keplerops/groundcontrol/api/controls/.
// `pick(args, fieldsForAction)` is the gate that drops stale fields — never
// pre-filter on the test side.
export const CONTROL_FIELDS = {
  control: {
    // Mirrors ControlRequest: uid, title, controlFunction, description, objective,
    // owner, implementationScope, methodologyFactors, effectiveness, category, source.
    // status and metadata are not in ControlRequest (status changes go through the
    // transition action; ControlRequest has no metadata bag).
    create: [
      "uid", "title", "control_function", "description", "objective",
      "owner", "implementation_scope", "methodology_factors", "effectiveness",
      "category", "source",
    ],
    // Mirrors UpdateControlRequest: same fields minus uid.
    update: [
      "title", "control_function", "description", "objective",
      "owner", "implementation_scope", "methodology_factors", "effectiveness",
      "category", "source",
    ],
  },
  control_test: {
    create: [
      "control_id", "uid", "methodology", "test_steps", "expected_results",
      "actual_results", "conclusion", "tester_identity", "test_date", "notes",
    ],
    update: [
      "methodology", "test_steps", "expected_results", "actual_results",
      "conclusion", "tester_identity", "test_date", "notes",
    ],
  },
  control_effectiveness_assessment: {
    create: [
      "control_id", "uid", "design_effectiveness", "operating_effectiveness",
      "assessed_at", "assessor", "rationale", "notes", "supporting_test_ids",
    ],
    update: [
      "design_effectiveness", "operating_effectiveness", "assessed_at",
      "assessor", "rationale", "notes", "supporting_test_ids",
    ],
  },
};

export const gcControlZodShape = {
  // Default to "control" so existing callers (which omit `entity`) keep working.
  entity: z.enum(GC_CONTROL_ENTITIES).default("control"),
  action: z.enum(GC_CONTROL_ACTIONS),
  id: z.string().uuid().optional(),
  uid: z.string().optional(),
  project: z.string().optional(),
  // ---- control (existing + ControlRequest fields added by #1106) ----
  title: z.string().optional(),
  description: z.string().optional(),
  status: z.enum(CONTROL_STATUSES).optional(),
  control_function: z.enum(CONTROL_FUNCTIONS).optional(),
  metadata: z.record(z.any()).optional(),
  // ControlRequest / UpdateControlRequest fields (mirrors backend DTO, #1106)
  objective: z.string().optional(),
  owner: z.string().optional(),
  implementation_scope: z.string().optional(),
  methodology_factors: z.record(z.any()).optional(),
  effectiveness: z.record(z.any()).optional(),
  category: z.string().optional(),
  source: z.string().optional(),
  control_id: z.string().uuid().optional(),
  target_type: z.enum(CONTROL_LINK_TARGET_TYPES).optional(),
  link_type: z.enum(CONTROL_LINK_TYPES).optional(),
  ...linkCreateOptionalSharedZodFields,
  link_id: z.string().uuid().optional(),
  // ---- control_test (GC-I012) ----
  methodology: z.enum(CONTROL_TEST_METHODOLOGIES).optional(),
  test_steps: z.string().optional(),
  expected_results: z.string().optional(),
  actual_results: z.string().optional(),
  conclusion: z.enum(CONTROL_TEST_CONCLUSIONS).optional(),
  tester_identity: z.string().optional(),
  test_date: z.string().optional(),
  // ---- control_effectiveness_assessment (GC-I013) ----
  design_effectiveness: z.enum(CONTROL_EFFECTIVENESS_RATINGS).optional(),
  operating_effectiveness: z.enum(CONTROL_EFFECTIVENESS_RATINGS).optional(),
  assessed_at: z.string().optional(),
  assessor: z.string().optional(),
  rationale: z.string().optional(),
  supporting_test_ids: z.array(z.string().uuid()).optional(),
  // ---- shared ----
  notes: z.string().optional(),
};

export const GC_CONTROL_DESCRIPTION =
  `Controls + control tests (GC-I012) + control effectiveness assessments (GC-I013) + their links. ` +
  `Entity: ${GC_CONTROL_ENTITIES.join(", ")} (defaults to "control" for back-compat). ` +
  `Actions: ${GC_CONTROL_ACTIONS.join(", ")}. ` +
  `Sub-entity actions are limited to create/update/delete — no transition or link actions. ` +
  `control create fields (snake_case; round-trip to backend camelCase): ` +
  `{uid,title,control_function,description,objective,owner,implementation_scope,methodology_factors,effectiveness,category,source}. ` +
  `control update drops uid; all remaining fields are optional. ` +
  `Per-sub-entity create fields: ` +
  `control_test={control_id,uid,methodology,test_steps,expected_results,actual_results,conclusion,tester_identity,test_date,notes}; ` +
  `control_effectiveness_assessment={control_id,uid,design_effectiveness,operating_effectiveness,assessed_at,assessor,rationale,notes,supporting_test_ids}. ` +
  `supporting_test_ids is a list of ControlTest UUIDs that must belong to the same control as the assessment; ` +
  `the backend emits SUPPORTED_BY graph edges from the assessment to each listed test. ` +
  `Update DTOs drop create-only foreign keys (control_id, uid); a non-null supporting_test_ids replaces the list wholesale. ` +
  `Reads (list, get, links_list) route through gc_query. ` +
  `Required fields per action: control/create→{title}; */update/delete→{id}; control/transition→{id,status}; control/link_create→{control_id,target_type,link_type}; control/link_delete→{control_id,link_id}; control_test/create and control_effectiveness_assessment/create→{control_id,uid}.`;

/**
 * Pure adapter handler for gc_control. Picks action-scoped body fields per
 * (entity, action) and dispatches to the corresponding lib.js call. Returns
 * the raw value the lib call produces (or null for delete responses); the
 * index.js registration wraps the return in the MCP ok() envelope. Throws
 * Error on unknown action/entity combinations and on missing required args.
 */
export async function gcControlToolHandler(args) {
  const entity = args.entity ?? "control";
  const allowlist = CONTROL_FIELDS[entity]?.[args.action] ?? [];
  const data = pick(args, allowlist);
  switch (entity) {
    case "control": {
      switch (args.action) {
        case "create":
          reqArg(args, "title", "create");
          return createControl(data, args.project);
        case "update":
          reqArg(args, "id", "update");
          return updateControl(args.id, data, args.project);
        case "delete":
          reqArg(args, "id", "delete");
          await deleteControl(args.id, args.project);
          return null;
        case "transition":
          reqArg(args, "id", "transition");
          reqArg(args, "status", "transition");
          return transitionControlStatus(args.id, args.status, args.project);
        case "link_create":
          return performLinkCreate(args, "control_id", createControlLink);
        case "link_delete":
          reqArg(args, "control_id", "link_delete");
          reqArg(args, "link_id", "link_delete");
          await deleteControlLink(args.control_id, args.link_id, args.project);
          return null;
        default:
          throw new Error(`Action '${args.action}' not valid for control`);
      }
    }
    case "control_test": {
      switch (args.action) {
        case "create":
          reqArg(args, "control_id", "create");
          reqArg(args, "uid", "create");
          return createControlTest(data, args.project);
        case "update":
          reqArg(args, "id", "update");
          return updateControlTest(args.id, data, args.project);
        case "delete":
          reqArg(args, "id", "delete");
          await deleteControlTest(args.id, args.project);
          return null;
        default:
          throw new Error(`Action '${args.action}' not valid for control_test`);
      }
    }
    case "control_effectiveness_assessment": {
      switch (args.action) {
        case "create":
          reqArg(args, "control_id", "create");
          reqArg(args, "uid", "create");
          return createControlEffectivenessAssessment(data, args.project);
        case "update":
          reqArg(args, "id", "update");
          return updateControlEffectivenessAssessment(args.id, data, args.project);
        case "delete":
          reqArg(args, "id", "delete");
          await deleteControlEffectivenessAssessment(args.id, args.project);
          return null;
        default:
          throw new Error(`Action '${args.action}' not valid for control_effectiveness_assessment`);
      }
    }
    default:
      throw new Error(`Unknown entity: ${entity}`);
  }
}

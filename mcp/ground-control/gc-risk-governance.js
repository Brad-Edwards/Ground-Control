// gc_risk_governance: entity- + action-discriminated MCP adapter for the
// methodology profile, risk register record, risk assessment result, treatment
// plan, and verification result REST surfaces (ADR-035). Extracted from
// index.js so the handler logic is testable in isolation — see
// gc-risk-governance.test.js for adapter-level tests that drive the full
// path raw args → handler dispatch → backend HTTP call (mocked fetch). Issues
// #878/#879/#880 are the regression locks: per-entity allowlists must mirror
// the backend Request records, and the handler's `pick(args, ...)` is the
// gate that drops stale fields — not test-side pre-filtering.

import { z } from "zod";
import {
  METHODOLOGY_FAMILIES,
  RISK_ASSESSMENT_APPROVAL_STATUSES,
  TREATMENT_STRATEGIES,
  ASSURANCE_LEVELS,
  GOVERNANCE_FIELDS,
  NORMALIZED_CONCEPTS,
  CROSSWALK_VOCABULARY_SURFACES,
  APPETITE_TOLERANCE_KINDS,
  CAMPAIGN_PHASES,
  REASSESSMENT_TRIGGER_CATEGORIES,
  REASSESSMENT_TRIGGER_TARGET_TYPES,
  pick, reqArg, validateGovernanceStatus,
  createMethodologyProfile, updateMethodologyProfile, deleteMethodologyProfile,
  createRiskRegisterRecord, updateRiskRegisterRecord, deleteRiskRegisterRecord,
  transitionRiskRegisterRecordStatus,
  createRiskAssessmentResult, updateRiskAssessmentResult,
  deleteRiskAssessmentResult, transitionRiskAssessmentApprovalState,
  createTreatmentPlan, updateTreatmentPlan, deleteTreatmentPlan,
  transitionTreatmentPlanStatus,
  createVerificationResult, updateVerificationResult, deleteVerificationResult,
  createRiskAppetiteProfile, updateRiskAppetiteProfile, deleteRiskAppetiteProfile,
  createRiskAssessmentCampaign, updateRiskAssessmentCampaign,
  deleteRiskAssessmentCampaign, advanceRiskAssessmentCampaignPhase,
  createKeyRiskIndicator, updateKeyRiskIndicator, deleteKeyRiskIndicator,
  recordKriMeasurement,
} from "./lib.js";

export const GC_RISK_GOVERNANCE_ENTITIES = [
  "methodology_profile", "risk_register_record", "risk_assessment_result",
  "treatment_plan", "verification_result",
  // GC-T005 / T006 / T007 risk-governance lifecycle aggregates
  "risk_appetite_profile", "risk_assessment_campaign", "key_risk_indicator",
];
export const GC_RISK_GOVERNANCE_ACTIONS = [
  "create", "update", "delete", "transition", "transition_approval",
  // GC-T006 campaign phase advance, GC-T007 KRI measurement
  "advance_phase", "record_measurement",
];

export const gcRiskGovernanceZodShape = {
  entity: z.enum(GC_RISK_GOVERNANCE_ENTITIES),
  action: z.enum(GC_RISK_GOVERNANCE_ACTIONS),
  id: z.string().uuid().optional(),
  project: z.string().optional(),
  // Status vocabulary is per-entity; the handler validates it against
  // GOVERNANCE_STATUS_ENUMS[args.entity] before any backend call. The Zod
  // shape accepts any string here — a discriminated check at the schema
  // level would require restructuring this tool into five entity-specific
  // tools, which ADR-035 already rejected.
  status: z.string().optional(),
  approval_state: z.enum(RISK_ASSESSMENT_APPROVAL_STATUSES).optional(),
  // Shared entity fields. Per-entity allowlist (GOVERNANCE_FIELDS) gates which
  // ones reach the backend on create/update, so unrelated MCP control fields
  // (action, entity, id, project) don't leak into the DTO.
  uid: z.string().optional(),
  name: z.string().optional(),
  title: z.string().optional(),
  description: z.string().optional(),
  family: z.enum(METHODOLOGY_FAMILIES).optional(),
  risk_scenario_id: z.string().uuid().optional(),
  risk_scenario_ids: z.array(z.string().uuid()).optional(),
  risk_register_record_id: z.string().uuid().optional(),
  methodology_profile_id: z.string().uuid().optional(),
  methodology_strategy_key: z.string().optional(),
  owner: z.string().optional(),
  review_cadence: z.string().optional(),
  next_review_at: z.string().optional(),
  category_tags: z.array(z.string()).optional(),
  decision_metadata: z.record(z.any()).optional(),
  asset_scope_summary: z.string().optional(),
  analyst_identity: z.string().optional(),
  assumptions: z.string().optional(),
  input_factors: z.record(z.any()).optional(),
  observation_date: z.string().optional(),
  assessment_at: z.string().optional(),
  time_horizon: z.string().optional(),
  confidence: z.string().optional(),
  uncertainty_metadata: z.record(z.any()).optional(),
  computed_outputs: z.record(z.any()).optional(),
  evidence_refs: z.array(z.string()).optional(),
  notes: z.string().optional(),
  observation_ids: z.array(z.string().uuid()).optional(),
  strategy: z.enum(TREATMENT_STRATEGIES).optional(),
  rationale: z.string().optional(),
  due_date: z.string().optional(),
  action_items: z.array(z.object({
    owner: z.string(),
    due_date: z.string(),
    status: z.enum(["PLANNED", "IN_PROGRESS", "BLOCKED", "DONE", "CANCELED"]),
    assignee: z.string().optional(),
    description: z.string().optional(),
  })).optional(),
  reassessment_triggers: z.array(z.object({
    // GC-T015: enum lists are sourced from the shared ADR-034 mirror in
    // lib.js (REASSESSMENT_TRIGGER_CATEGORIES / REASSESSMENT_TRIGGER_TARGET_TYPES).
    // Hardcoding the legacy 5-category / 7-target subset would silently
    // reject the new NIST §3.4 categories (THREAT/VULNERABILITY/...
    // KRI_BREACH) and the new lifecycle target types (RISK_APPETITE_PROFILE,
    // RISK_ASSESSMENT_CAMPAIGN, KEY_RISK_INDICATOR, OBSERVATION, THREAT_MODEL)
    // that the backend enums were extended with — keep this Zod surface in
    // lockstep with the Java source via the shared mirror.
    category: z.enum(REASSESSMENT_TRIGGER_CATEGORIES),
    target_type: z.enum(REASSESSMENT_TRIGGER_TARGET_TYPES).optional(),
    target_entity_id: z.string().uuid().optional(),
    target_identifier: z.string().optional(),
    note: z.string().optional(),
  }).superRefine((t, ctx) => {
    // GC-T004 / C8 (#863), codex cycle-1 finding #2: the trigger target is a
    // coherent typed reference, not three independent optionals. Mirror the
    // backend invariant at the MCP boundary so bad calls fail before
    // round-tripping through the API.
    const hasIdentifier = t.target_identifier != null && t.target_identifier.length > 0;
    const hasEntityId = t.target_entity_id != null;
    if (t.target_type == null) {
      if (hasEntityId || hasIdentifier) {
        ctx.addIssue({
          code: "custom",
          message: "target_entity_id / target_identifier require target_type",
        });
      }
      return;
    }
    if (t.target_type === "EXTERNAL") {
      if (hasEntityId) {
        ctx.addIssue({
          code: "custom",
          message: "target_type=EXTERNAL must not set target_entity_id",
        });
      }
      if (!hasIdentifier) {
        ctx.addIssue({
          code: "custom",
          message: "target_type=EXTERNAL requires target_identifier",
        });
      }
    } else {
      if (hasIdentifier) {
        ctx.addIssue({
          code: "custom",
          message: `target_type=${t.target_type} must not set target_identifier`,
        });
      }
      if (!hasEntityId) {
        ctx.addIssue({
          code: "custom",
          message: `target_type=${t.target_type} requires target_entity_id`,
        });
      }
    }
  })).optional(),
  reassessment_required_at: z.string().optional(),
  outcome: z.string().optional(),
  assurance_level: z.enum(ASSURANCE_LEVELS).optional(),
  verified_at: z.string().optional(),
  metadata: z.record(z.any()).optional(),
  // GC-T012: profile-scoped crosswalk entries. Forwarded verbatim to the
  // REST API after toCamelCase conversion (crosswalk_entries → crosswalkEntries).
  crosswalk_entries: z.array(z.object({
    normalizedConcept: z.enum(NORMALIZED_CONCEPTS),
    vocabularySurface: z.enum(CROSSWALK_VOCABULARY_SURFACES),
    sourceFieldPath: z.string(),
    sourceTermLabel: z.string().optional(),
    sourceTermDefinition: z.string().optional(),
    scale: z.string().optional(),
    units: z.string().optional(),
    conversionRule: z.string().optional(),
    limitations: z.string().optional(),
  })).optional(),
  // GC-T005: appetite profile fields
  profile_key: z.string().optional(),
  version: z.string().optional(),
  appetite_statement: z.string().optional(),
  active: z.boolean().optional(),
  tolerances: z.array(z.object({
    category: z.string(),
    kind: z.enum(APPETITE_TOLERANCE_KINDS),
    qualitativeLabel: z.string().optional(),
    monetaryLow: z.number().optional(),
    monetaryHigh: z.number().optional(),
    currency: z.string().optional(),
    lossEventFrequencyMax: z.number().optional(),
    exceedanceProbabilityMax: z.number().optional(),
    criteria: z.record(z.any()).optional(),
    rationale: z.string().optional(),
  })).optional(),
  // GC-T006: campaign fields
  appetite_profile_id: z.string().uuid().optional(),
  objective: z.string().optional(),
  scheduled_start: z.string().optional(),
  scheduled_end: z.string().optional(),
  scope: z.record(z.any()).optional(),
  approval_metadata: z.record(z.any()).optional(),
  scoped_asset_ids: z.array(z.string()).optional(),
  phase: z.enum(CAMPAIGN_PHASES).optional(),
  // GC-T007: KRI fields
  metric_unit: z.string().optional(),
  yellow_threshold: z.number().optional(),
  red_threshold: z.number().optional(),
  direction: z.string().optional(),
  value: z.number().optional(),
  measured_at: z.string().optional(),
  // GC-T015: treatment plan extensions
  risk_assessment_result_id: z.string().uuid().optional(),
  monitored_risk_factors: z.array(z.object({
    label: z.string(),
    category: z.string(),
    cadence: z.string().optional(),
    notes: z.string().optional(),
  })).optional(),
  update_cadence: z.string().optional(),
};

export const GC_RISK_GOVERNANCE_DESCRIPTION =
  `Methodology profiles, risk register records, risk assessments, treatment plans, verification results. ` +
  `Entity: ${GC_RISK_GOVERNANCE_ENTITIES.join(", ")}. Actions: ${GC_RISK_GOVERNANCE_ACTIONS.join(", ")}. ` +
  `Reads (list, get) route through gc_query. ` +
  `Per-entity create fields (snake_case; round-trip to backend camelCase): ` +
  `risk_register_record={uid,title,owner,review_cadence,next_review_at,category_tags,decision_metadata,asset_scope_summary,risk_scenario_ids}; ` +
  `risk_assessment_result={risk_scenario_id,risk_register_record_id,methodology_profile_id,analyst_identity,assumptions,input_factors,observation_date,assessment_at,time_horizon,confidence,uncertainty_metadata,computed_outputs,evidence_refs,notes,observation_ids}; ` +
  `treatment_plan={uid,title,risk_scenario_id,risk_register_record_id,strategy,owner,rationale,due_date,status,action_items,reassessment_triggers[{category,target_type,target_entity_id,target_identifier,note}],methodology_profile_id,methodology_strategy_key}. ` +
  `Update DTOs drop create-only foreign keys (uid; risk_register_record_id for treatment_plan; risk_scenario_id for risk_assessment_result) and status fields whose changes go through the transition action. ` +
  `Unknown fields are dropped — never tunneled through metadata.`;

/**
 * Pure adapter handler for gc_risk_governance. Validates per-entity status,
 * picks action-scoped body fields, and dispatches to the corresponding lib.js
 * call. Returns the raw value the lib call produces (or null for delete /
 * 204 responses); the index.js registration wraps the return in the MCP `ok()`
 * envelope. Throws Error on unknown action/entity combinations and on missing
 * required args — same semantics as the previous inline handler.
 */
export async function gcRiskGovernanceToolHandler(args) {
  validateGovernanceStatus(args.entity, args.status);
  const fieldsForAction = GOVERNANCE_FIELDS[args.entity]?.[args.action] ?? [];
  const data = pick(args, fieldsForAction);
  switch (args.entity) {
    case "methodology_profile": {
      switch (args.action) {
        case "create": return createMethodologyProfile(data, args.project);
        case "update": reqArg(args, "id", "update"); return updateMethodologyProfile(args.id, data, args.project);
        case "delete": reqArg(args, "id", "delete"); await deleteMethodologyProfile(args.id, args.project); return null;
        default: throw new Error(`Action '${args.action}' not valid for methodology_profile`);
      }
    }
    case "risk_register_record": {
      switch (args.action) {
        case "create": return createRiskRegisterRecord(data, args.project);
        case "update": reqArg(args, "id", "update"); return updateRiskRegisterRecord(args.id, data, args.project);
        case "delete": reqArg(args, "id", "delete"); await deleteRiskRegisterRecord(args.id, args.project); return null;
        case "transition":
          reqArg(args, "id", "transition");
          reqArg(args, "status", "transition");
          return transitionRiskRegisterRecordStatus(args.id, args.status, args.project);
        default: throw new Error(`Action '${args.action}' not valid for risk_register_record`);
      }
    }
    case "risk_assessment_result": {
      switch (args.action) {
        case "create": return createRiskAssessmentResult(data, args.project);
        case "update": reqArg(args, "id", "update"); return updateRiskAssessmentResult(args.id, data, args.project);
        case "delete": reqArg(args, "id", "delete"); await deleteRiskAssessmentResult(args.id, args.project); return null;
        case "transition_approval":
          reqArg(args, "id", "transition_approval");
          reqArg(args, "approval_state", "transition_approval");
          return transitionRiskAssessmentApprovalState(args.id, args.approval_state, args.project);
        default: throw new Error(`Action '${args.action}' not valid for risk_assessment_result`);
      }
    }
    case "treatment_plan": {
      switch (args.action) {
        case "create": return createTreatmentPlan(data, args.project);
        case "update": reqArg(args, "id", "update"); return updateTreatmentPlan(args.id, data, args.project);
        case "delete": reqArg(args, "id", "delete"); await deleteTreatmentPlan(args.id, args.project); return null;
        case "transition":
          reqArg(args, "id", "transition");
          reqArg(args, "status", "transition");
          return transitionTreatmentPlanStatus(args.id, args.status, args.project);
        default: throw new Error(`Action '${args.action}' not valid for treatment_plan`);
      }
    }
    case "verification_result": {
      switch (args.action) {
        case "create": return createVerificationResult(data, args.project);
        case "update": reqArg(args, "id", "update"); return updateVerificationResult(args.id, data, args.project);
        case "delete": reqArg(args, "id", "delete"); await deleteVerificationResult(args.id, args.project); return null;
        default: throw new Error(`Action '${args.action}' not valid for verification_result`);
      }
    }
    case "risk_appetite_profile": {
      switch (args.action) {
        case "create": return createRiskAppetiteProfile(data, args.project);
        case "update": reqArg(args, "id", "update"); return updateRiskAppetiteProfile(args.id, data, args.project);
        case "delete": reqArg(args, "id", "delete"); await deleteRiskAppetiteProfile(args.id, args.project); return null;
        default: throw new Error(`Action '${args.action}' not valid for risk_appetite_profile`);
      }
    }
    case "risk_assessment_campaign": {
      switch (args.action) {
        case "create": return createRiskAssessmentCampaign(data, args.project);
        case "update": reqArg(args, "id", "update"); return updateRiskAssessmentCampaign(args.id, data, args.project);
        case "delete": reqArg(args, "id", "delete"); await deleteRiskAssessmentCampaign(args.id, args.project); return null;
        case "advance_phase":
          reqArg(args, "id", "advance_phase");
          reqArg(args, "phase", "advance_phase");
          return advanceRiskAssessmentCampaignPhase(args.id, args.phase, args.project);
        default: throw new Error(`Action '${args.action}' not valid for risk_assessment_campaign`);
      }
    }
    case "key_risk_indicator": {
      switch (args.action) {
        case "create": return createKeyRiskIndicator(data, args.project);
        case "update": reqArg(args, "id", "update"); return updateKeyRiskIndicator(args.id, data, args.project);
        case "delete": reqArg(args, "id", "delete"); await deleteKeyRiskIndicator(args.id, args.project); return null;
        case "record_measurement":
          reqArg(args, "id", "record_measurement");
          reqArg(args, "value", "record_measurement");
          return recordKriMeasurement(args.id, args.value, args.measured_at, args.project);
        default: throw new Error(`Action '${args.action}' not valid for key_risk_indicator`);
      }
    }
    default: throw new Error(`Unknown entity: ${args.entity}`);
  }
}

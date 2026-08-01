// Split from index.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Registration bodies are unchanged.

import { z } from "zod";
import {
  GOVERNANCE_FIELDS,
  MAPPING_CONTROL_ROLES,
  addMappingEvidenceRef,
  attachMappingObservation,
  createRiskControlMapping,
  createScopedControlImplementation,
  deleteRiskControlMapping,
  deleteScopedControlImplementation,
  detachMappingObservation,
  getAssessmentFeed,
  getThreatUnmappedControls,
  getUnmappedControls,
  getUnmappedRecords,
  getUnmappedScenarios,
  getUnmappedThreats,
  pick,
  reqArg,
  updateRiskControlMapping,
  updateScopedControlImplementation,
} from "../lib.js";
import {
  GC_RESEARCH_PROVENANCE_DESCRIPTION,
  gcResearchProvenanceToolHandler,
  gcResearchProvenanceZodShape,
} from "../gc-research-provenance.js";
import {
  GC_RESEARCH_OPERATION_AUTHORIZATION_DESCRIPTION,
  gcResearchOperationAuthorizationToolHandler,
  gcResearchOperationAuthorizationZodShape,
} from "../gc-research-operation-authorization.js";
import { GC_RISK_GOVERNANCE_DESCRIPTION, gcRiskGovernanceToolHandler, gcRiskGovernanceZodShape } from "../gc-risk-governance.js";
import { ok, err } from "./respond.js";

export const RISK_CONTROL_MAPPING_ACTIONS = [
  // SCI
  "sci-create", "sci-update", "sci-delete",
  // Mapping CRUD
  "create", "update", "delete",
  // Mapping observation/evidence (C8)
  "attach-observation", "detach-observation", "add-evidence",
  // Coverage queries (risk-side)
  "unmapped-scenarios", "unmapped-records", "unmapped-controls", "assessment-feed",
  // Coverage queries (threat-side, GC-H006)
  "unmapped-threats", "threat-unmapped-controls",
];


export function registerResearchProvenance(server, ctx) {
  server.tool(
    "gc_research_provenance",
    GC_RESEARCH_PROVENANCE_DESCRIPTION,
    gcResearchProvenanceZodShape,
    async (args) => {
      try {
        const result = await gcResearchProvenanceToolHandler(args);
        return ok(JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_research_operation_authorization",
    GC_RESEARCH_OPERATION_AUTHORIZATION_DESCRIPTION,
    gcResearchOperationAuthorizationZodShape,
    async (args) => {
      try {
        const result = await gcResearchOperationAuthorizationToolHandler(args);
        return ok(JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_risk_governance",
    GC_RISK_GOVERNANCE_DESCRIPTION,
    gcRiskGovernanceZodShape,
    async (args) => {
      try {
        const result = await gcRiskGovernanceToolHandler(args);
        return ok(result === null ? "Deleted" : JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_risk_control_mapping",
    `Risk-control mapping operations (GC-T003 / ADR-052, GC-H006). ` +
      `Actions: ${RISK_CONTROL_MAPPING_ACTIONS.join(", ")}. ` +
      `Reads (list, get) route through gc_query. ` +
      `control_role values: ${MAPPING_CONTROL_ROLES.join(", ")}. ` +
      `Analysis-side endpoint: exactly one of risk_scenario_id, risk_register_record_id, or threat_model_id (GC-H006).`,
    {
      action: z.enum(RISK_CONTROL_MAPPING_ACTIONS),
      id: z.string().uuid().optional(),
      project: z.string().optional(),
      // SCI fields
      uid: z.string().max(50).optional(),
      control_id: z.string().uuid().optional(),
      name: z.string().max(200).optional(),
      implementation_scope: z.string().optional(),
      // Mapping fields
      scoped_implementation_id: z.string().uuid().optional(),
      risk_scenario_id: z.string().uuid().optional(),
      risk_register_record_id: z.string().uuid().optional(),
      threat_model_id: z.string().uuid().optional(),  // GC-H006: threat-model analysis endpoint
      operational_asset_id: z.string().uuid().optional(),
      control_role: z.enum(MAPPING_CONTROL_ROLES).optional(),
      mapping_objective: z.string().optional(),
      mapping_scope: z.string().optional(),
      methodology_profile_id: z.string().uuid().optional(),
      methodology_influence: z.record(z.unknown()).optional(),
      // C8 fields
      observation_id: z.string().uuid().optional(),
      evidence_ref: z.string().optional(),
      evidence_note: z.string().optional(),
      evidence_artifact_id: z.string().uuid().optional(),
      // Coverage query options
      transitive: z.boolean().optional(),
      assessment_result_id: z.string().uuid().optional(),
    },
    async (args) => {
      try {
        const p = args.project;
        let result;
        switch (args.action) {
          // ---- SCI ----
          case "sci-create":
            result = await createScopedControlImplementation({
              uid: reqArg(args, "uid"), controlId: reqArg(args, "control_id"),
              name: reqArg(args, "name"), implementationScope: args.implementation_scope,
              operationalAssetId: args.operational_asset_id,
            }, p);
            break;
          case "sci-update":
            result = await updateScopedControlImplementation(reqArg(args, "id"), pick(args, {
              name: "name", implementationScope: "implementation_scope",
              operationalAssetId: "operational_asset_id",
            }), p);
            break;
          case "sci-delete":
            await deleteScopedControlImplementation(reqArg(args, "id"), p);
            return ok("Deleted");
          // ---- Mapping CRUD ----
          case "create":
            result = await createRiskControlMapping({
              controlId: args.control_id, scopedImplementationId: args.scoped_implementation_id,
              riskScenarioId: args.risk_scenario_id, riskRegisterRecordId: args.risk_register_record_id,
              threatModelId: args.threat_model_id,  // GC-H006
              operationalAssetId: args.operational_asset_id,
              controlRole: reqArg(args, "control_role"),
              mappingObjective: args.mapping_objective, mappingScope: args.mapping_scope,
              methodologyProfileId: args.methodology_profile_id,
              methodologyInfluence: args.methodology_influence,
            }, p);
            break;
          case "update":
            result = await updateRiskControlMapping(reqArg(args, "id"), pick(args, {
              controlRole: "control_role", mappingObjective: "mapping_objective",
              mappingScope: "mapping_scope", methodologyProfileId: "methodology_profile_id",
              methodologyInfluence: "methodology_influence",
            }), p);
            break;
          case "delete":
            await deleteRiskControlMapping(reqArg(args, "id"), p);
            return ok("Deleted");
          // ---- C8 ----
          case "attach-observation":
            result = await attachMappingObservation(reqArg(args, "id"), reqArg(args, "observation_id"), p);
            break;
          case "detach-observation":
            result = await detachMappingObservation(reqArg(args, "id"), reqArg(args, "observation_id"), p);
            break;
          case "add-evidence":
            result = await addMappingEvidenceRef(reqArg(args, "id"), {
              evidenceRef: reqArg(args, "evidence_ref"),
              evidenceNote: args.evidence_note,
              evidenceArtifactId: args.evidence_artifact_id,
            }, p);
            break;
          // ---- Coverage queries ----
          case "unmapped-scenarios":
            result = await getUnmappedScenarios(p);
            break;
          case "unmapped-records":
            result = await getUnmappedRecords(p, args.transitive !== false);
            break;
          case "unmapped-controls":
            result = await getUnmappedControls(p);
            break;
          case "assessment-feed":
            result = await getAssessmentFeed(reqArg(args, "assessment_result_id"), p);
            break;
          // ---- Threat-side coverage queries (GC-H006) ----
          case "unmapped-threats":
            result = await getUnmappedThreats(p);
            break;
          case "threat-unmapped-controls":
            result = await getThreatUnmappedControls(p);
            break;
          default:
            throw new Error(`Unknown action: ${args.action}`);
        }
        return ok(result === null ? "Deleted" : JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );
}

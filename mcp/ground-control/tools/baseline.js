// Split from index.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Registration bodies are unchanged.

import { z } from "zod";
import {
  COMPARISON_OPERATORS,
  LINK_TYPES,
  METRIC_TYPES,
  STATUSES,
  compareBaselines,
  createBaseline,
  createQualityGate,
  deleteBaseline,
  deleteQualityGate,
  evaluateQualityGates,
  getBaselineSnapshot,
  getTraceabilityMatrix,
  pick,
  reqArg,
  updateQualityGate,
} from "../lib.js";
import { GC_THREAT_MODEL_DESCRIPTION, gcThreatModelToolHandler, gcThreatModelZodShape } from "../gc-threat-model.js";
import { GC_FINDING_DESCRIPTION, gcFindingToolHandler, gcFindingZodShape } from "../gc-finding.js";
import { GC_EVIDENCE_DESCRIPTION, gcEvidenceToolHandler, gcEvidenceZodShape } from "../gc-evidence.js";
import { GC_AUDIT_DESCRIPTION, gcAuditToolHandler, gcAuditZodShape } from "../gc-audit.js";
import { GC_RISK_SCENARIO_DESCRIPTION, gcRiskScenarioToolHandler, gcRiskScenarioZodShape } from "../gc-risk-scenario.js";
import { GC_CONTROL_DESCRIPTION, gcControlToolHandler, gcControlZodShape } from "../gc-control.js";
import { GC_ASSET_DESCRIPTION, gcAssetToolHandler, gcAssetZodShape } from "../gc-asset.js";
import { GC_OBSERVATION_DESCRIPTION, gcObservationToolHandler, gcObservationZodShape } from "../gc-observation.js";
import { ok, err } from "./respond.js";

export const BASELINE_ACTIONS = ["create", "delete", "snapshot", "compare"];

export const QUALITY_GATE_ACTIONS = ["create", "update", "delete", "evaluate"];


export function registerBaseline(server, ctx) {
  server.tool(
    "gc_baseline",
    `Baseline operations. Actions: ${BASELINE_ACTIONS.join(", ")}. ` +
      `Reads (list, get) route through gc_query. Required: create→{name}; delete/snapshot→{id}; compare→{baseline_a, baseline_b}.`,
    {
      action: z.enum(BASELINE_ACTIONS),
      id: z.string().uuid().optional(),
      project: z.string().optional(),
      name: z.string().optional(),
      description: z.string().optional(),
      baseline_a: z.string().uuid().optional(),
      baseline_b: z.string().uuid().optional(),
    },
    async (args) => {
      try {
        const ENTITY_FIELDS = ["name", "description"];
        switch (args.action) {
          case "create": {
            reqArg(args, "name", "create");
            return ok(JSON.stringify(await createBaseline(pick(args, ENTITY_FIELDS), args.project), null, 2));
          }
          case "delete": {
            reqArg(args, "id", "delete");
            await deleteBaseline(args.id);
            return ok("Deleted");
          }
          case "snapshot": {
            reqArg(args, "id", "snapshot");
            return ok(JSON.stringify(await getBaselineSnapshot(args.id), null, 2));
          }
          case "compare": {
            reqArg(args, "baseline_a", "compare"); reqArg(args, "baseline_b", "compare");
            return ok(JSON.stringify(await compareBaselines(args.baseline_a, args.baseline_b), null, 2));
          }
          default: return err(new Error(`Unknown action: ${args.action}`));
        }
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_quality_gate",
    `Quality gate operations. Actions: ${QUALITY_GATE_ACTIONS.join(", ")}. ` +
      `Reads (list, get) route through gc_query. ` +
      `Required fields per action: create→{name,metric_type}; update/delete→{id}; evaluate→{} (no required fields; optional project scopes it).`,
    {
      action: z.enum(QUALITY_GATE_ACTIONS),
      id: z.string().uuid().optional(),
      project: z.string().optional(),
      name: z.string().optional(),
      description: z.string().optional(),
      metric_type: z.enum(METRIC_TYPES).optional(),
      comparison_operator: z.enum(COMPARISON_OPERATORS).optional(),
      threshold: z.number().optional(),
      enabled: z.boolean().optional(),
    },
    async (args) => {
      try {
        const ENTITY_FIELDS = ["name", "description", "metric_type", "comparison_operator", "threshold", "enabled"];
        switch (args.action) {
          case "create": {
            reqArg(args, "name", "create"); reqArg(args, "metric_type", "create");
            return ok(JSON.stringify(await createQualityGate(pick(args, ENTITY_FIELDS), args.project), null, 2));
          }
          case "update": {
            reqArg(args, "id", "update");
            return ok(JSON.stringify(await updateQualityGate(args.id, pick(args, ENTITY_FIELDS)), null, 2));
          }
          case "delete": {
            reqArg(args, "id", "delete");
            await deleteQualityGate(args.id);
            return ok("Deleted");
          }
          case "evaluate":
            return ok(JSON.stringify(await evaluateQualityGates(args.project), null, 2));
          default: return err(new Error(`Unknown action: ${args.action}`));
        }
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_asset",
    GC_ASSET_DESCRIPTION,
    gcAssetZodShape,
    async (args) => {
      try {
        const result = await gcAssetToolHandler(args);
        return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_observation",
    GC_OBSERVATION_DESCRIPTION,
    gcObservationZodShape,
    async (args) => {
      try {
        const result = await gcObservationToolHandler(args);
        return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_risk_scenario",
    GC_RISK_SCENARIO_DESCRIPTION,
    gcRiskScenarioZodShape,
    async (args) => {
      try {
        const result = await gcRiskScenarioToolHandler(args);
        return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_threat_model",
    GC_THREAT_MODEL_DESCRIPTION,
    gcThreatModelZodShape,
    async (args) => {
      try {
        const result = await gcThreatModelToolHandler(args);
        return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_finding",
    GC_FINDING_DESCRIPTION,
    gcFindingZodShape,
    async (args) => {
      try {
        const result = await gcFindingToolHandler(args);
        return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_audit",
    GC_AUDIT_DESCRIPTION,
    gcAuditZodShape,
    async (args) => {
      try {
        const result = await gcAuditToolHandler(args);
        return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_evidence",
    GC_EVIDENCE_DESCRIPTION,
    gcEvidenceZodShape,
    async (args) => {
      try {
        const result = await gcEvidenceToolHandler(args);
        return ok(JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_traceability_matrix",
    "Read-only Traceability Matrix view (GC-Q003). Returns a paged list of rows, " +
      "each pairing a requirement with its traceability links, for the matrix view. " +
      "This is a read that also routes through gc_query against " +
      "/api/v1/requirements/matrix. When linkType is set, only links of that type " +
      "are returned; requirements with no matching link still appear with an empty " +
      "links array. Optional filters: project, status (Status), wave (int), " +
      "linkType (LinkType), plus page and size pagination.",
    {
      project: z.string().optional(),
      status: z.enum(STATUSES).optional(),
      wave: z.number().int().optional(),
      linkType: z.enum(LINK_TYPES).optional(),
      page: z.number().int().nonnegative().optional(),
      size: z.number().int().positive().optional(),
    },
    async ({ project, status, wave, linkType, page, size }) => {
      try {
        const result = await getTraceabilityMatrix({ project, status, wave, linkType, page, size });
        return ok(JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_control",
    GC_CONTROL_DESCRIPTION,
    gcControlZodShape,
    async (args) => {
      try {
        const result = await gcControlToolHandler(args);
        return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
      } catch (e) { return err(e); }
    },
  );
}

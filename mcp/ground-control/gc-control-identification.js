// gc_control_identification: MCP adapter for GC-GRC-008 deterministic control
// identification and mapping. Reads are deterministic (no LLM): the same
// enumerated threats + installed controls produce identical candidate controls.
// Two read actions:
//   - identify (default): map enumerated threats -> candidate controls + gaps
//   - coverage: which controls cover a given threat (confirmed mappings)
// Confirmation (recording a threat->control mitigation) is a write and is
// performed through the REST /confirmations route or the existing gc_threat_model
// / gc_risk_control_mapping write tools; it is intentionally not exposed here.

import { z } from "zod";
import { controlIdentification, controlCoverage } from "./lib.js";

export const GC_CONTROL_IDENTIFICATION_DESCRIPTION =
  "Identify candidate controls for a project's enumerated threats (GC-GRC-008). " +
  "Deterministic mapping (no LLM): threat category -> control objective -> candidate controls, " +
  "drawing candidates from installed control packs (OSCAL catalogs) and existing project controls. " +
  "action='identify' (default) requires threatPackId (version pins a pack release, latest when omitted; " +
  "snapshotId targets a snapshot, latest when omitted) and returns candidates[]{producingRuleId, " +
  "ruleSetId, threatCategory, strideCategory, objectiveKey, threatRef, controlUid, source, packId, " +
  "implementationGuidance, matchedFacts, rationale} plus gaps[]{objectiveKey, threatRef, reason} for " +
  "threat categories with no matching control. action='coverage' requires threatModelId and returns the " +
  "controls recorded as covering that threat via RiskControlMapping and ThreatModelLink MITIGATED_BY.";

export const gcControlIdentificationZodShape = {
  action: z.enum(["identify", "coverage"]).optional(),
  project: z.string().optional(),
  threatPackId: z.string().min(1).max(200).optional(),
  version: z.string().max(100).optional(),
  snapshotId: z.string().uuid().optional(),
  threatModelId: z.string().uuid().optional(),
};

export async function gcControlIdentificationToolHandler(args) {
  const action = args.action || "identify";
  if (action === "coverage") {
    return controlCoverage({ project: args.project, threatModelId: args.threatModelId });
  }
  return controlIdentification({
    project: args.project,
    threatPackId: args.threatPackId,
    version: args.version,
    snapshotId: args.snapshotId,
  });
}

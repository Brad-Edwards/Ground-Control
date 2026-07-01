// gc_threat_enumeration: MCP adapter for GC-GRC-007 deterministic threat enumeration.
// Enumerates candidate threats from an architecture-model snapshot against a
// registered threat rule pack. Enumeration is deterministic (no LLM): same
// snapshot + same pinned pack version produces identical candidate sets.

import { z } from "zod";
import { threatEnumeration } from "./lib.js";

export const GC_THREAT_ENUMERATION_DESCRIPTION =
  "Enumerate candidate threats for a project (GC-GRC-007). " +
  "Runs the deterministic threat enumeration engine against an architecture-model snapshot " +
  "using the specified threat rule pack. " +
  "packId is required; version pins a specific pack release (latest when omitted); " +
  "snapshotId targets a specific snapshot (latest when omitted). " +
  "Returns candidates[]{producingRuleId, category, strideCategory, elementStableKey, " +
  "elementKind, matchedFacts, narrative} and limitations[]{reason, detail, elementStableKey}. " +
  "No LLM judgment is applied: the same model snapshot and pack version always produce " +
  "identical candidates. Rule packs of type THREAT_RULE_PACK are registered and versioned " +
  "via the admin pack-registry surface.";

export const gcThreatEnumerationZodShape = {
  project: z.string().optional(),
  packId: z.string().min(1).max(200),
  version: z.string().max(100).optional(),
  snapshotId: z.string().uuid().optional(),
};

export async function gcThreatEnumerationToolHandler(args) {
  return threatEnumeration({
    project: args.project,
    packId: args.packId,
    version: args.version,
    snapshotId: args.snapshotId,
  });
}

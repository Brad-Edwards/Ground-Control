-- GC-GRC-007: add threat_rule_entries column to pack_registry_entry for THREAT_RULE_PACK support.
--
-- The column stores the JSON-serialised list of RegisteredThreatRule records produced by
-- ThreatRulePackTypeHandler.applyRegistrationContent. It is nullable because existing
-- CONTROL_PACK, REQUIREMENTS_PACK, and CUSTOM entries do not carry threat rule content.
--
-- PackRegistryEntry is @Audited, so the Envers audit shadow table must also receive the column
-- so revision history is reconstructable (matching the V170 pattern for control_pack_entries).

ALTER TABLE pack_registry_entry ADD COLUMN threat_rule_entries TEXT;

ALTER TABLE pack_registry_entry_audit ADD COLUMN threat_rule_entries TEXT;

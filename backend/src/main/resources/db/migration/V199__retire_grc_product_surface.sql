-- ADR-089: Retire the composed GRC product surface (issue #1346).
--
-- Drops the tables owned by the aggregates that composed, derived, or enforced a GRC
-- conclusion rather than owning an independently useful primitive. Retained aggregates
-- (Control, ControlLink, ControlTest, EvidenceArtifact, Finding, Audit, Asset,
-- RiskScenario, ThreatModel, RiskControlMapping, ScopedControlImplementation,
-- VerificationResult, requirement traceability, graph) and their tables/audit tables are
-- untouched.
--
-- risk_control_mapping (kept) carries real FKs to risk_register_record and
-- methodology_profile (both dropped here). CASCADE drops only the dependent FK
-- constraints on risk_control_mapping -- the table, its other columns, and its data are
-- unaffected. The exactly-one-of-three ck_rcm_analysis_side CHECK constraint stays valid:
-- new rows simply never set risk_register_record_id again.
--
-- pack_registry_entry is shared generic infrastructure (also used by REQUIREMENTS_PACK
-- and CUSTOM packs) and is not dropped; only the retired CONTROL_PACK and
-- THREAT_RULE_PACK rows are deleted from it. Its Envers audit table is left untouched
-- per ADR-089 SS4 (historical rows for a kept, generic aggregate remain readable).

-- ---- GRC assessment run ----
DROP TABLE IF EXISTS grc_assessment_run_audit CASCADE;
DROP TABLE IF EXISTS grc_assessment_run CASCADE;

-- ---- Derivation facts (boundary + IaC/CI derivation adapters) ----
DROP TABLE IF EXISTS derivation_capture_limit_audit CASCADE;
DROP TABLE IF EXISTS derivation_capture_limit CASCADE;
DROP TABLE IF EXISTS system_model_fact_audit CASCADE;
DROP TABLE IF EXISTS system_model_fact CASCADE;
DROP TABLE IF EXISTS derivation_run_audit CASCADE;
DROP TABLE IF EXISTS derivation_run CASCADE;

-- ---- Boundary model ----
DROP TABLE IF EXISTS boundary_model_gap_audit CASCADE;
DROP TABLE IF EXISTS boundary_model_gap CASCADE;
DROP TABLE IF EXISTS boundary_model_assignment_audit CASCADE;
DROP TABLE IF EXISTS boundary_model_assignment CASCADE;
DROP TABLE IF EXISTS boundary_model_boundary_audit CASCADE;
DROP TABLE IF EXISTS boundary_model_boundary CASCADE;
DROP TABLE IF EXISTS boundary_model_snapshot_audit CASCADE;
DROP TABLE IF EXISTS boundary_model_snapshot CASCADE;

-- ---- Architecture model ----
DROP TABLE IF EXISTS architecture_model_element_state_audit CASCADE;
DROP TABLE IF EXISTS architecture_model_element_state CASCADE;
DROP TABLE IF EXISTS architecture_model_snapshot_audit CASCADE;
DROP TABLE IF EXISTS architecture_model_snapshot CASCADE;
DROP TABLE IF EXISTS architecture_model_element_audit CASCADE;
DROP TABLE IF EXISTS architecture_model_element CASCADE;

-- ---- Data classification lattice ----
DROP TABLE IF EXISTS data_classification_flow_rule_audit CASCADE;
DROP TABLE IF EXISTS data_classification_flow_rule CASCADE;
DROP TABLE IF EXISTS data_classification_label_audit CASCADE;
DROP TABLE IF EXISTS data_classification_label CASCADE;
DROP TABLE IF EXISTS data_classification_lattice_audit CASCADE;
DROP TABLE IF EXISTS data_classification_lattice CASCADE;

-- ---- Evidence campaign ----
DROP TABLE IF EXISTS evidence_campaign_run CASCADE;
DROP TABLE IF EXISTS evidence_campaign_audit CASCADE;
DROP TABLE IF EXISTS evidence_campaign CASCADE;

-- ---- Control pack ----
DROP TABLE IF EXISTS control_pack_override_audit CASCADE;
DROP TABLE IF EXISTS control_pack_override CASCADE;
DROP TABLE IF EXISTS control_pack_entry_audit CASCADE;
DROP TABLE IF EXISTS control_pack_entry CASCADE;
DROP TABLE IF EXISTS control_pack_audit CASCADE;
DROP TABLE IF EXISTS control_pack CASCADE;

-- ---- Risk appetite profile ----
DROP TABLE IF EXISTS risk_appetite_profile_audit CASCADE;
DROP TABLE IF EXISTS risk_appetite_profile CASCADE;

-- ---- Control effectiveness assessment (riskscenarios-satellite per ADR-089, lives
--      under domain/controls in Java but is retired with the other satellites) ----
DROP TABLE IF EXISTS control_effectiveness_assessment_audit CASCADE;
DROP TABLE IF EXISTS control_effectiveness_assessment CASCADE;

-- ---- Risk assessment / treatment / methodology / register-record family ----
-- (risk_scenario itself is NOT in this list -- it stays.)
DROP TABLE IF EXISTS risk_assessment_result_observation_audit CASCADE;
DROP TABLE IF EXISTS risk_assessment_result_observation CASCADE;
DROP TABLE IF EXISTS risk_assessment_result_audit CASCADE;
DROP TABLE IF EXISTS risk_assessment_result CASCADE;
DROP TABLE IF EXISTS treatment_plan_audit CASCADE;
DROP TABLE IF EXISTS treatment_plan CASCADE;
DROP TABLE IF EXISTS risk_register_record_scenario_audit CASCADE;
DROP TABLE IF EXISTS risk_register_record_scenario CASCADE;
DROP TABLE IF EXISTS risk_register_record_audit CASCADE;
DROP TABLE IF EXISTS risk_register_record CASCADE;
DROP TABLE IF EXISTS methodology_profile_audit CASCADE;
DROP TABLE IF EXISTS methodology_profile CASCADE;

-- ---- Shared pack registry: remove retired-type rows only (table stays; it is also
--      used by REQUIREMENTS_PACK and CUSTOM packs, which are not retired) ----
DELETE FROM pack_registry_entry WHERE pack_type IN ('CONTROL_PACK', 'THREAT_RULE_PACK');

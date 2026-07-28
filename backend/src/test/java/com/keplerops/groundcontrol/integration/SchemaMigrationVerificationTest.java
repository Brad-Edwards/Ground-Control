package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Asserts the applied Flyway migration set and the schema shape it leaves behind.
 *
 * Split out of RequirementsE2EIntegrationTest under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). It reads flyway_schema_history and the
 * catalogue only, so unlike the rest of that class it depends on nothing an
 * earlier test left behind and does not belong in its ordered chain.
 */
class SchemaMigrationVerificationTest extends BaseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationVerification() throws Exception {
        List<String> versions = new ArrayList<>();
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString("version"));
            }
        }
        // Flyway immutability: an applied versioned migration's content is frozen and its checksum is
        // validated on every startup. Never edit an applied V*.sql in place; append a new forward
        // migration instead. Editing the already-applied V043/V045 (instead of relying on the forward
        // V138 realignment) crashed a prod deploy on a checksum mismatch that fresh CI databases miss.
        assertThat(versions)
                .containsExactly(
                        "001",
                        "002",
                        "003",
                        "004",
                        "005",
                        "006",
                        "007",
                        "008",
                        "009",
                        "010",
                        "011",
                        "012",
                        "013",
                        "014",
                        "015",
                        "016",
                        "017",
                        "018",
                        "019",
                        "020",
                        "021",
                        "022",
                        "023",
                        "024",
                        "025",
                        "026",
                        "027",
                        "028",
                        "029",
                        "030",
                        "031",
                        "032",
                        "033",
                        "034",
                        "035",
                        "036",
                        "037",
                        "038",
                        "039",
                        "040",
                        "041",
                        "042",
                        "043",
                        "044",
                        "045",
                        "046",
                        "047",
                        "048",
                        "049",
                        "050",
                        "051",
                        "052", // V052: control pack tables
                        "053", // V053: pack registry tables
                        "054", // V054: typed control-pack registry payloads
                        "055", // V055: threat_model
                        "056", // V056: threat_model_audit
                        "057", // V057: threat_model_link (target_url / target_title NOT NULL DEFAULT '')
                        "058", // V058: threat_model_link_audit
                        "059", // V059: ADR-037 users + authorities (browser session JDBC store)
                        "060", // V060: finding (GC-V001 / ADR-038)
                        "061", // V061: finding_audit
                        "062", // V062: finding_link (target_url / target_title NOT NULL DEFAULT '')
                        "063", // V063: finding_link_audit
                        "064", // V064: drop ON DELETE CASCADE on asset_link / control_link / risk_scenario_link /
                        // threat_model_link FKs (Envers audit gap)
                        "065", // V065: control_test (GC-I012 / ADR-039)
                        "066", // V066: control_test_audit
                        "067", // V067: control_effectiveness_assessment (GC-I013 / ADR-039)
                        "068", // V068: control_effectiveness_assessment_audit
                        "069", // V069: operational_asset ownership/criticality/scope (GC-M012)
                        "070", // V070: operational_asset_audit parity for GC-M012
                        "071", // V071: test_case (TC-001 / ADR-040)
                        "072", // V072: test_case_audit
                        "073", // V073: test_case_step (TC-002)
                        "074", // V074: test_case_step_audit
                        "075", // V075: forward-fix V072 missing timestamp columns on test_case_audit
                        "076", // V076: add test_case.format discriminator (TC-004)
                        "077", // V077: add test_case_audit.format parity column
                        "078", // V078: create test_case_gherkin
                        "079", // V079: create test_case_gherkin_audit
                        "080", // V080: operational_asset subtype + metadata (GC-M011)
                        "081", // V081: operational_asset_audit parity for GC-M011
                        "082", // V082: asset_subtype_schema (GC-M011 registry)
                        "083", // V083: asset_subtype_schema_audit
                        "084", // V084: create test_case_folder (TC-005 / ADR-043)
                        "085", // V085: create test_case_folder_audit
                        "086", // V086: add test_case.parent_folder_id + sort_order
                        "087", // V087: add test_case_audit.parent_folder_id + sort_order
                        "088", // V088: create test_plan (TC-006 / ADR-044)
                        "089", // V089: create test_plan_audit
                        "090", // V090: create evidence_artifact (GC-M016 / ADR-045)
                        "091", // V091: create evidence_artifact_audit
                        "092", // V092: add_asset_knowledge_state (GC-M018 / ADR-046)
                        "093", // V093: add_asset_knowledge_state_audit
                        "094", // V094: create test_suite (TC-007 / ADR-047)
                        "095", // V095: create test_suite_audit
                        "096", // V096: create test_suite_member
                        "097", // V097: create test_suite_member_audit
                        "098", // V098: create test_suite_source_requirement
                        "099", // V099: create test_suite_source_requirement_audit
                        "100", // V100: create audit (GC-U001 / ADR-048 audit-entity-boundary)
                        "101", // V101: create audit_audit
                        "102", // V102: create audit_link
                        "103", // V103: create audit_link_audit
                        "104", // V104: migrate legacy EVIDENCE links to EXTERNAL (GC-L006)
                        "110", // V110: create test_run (TC-008 / ADR-049)
                        "111", // V111: create test_run_audit
                        "112", // V112: create test_run_tester_assignment
                        "113", // V113: create test_run_tester_assignment_audit
                        "114", // V114: create test_run_case_result
                        "115", // V115: create test_run_case_result_audit
                        "116", // V116: create test_run_step_result (TC-009 / ADR-050)
                        "117", // V117: create test_run_step_result_audit
                        "118", // V118: add test_run cursor columns
                        "119", // V119: create scoped_control_implementation (GC-T003 C1)
                        "120", // V120: create scoped_control_implementation_audit
                        "121", // V121: create risk_control_mapping + mapping_observation + mapping_evidence
                        "122", // V122: create risk_control_mapping_audit
                        "123", // V123: create mapping_evidence_audit (Envers @ElementCollection shadow)
                        "124", // V124: add treatment_plan methodology binding columns (GC-T004 C5)
                        "125", // V125: add methodology_profile treatment_strategy_vocabulary (GC-T004 C5)
                        "126", // V126: add risk_assessment_result reassessment_required_at (GC-T004 C8)
                        "127", // V127: FAIR-CRST rename risk_scenario axes + drop vulnerability (GC-T013)
                        "128", // V128: expand seeded NIST SP 800-30 Rev. 1 profile schema (GC-T014)
                        "129", // V129: add methodology_profile crosswalk_entries column (GC-T012)
                        "130", // V130: add methodology_profile_aud crosswalk_entries column (GC-T012 audit parity)
                        "131", // V131: add project.type + create research_intake (ADR-056, #999)
                        "132", // V132: create research_intake_audit (ADR-056, #999)
                        "133", // V133: create derivation run/fact/capture-limit tables (GC-GRC-001)
                        "134", // V134: create derivation audit tables (GC-GRC-001)
                        "135", // V135: create mcp_tool_event (issue #1104 / ADR-059)
                        "136", // V136: extend FAIR input schema with contact_frequency, probability_of_action,
                        // threat_capability, resistance_strength sub-factors (GC-T011)
                        "137", // V137: add threat_model_id to risk_control_mapping (GC-H006)
                        "138", // V138: align FAIR/NIST profile source semantics
                        "139", // V139: O-RT forms-of-loss materiality + stakeholder secondary effects (GC-T016)
                        "140", // V140: create risk_appetite_profile (GC-T005, #260)
                        "141", // V141: create risk_appetite_profile_audit (GC-T005 audit parity)
                        "142", // V142: create workflow_run telemetry reporting tables (#859, ADR-061)
                        "143", // V143: create age_graph_snapshot pointer/metadata (#252, ADR-062)
                        "144", // V144: create research_run lifecycle aggregate (#1000, ADR-064)
                        "145", // V145: create research_run_audit (#1000 audit parity)
                        "146", // V146: create research_run_artifact manifest (#1000, ADR-064)
                        "147", // V147: create research_run_artifact_audit (#1000 audit parity)
                        "148", // V148: create research_run_gate policy/decision (#1000, ADR-064)
                        "149", // V149: create research_run_gate_audit (#1000 audit parity)
                        "150", // V150: create canonical boundary model snapshot (GC-GRC-004)
                        "151", // V151: create canonical boundary model audit tables (GC-GRC-004)
                        "152", // V152: create research_run_gate_decision_log (#1001, ADR-066)
                        "153", // V153: create research_run_gate_decision_log_audit (#1001 audit parity)
                        "154", // V154: create research_run_review_comment (#1001, ADR-066)
                        "155", // V155: create research_run_review_comment_audit (#1001 audit parity)
                        "156", // V156: create research_run_rationale_entry (#1001, ADR-067)
                        "157", // V157: create research_run_rationale_entry_audit (#1001 audit parity)
                        "158", // V158: create research_run_disclosure (#1001, ADR-068)
                        "159", // V159: create research_run_disclosure_audit (#1001 audit parity)
                        "160", // V160: create research_run_disclosure_entry (#1001, ADR-068)
                        "161", // V161: create research_run_disclosure_entry_audit (#1001 audit parity)
                        "162", // V162: create research_provenance_node (#1002, ADR-069)
                        "163", // V163: create research_provenance_node_audit (#1002 audit parity)
                        "164", // V164: create research_provenance_edge (#1002, ADR-069)
                        "165", // V165: create research_provenance_edge_audit (#1002 audit parity)
                        "166", // V166: create architecture model aggregate (GC-GRC-005)
                        "167", // V167: create architecture model audit tables (GC-GRC-005)
                        "168", // V168: migrate legacy architecture-model threat links (GC-GRC-005)
                        "169", // V169: create data classification lattice aggregate (GC-GRC-006)
                        "170", // V170: create data classification lattice audit tables (GC-GRC-006)
                        "171", // V171: add threat_rule_entries column to pack_registry_entry (GC-GRC-007)
                        "172", // V172: create evidence_campaign aggregate (GC-S005)
                        "173", // V173: create evidence_campaign_audit shadow (GC-S005 audit parity)
                        "174", // V174: create evidence_campaign_run telemetry (GC-S005)
                        "175", // V175: create research_run_methodology_selection (#1005, GC-RSCH-F006)
                        "176", // V176: create research_run_methodology_selection_audit (#1005 audit parity)
                        "177", // V177: create research_run_methodology_source (#1005, GC-RSCH-F006)
                        "178", // V178: create research_run_methodology_source_audit (#1005 audit parity)
                        "179", // V179: create methodology_requirements_contract (#1006, ADR-080)
                        "180", // V180: create methodology_requirements_contract_audit (#1006 audit parity)
                        "181", // V181: create methodology_requirements_contract_entry (#1006, ADR-080)
                        "182", // V182: create methodology_requirements_contract_entry_source_link (#1006)
                        "183", // V183: create methodology_requirements_contract_rejected_alternative (#1006)
                        "184", // V184: create protocol_plan (#1007, ADR-083)
                        "185", // V185: create protocol_plan_audit (#1007 audit parity)
                        "186", // V186: create protocol_plan_coverage (#1007, ADR-083)
                        "187", // V187: create protocol_plan_section (#1007, ADR-083)
                        "188", // V188: research_run operation policy snapshot (#1008, ADR-086)
                        "189", // V189: research_run policy snapshot audit (#1008 audit parity)
                        "190", // V190: research_intake egress policy (#1008, ADR-086)
                        "191", // V191: research_intake egress policy audit (#1008 audit parity)
                        "192", // V192: research_run_artifact data class (#1008, ADR-086)
                        "193", // V193: research_run_artifact data class audit (#1008 audit parity)
                        "194", // V194: create research_run_operation_authorization (#1008, ADR-086)
                        "195", // V195: research_run_operation_authorization audit (#1008 audit parity)
                        "196", // V196: create grc_assessment_run (GC-GRC-016, #1129)
                        "197", // V197: grc_assessment_run audit (GC-GRC-016 audit parity)
                        "198", // V198: operator_signal_audit append-only log (GC-O009 (b), #1279)
                        "199", // V199: retire GRC product surface (ADR-089, #1346)
                        "200", // V200: drops V198 with the Temporal orchestration lane (#1359)
                        "201", // V201: age_graph_snapshot.source_revision (#1309, ADR-084 §5)
                        "202", // V202: document_audit — Document joins the audited spine (#1309)
                        "203", // V203: workflow telemetry audit shadows for graph projection (#1311)
                        "204", // V204: workflow_phase_event.source_id deterministic identity (#1435)
                        "205", // V205: identity/RBAC foundation + compatibility role seed (#1282)
                        "206", // V206: identity/RBAC Envers audit shadows (#1282)
                        "207", // V207: station-result axis + gate-finding projection (#1355)
                        "208"); // V208: workflow_gate_finding Envers shadow (#1355)
    }
}

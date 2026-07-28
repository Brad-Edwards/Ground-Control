package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Split from MigrationSmokeTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
class MigrationSmokeTest extends BaseIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    /**
     * Tables the migration set must leave queryable.
     *
     * One list plus one assertion replaces the 96 identical
     * {@code SELECT 1 FROM <table> LIMIT 1} probes this test used to carry
     * (issue #1467). The list is the readable inventory of what is covered,
     * and a missing table now names itself in the failure instead of the run
     * stopping at the first one.
     */
    private static final List<String> AUDITED_TABLES = List.of(
            "requirement",
            "requirement_audit",
            "requirement_relation_audit",
            "revinfo",
            "traceability_link",
            "github_issue_sync",
            "requirement_import",
            "traceability_link_audit",
            "identity_user",
            "identity_user_audit",
            "identity_group_audit",
            "group_membership_audit",
            "identity_role_audit",
            "role_permission_assignment_audit",
            "role_grant_audit",
            "project_access_grant_audit",
            "operational_asset",
            "operational_asset_audit",
            "asset_relation",
            "asset_relation_audit",
            "asset_link",
            "asset_link_audit",
            "asset_external_id",
            "asset_external_id_audit",
            "observation",
            "observation_audit",
            "risk_scenario",
            "risk_scenario_audit",
            "risk_scenario_link",
            "risk_scenario_link_audit",
            "control",
            "control_audit",
            "control_link",
            "control_link_audit",
            "github_pr_sync",
            "verification_result",
            "verification_result_audit",
            "registered_plugin",
            "pack_registry_entry",
            "pack_registry_entry_audit",
            "pack_install_record",
            "pack_install_record_audit",
            "trust_policy",
            "trust_policy_audit",
            "threat_model",
            "threat_model_audit",
            "threat_model_link",
            "threat_model_link_audit",
            "users",
            "authorities",
            "finding",
            "finding_audit",
            "finding_link",
            "finding_link_audit",
            "control_test",
            "control_test_audit",
            "test_case",
            "test_case_audit",
            "test_case_step",
            "test_case_step_audit",
            "test_case_gherkin",
            "test_case_gherkin_audit",
            "asset_subtype_schema",
            "asset_subtype_schema_audit",
            "test_case_folder",
            "test_case_folder_audit",
            "test_plan",
            "test_plan_audit",
            "test_suite",
            "test_suite_audit",
            "test_suite_member",
            "test_suite_member_audit",
            "test_suite_source_requirement",
            "test_suite_source_requirement_audit",
            "audit",
            "audit_audit",
            "audit_link",
            "audit_link_audit",
            "test_run",
            "test_run_audit",
            "test_run_tester_assignment",
            "test_run_tester_assignment_audit",
            "test_run_case_result",
            "test_run_case_result_audit",
            "test_run_step_result",
            "test_run_step_result_audit",
            "scoped_control_implementation",
            "scoped_control_implementation_audit",
            "risk_control_mapping",
            "risk_control_mapping_audit",
            "mapping_observation",
            "mapping_evidence",
            "mapping_evidence_audit",
            "research_intake",
            "research_intake_audit",
            "mcp_tool_event");

    @Test
    void contextLoads() throws Exception {
        // Spring context boots successfully with ddl-auto: validate. The
        // assertion makes the pass/fail explicit so a future config change
        // that silently disables validation (ddl-auto: none / create) still
        // produces a real signal — empty test bodies pass even when the
        // intended schema-correctness guarantee has been lost.
        try (var conn = dataSource.getConnection()) {
            assertThat(conn.isValid(1)).isTrue();
        }
    }

    @Test
    void allFlywayMigrationsRan() throws Exception {
        List<String> versions = new ArrayList<>();
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString("version"));
            }
        }
        // V139: O-RT forms-of-loss materiality schema seed update for FAIR_V3_0 (GC-T016) — schema-only, no DDL.
        // V144–V149: research-run lifecycle aggregate, artifact manifest, gate rows + their audit shadows
        // (#1000, ADR-064 / ADR-065).
        // V150–V151: canonical boundary model snapshot + audit shadows (GC-GRC-004).
        // V152–V161: #1001 research decision-log / review-comments / rationale-ledger / disclosure
        // (+ disclosure entries) + their audit shadows (ADR-066 / ADR-067 / ADR-068).
        // V162–V165: #1002 research provenance ledger node + edge + their audit shadows (ADR-069).
        // V166–V168: architecture model aggregate + audit shadows + legacy link compatibility (GC-GRC-005).
        // V169–V170: data classification lattice aggregate + audit shadows (GC-GRC-006).
        // V171: add threat_rule_entries column to pack_registry_entry (GC-GRC-007).
        // V172–V174: scheduled evidence-collection campaign + audit shadow + campaign-run telemetry (GC-S005).
        // Flyway immutability: once a versioned migration has been applied to a long-lived database
        // (e.g. production) its file content is frozen — the checksum is validated on every startup.
        // Never edit an applied V*.sql in place; append a new forward migration instead. Editing the
        // already-applied V043/V045 (rather than relying solely on the forward V138 realignment)
        // crashed a prod deploy on a checksum mismatch that fresh CI databases could not catch.
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
                        "052",
                        "053",
                        "054",
                        "055",
                        "056",
                        "057",
                        "058",
                        "059",
                        "060",
                        "061",
                        "062",
                        "063",
                        "064",
                        "065",
                        "066",
                        "067",
                        "068",
                        "069",
                        "070",
                        "071",
                        "072",
                        "073",
                        "074",
                        "075",
                        "076",
                        "077",
                        "078",
                        "079",
                        "080",
                        "081",
                        "082",
                        "083",
                        "084",
                        "085",
                        "086",
                        "087",
                        "088",
                        "089",
                        "090",
                        "091",
                        "092",
                        "093",
                        "094",
                        "095",
                        "096",
                        "097",
                        "098",
                        "099",
                        "100",
                        "101",
                        "102",
                        "103",
                        "104",
                        "110",
                        "111",
                        "112",
                        "113",
                        "114",
                        "115",
                        "116",
                        "117",
                        "118",
                        "119",
                        "120",
                        "121",
                        "122",
                        "123",
                        "124",
                        "125",
                        "126",
                        "127",
                        "128",
                        "129",
                        "130",
                        "131",
                        "132",
                        "133",
                        "134",
                        "135",
                        "136",
                        "137",
                        "138",
                        "139",
                        "140",
                        "141",
                        "142",
                        "143",
                        "144",
                        "145",
                        "146",
                        "147",
                        "148",
                        "149",
                        "150",
                        "151",
                        "152",
                        "153",
                        "154",
                        "155",
                        "156",
                        "157",
                        "158",
                        "159",
                        "160",
                        "161",
                        "162",
                        "163",
                        "164",
                        "165",
                        "166",
                        "167",
                        "168",
                        "169",
                        "170",
                        "171",
                        "172",
                        "173",
                        "174",
                        "175",
                        "176",
                        "177",
                        "178",
                        "179",
                        "180",
                        "181",
                        "182",
                        "183",
                        "184",
                        "185",
                        "186",
                        "187",
                        // V188-V195 (#1008, ADR-086): research high-risk operation authorization —
                        // run policy snapshot, intake egress policy, artifact data class, and the
                        // operation-authorization table (each with its Envers audit shadow).
                        "188",
                        "189",
                        "190",
                        "191",
                        "192",
                        "193",
                        "194",
                        "195",
                        // V196-V197 (#1129, GC-GRC-016): durable on-demand GRC assessment lane run
                        // record and Envers audit shadow.
                        "196",
                        "197",
                        // V198 (#1279, GC-O009 (b)): append-only operator-signal audit log (no Envers
                        // shadow — it IS the audit log).
                        "198",
                        // V199 (#1346, ADR-089): retire the composed GRC product surface — drops the
                        // tables owned by grc_assessment_run, derivation facts, boundary model,
                        // architecture model, data classification lattice, evidence campaign, control
                        // pack, risk appetite profile, control effectiveness assessment, and the
                        // risk_assessment_result/treatment_plan/risk_register_record/methodology_profile
                        // family, plus their audit shadows; deletes retired-type rows from the shared
                        // pack_registry_entry table.
                        "199",
                        // V200 (#1359): drops V198's operator-signal audit log with the Temporal lane.
                        "200",
                        // V201 (#1309, ADR-084 §5): age_graph_snapshot.source_revision — binds a graph
                        // snapshot to the Envers revision visible to its publishing transaction.
                        "201",
                        // V202 (#1309, ADR-084 §5): document_audit — Document joins the audited spine.
                        "202",
                        // V203 (#1311, ADR-061 amendment): workflow reporting entities join the
                        // audited graph time spine.
                        "203",
                        // V204 (#1435): workflow_phase_event.source_id — deterministic identity so
                        // live emission and issue-thread backfill converge on one row per attempt.
                        "204",
                        // V205-V206 (#1282, GC-P024): identity/RBAC foundation, deterministic
                        // compatibility roles, and matching Envers audit shadows.
                        "205",
                        "206",
                        // V207-V208 (#1355, ADR-090 amendment): the station-result axis and the
                        // gate-finding projection, with its Envers shadow.
                        "207",
                        "208");
    }

    @Test
    void identityMigrationSeedsCompatibilityRolesWithoutImportingLegacyPrincipals() throws Exception {
        try (var conn = dataSource.getConnection();
                var statement = conn.createStatement()) {
            try (var rs = statement.executeQuery(
                    "SELECT role_key FROM identity_role WHERE built_in = true ORDER BY role_key")) {
                List<String> roleKeys = new ArrayList<>();
                while (rs.next()) {
                    roleKeys.add(rs.getString(1));
                }
                assertThat(roleKeys).containsExactly("ADMIN", "USER");
            }
            try (var rs = statement.executeQuery("SELECT count(*) FROM identity_user")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
        }
    }

    @Test
    @Transactional
    void auditTablesExist() {
        // Every audited table must exist. information_schema is queried once
        // with a constant statement, so this builds no SQL from identifiers.
        List<String> presentTables =
                entityManager
                        .createNativeQuery("SELECT table_name FROM information_schema.tables")
                        .getResultList()
                        .stream()
                        .map(String::valueOf)
                        .toList();
        assertThat(presentTables).containsAll(AUDITED_TABLES);
    }
}

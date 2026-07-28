package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Split from MigrationSmokeTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
class MigrationSmokeAgeGraphSnapshotTableExistsTest extends BaseIntegrationTest {
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

    /**
     * V143: AGE graph projection snapshot pointer/metadata (#252 / ADR-062). Plain relational
     * bookkeeping (no AGE dependency); the active snapshot is the greatest-version row. ddl-auto
     * does not own this table (it is managed via JdbcTemplate), so probe the table, the version
     * sequence, and the non-negative count CHECK constraints explicitly here.
     */
    @Test
    @Transactional
    void ageGraphSnapshotTableExists() {
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT version, graph_name, scope, node_count, edge_count,"
                                + " published_at, published_by FROM age_graph_snapshot LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT nextval('age_graph_snapshot_version_seq')")
                        .getSingleResult())
                .doesNotThrowAnyException();
        var snapshotChecks = entityManager
                .createNativeQuery("SELECT string_agg(pg_get_constraintdef(c.oid), ' ')"
                        + " FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid"
                        + " WHERE t.relname = 'age_graph_snapshot' AND c.contype = 'c'")
                .getSingleResult();
        assertThat(snapshotChecks.toString())
                .as("age_graph_snapshot must CHECK non-negative node/edge counts")
                .contains("node_count")
                .contains("edge_count");
    }
}

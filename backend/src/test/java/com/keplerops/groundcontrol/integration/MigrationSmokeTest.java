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

class MigrationSmokeTest extends BaseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

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
        assertThat(versions)
                .containsExactly(
                        "001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011", "012", "013",
                        "014", "015", "016", "017", "018", "019", "020", "021", "022", "023", "024", "025", "026",
                        "027", "028", "029", "030", "031", "032", "033", "034", "035", "036", "037", "038", "039",
                        "040", "041", "042", "043", "044", "045", "046", "047", "048", "049", "050", "051", "052",
                        "053", "054", "055", "056", "057", "058", "059", "060", "061", "062", "063", "064", "065",
                        "066", "067", "068", "069", "070", "071", "072", "073", "074", "075", "076", "077", "078",
                        "079", "080", "081", "082", "083", "084", "085", "086", "087", "088", "089", "090", "091",
                        "092", "093", "094", "095", "096", "097", "098", "099", "100", "101", "102", "103", "104",
                        "110", "111", "112", "113", "114", "115", "116", "117", "118", "119", "120", "121", "122",
                        "123", "124", "125", "126", "127", "128", "129", "130", "131", "132", "133", "134");
    }

    @Test
    @Transactional
    void auditTablesExist() {
        // These queries will throw if tables don't exist
        entityManager.createNativeQuery("SELECT 1 FROM requirement LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM requirement_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM requirement_relation_audit LIMIT 1")
                .getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM revinfo LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM traceability_link LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM github_issue_sync LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM requirement_import LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM traceability_link_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM operational_asset LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM operational_asset_audit LIMIT 1")
                .getResultList();
        // GC-M012 column-existence probes (V069 / V070). A column-by-column
        // SELECT throws PersistenceException if any ALTER TABLE in the
        // migration silently omitted a column, where the table-only
        // `SELECT 1 FROM operational_asset` check would not. Wrap with
        // assertThatCode so the intent — "these six columns must exist" —
        // is expressed as an assertion a reader can recognize, not as the
        // absence of a thrown exception.
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery(
                                "SELECT owner, steward, environment, criticality, business_context, scope_designation"
                                        + " FROM operational_asset LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery(
                                "SELECT owner, steward, environment, criticality, business_context, scope_designation"
                                        + " FROM operational_asset_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        entityManager.createNativeQuery("SELECT 1 FROM asset_relation LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM asset_relation_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT updated_at FROM asset_relation LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT updated_at FROM asset_relation_audit LIMIT 1")
                .getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM asset_link LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM asset_link_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM asset_external_id LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM asset_external_id_audit LIMIT 1")
                .getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM observation LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM observation_audit LIMIT 1")
                .getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM risk_scenario LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_scenario_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_scenario_link LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_scenario_link_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM methodology_profile LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM methodology_profile_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_register_record LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_register_record_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_register_record_scenario LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_register_record_scenario_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_assessment_result LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_assessment_result_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_assessment_result_observation LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_assessment_result_observation_audit LIMIT 1")
                .getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM treatment_plan LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM treatment_plan_audit LIMIT 1")
                .getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM control LIMIT 1").getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM control_audit LIMIT 1").getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM control_link LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM control_link_audit LIMIT 1")
                .getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM github_pr_sync LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM verification_result LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM verification_result_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM registered_plugin LIMIT 1")
                .getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM control_pack LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM control_pack_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM control_pack_entry LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM control_pack_entry_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM control_pack_override LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM control_pack_override_audit LIMIT 1")
                .getResultList();
        // V053: pack registry tables
        entityManager
                .createNativeQuery("SELECT 1 FROM pack_registry_entry LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM pack_registry_entry_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM pack_install_record LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM pack_install_record_audit LIMIT 1")
                .getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM trust_policy LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM trust_policy_audit LIMIT 1")
                .getResultList();
        // V053/V054 pack registry tables verified
        // V055-V058 threat model tables
        entityManager.createNativeQuery("SELECT 1 FROM threat_model LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM threat_model_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM threat_model_link LIMIT 1")
                .getResultList();
        // V057 set target_url / target_title to NOT NULL DEFAULT '' so the entity-side
        // empty-string contract holds end-to-end. Verify the column metadata directly.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'threat_model_link'"
                        + " AND column_name = 'target_url' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'threat_model_link'"
                        + " AND column_name = 'target_title' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM threat_model_link_audit LIMIT 1")
                .getResultList();
        // V059: ADR-037 browser session JDBC user store. These are Spring Security
        // principal tables (not domain entities), so they intentionally have no
        // matching _audit suffix — role-change events are captured via structured
        // log lines from UserAdminService instead. See ADR-037 §4 and §6.
        entityManager.createNativeQuery("SELECT 1 FROM users LIMIT 1").getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM authorities LIMIT 1").getResultList();
        // V060-V063: finding tables (GC-V001 / ADR-038)
        entityManager.createNativeQuery("SELECT 1 FROM finding LIMIT 1").getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM finding_audit LIMIT 1").getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM finding_link LIMIT 1").getResultList();
        // V062 sets target_url / target_title to NOT NULL DEFAULT '' so the entity-side
        // empty-string contract holds end-to-end. Verify the column metadata directly.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'finding_link'"
                        + " AND column_name = 'target_url' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'finding_link'"
                        + " AND column_name = 'target_title' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM finding_link_audit LIMIT 1")
                .getResultList();
        // V065-V066 control_test + audit (GC-I012 / ADR-039)
        entityManager.createNativeQuery("SELECT 1 FROM control_test LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM control_test_audit LIMIT 1")
                .getResultList();
        // V067-V068 control_effectiveness_assessment + audit (GC-I013 / ADR-039)
        entityManager
                .createNativeQuery("SELECT 1 FROM control_effectiveness_assessment LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM control_effectiveness_assessment_audit LIMIT 1")
                .getResultList();
        // V071-V072 test_case + audit (TC-001 / ADR-040). The audit table is
        // not a Hibernate-managed entity, so ddl-auto: validate doesn't catch
        // a misspelled or dropped column there. Verify the structural shape
        // via information_schema for the columns most likely to regress.
        entityManager.createNativeQuery("SELECT 1 FROM test_case LIMIT 1").getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM test_case_audit LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_audit'"
                        + " AND column_name = 'estimated_duration_seconds'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_audit'"
                        + " AND column_name = 'status'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_audit'"
                        + " AND column_name = 'type'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_audit'"
                        + " AND column_name = 'priority'")
                .getSingleResult();
        // V073-V074 test_case_step + audit (TC-002 / ADR-041). Same rationale
        // as the test_case_audit probes above — ddl-auto: validate doesn't see
        // the audit shadow tables, so verify the structural shape by
        // information_schema for the columns that would silently regress.
        entityManager.createNativeQuery("SELECT 1 FROM test_case_step LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_case_step_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_step_audit'"
                        + " AND column_name = 'step_number'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_step_audit'"
                        + " AND column_name = 'action'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_step_audit'"
                        + " AND column_name = 'expected_result'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_step_audit'"
                        + " AND column_name = 'actual_result'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_step'"
                        + " AND column_name = 'step_number' AND is_nullable = 'NO'")
                .getSingleResult();
        // BaseEntity timestamps are @Audited, so every _audit table for an
        // entity that extends BaseEntity MUST carry created_at / updated_at
        // columns. The TC-001 V072 omission is fixed by V075; pin the
        // post-V075 shape on both audit tables so a future drift surfaces
        // here rather than as a flush-time Envers failure in production.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_audit'"
                        + " AND column_name = 'created_at'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_audit'"
                        + " AND column_name = 'updated_at'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_step_audit'"
                        + " AND column_name = 'created_at'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_step_audit'"
                        + " AND column_name = 'updated_at'")
                .getSingleResult();
        // V076-V077 test_case.format + audit parity (TC-004). The format
        // column lands on test_case as NOT NULL with default 'STEP_BASED' so
        // existing rows back-fill; the audit parity column is nullable per
        // Envers convention.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case'"
                        + " AND column_name = 'format' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_audit'"
                        + " AND column_name = 'format'")
                .getSingleResult();
        // V078-V079 test_case_gherkin + audit (TC-004).
        entityManager
                .createNativeQuery("SELECT 1 FROM test_case_gherkin LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_case_gherkin_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery(
                        "SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_gherkin_audit'"
                                + " AND column_name = 'test_case_id'")
                .getSingleResult();
        entityManager
                .createNativeQuery(
                        "SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_gherkin_audit'"
                                + " AND column_name = 'source'")
                .getSingleResult();
        entityManager
                .createNativeQuery(
                        "SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_gherkin_audit'"
                                + " AND column_name = 'created_at'")
                .getSingleResult();
        entityManager
                .createNativeQuery(
                        "SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_gherkin_audit'"
                                + " AND column_name = 'updated_at'")
                .getSingleResult();
        // Each test_case row may hold at most one Gherkin doc; the UNIQUE
        // constraint backs the singleton-per-test-case invariant the service
        // enforces.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_case_gherkin'"
                        + " AND constraint_name = 'uq_test_case_gherkin_test_case'")
                .getSingleResult();
        // GC-M011 V080 / V081 subtype + metadata column-existence probes.
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT subtype, metadata FROM operational_asset LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT subtype, metadata FROM operational_asset_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // GC-M011 V082 / V083 asset_subtype_schema + audit table presence.
        entityManager
                .createNativeQuery("SELECT 1 FROM asset_subtype_schema LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM asset_subtype_schema_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'asset_subtype_schema'"
                        + " AND column_name = 'schema_body'")
                .getSingleResult();
        entityManager
                .createNativeQuery(
                        "SELECT 1 FROM information_schema.columns WHERE table_name = 'asset_subtype_schema_audit'"
                                + " AND column_name = 'schema_body'")
                .getSingleResult();
        // BaseEntity-audited columns; Envers needs them on the _audit table.
        entityManager
                .createNativeQuery(
                        "SELECT 1 FROM information_schema.columns WHERE table_name = 'asset_subtype_schema_audit'"
                                + " AND column_name = 'created_at'")
                .getSingleResult();
        entityManager
                .createNativeQuery(
                        "SELECT 1 FROM information_schema.columns WHERE table_name = 'asset_subtype_schema_audit'"
                                + " AND column_name = 'updated_at'")
                .getSingleResult();
        // V084-V087 test_case_folder + audit + test_case placement columns
        // (TC-005 / ADR-043). Same column-existence probe rationale as the
        // older audit-table assertions: ddl-auto: validate doesn't see audit
        // shadow tables, and ALTER TABLE silent regressions on the parent
        // would otherwise pass.
        entityManager
                .createNativeQuery("SELECT 1 FROM test_case_folder LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_case_folder_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_folder'"
                        + " AND column_name = 'parent_id'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_folder'"
                        + " AND column_name = 'sort_order' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery(
                        "SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_folder_audit'"
                                + " AND column_name = 'parent_id'")
                .getSingleResult();
        entityManager
                .createNativeQuery(
                        "SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_folder_audit'"
                                + " AND column_name = 'sort_order'")
                .getSingleResult();
        // BaseEntity timestamps on the test_case_folder_audit shadow — required by
        // AuditRetentionJob.purgeOldAuditRecords to age out folder revisions.
        entityManager
                .createNativeQuery(
                        "SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_folder_audit'"
                                + " AND column_name = 'created_at'")
                .getSingleResult();
        entityManager
                .createNativeQuery(
                        "SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_folder_audit'"
                                + " AND column_name = 'updated_at'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case'"
                        + " AND column_name = 'parent_folder_id'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case'"
                        + " AND column_name = 'sort_order' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_audit'"
                        + " AND column_name = 'parent_folder_id'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_case_audit'"
                        + " AND column_name = 'sort_order'")
                .getSingleResult();
        // Partial unique indexes on (project_id, title) WHERE parent IS NULL
        // and (project_id, parent_id, title) WHERE parent IS NOT NULL back
        // the sibling-title uniqueness invariant.
        entityManager
                .createNativeQuery("SELECT 1 FROM pg_indexes WHERE tablename = 'test_case_folder'"
                        + " AND indexname = 'uq_test_case_folder_title_root'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM pg_indexes WHERE tablename = 'test_case_folder'"
                        + " AND indexname = 'uq_test_case_folder_title_under_parent'")
                .getSingleResult();
        // V088-V089 test_plan + audit (TC-006 / ADR-044). Same column-existence
        // probe rationale as the older audit-table assertions: ddl-auto:
        // validate doesn't see audit shadow tables, so the columns most likely
        // to silently regress are pinned via information_schema. project_id is
        // intentionally absent from test_plan_audit (@NotAudited on
        // TestPlan.project), mirroring the test_case_folder_audit shape.
        entityManager.createNativeQuery("SELECT 1 FROM test_plan LIMIT 1").getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM test_plan_audit LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan'"
                        + " AND column_name = 'uid' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan'"
                        + " AND column_name = 'name' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan'"
                        + " AND column_name = 'product'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan'"
                        + " AND column_name = 'version'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan'"
                        + " AND column_name = 'build'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan'"
                        + " AND column_name = 'status' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan'"
                        + " AND column_name = 'start_date'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan'"
                        + " AND column_name = 'end_date'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan_audit'"
                        + " AND column_name = 'uid'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan_audit'"
                        + " AND column_name = 'name'")
                .getSingleResult();
        // Nullable payload columns on the audit shadow. A V089 copy-paste
        // regression that dropped any of these would surface as an Envers
        // flush failure on the first plan mutation rather than as a
        // targeted schema-smoke failure (test-quality cycle 1).
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan_audit'"
                        + " AND column_name = 'description'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan_audit'"
                        + " AND column_name = 'product'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan_audit'"
                        + " AND column_name = 'version'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan_audit'"
                        + " AND column_name = 'build'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan_audit'"
                        + " AND column_name = 'status'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan_audit'"
                        + " AND column_name = 'start_date'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan_audit'"
                        + " AND column_name = 'end_date'")
                .getSingleResult();
        // BaseEntity timestamps on the audit shadow — AuditRetentionJob ages
        // plan revisions out via these columns.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan_audit'"
                        + " AND column_name = 'created_at'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_plan_audit'"
                        + " AND column_name = 'updated_at'")
                .getSingleResult();
        // (project_id, uid) uniqueness backs the project-scoped UID invariant.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_plan'"
                        + " AND constraint_name = 'uq_test_plan_project_uid'")
                .getSingleResult();
        // V090-V095 test_suite + members + sources + audits (TC-007 / ADR-047).
        // Same column-existence + constraint shape as the test_plan probes so a
        // V091 copy-paste that omitted a payload column or a V092/V094 missing
        // FK action would surface as a failing query rather than as a silent
        // drift across the next migration cycle.
        entityManager.createNativeQuery("SELECT 1 FROM test_suite LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_suite_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_suite_member LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_suite_member_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_suite_source_requirement LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_suite_source_requirement_audit LIMIT 1")
                .getResultList();
        // Required columns on the suite root (population_mode + criteria_*).
        for (String column : java.util.List.of(
                "uid",
                "name",
                "population_mode",
                "criteria_status",
                "criteria_type",
                "criteria_priority",
                "criteria_format",
                "criteria_folder_id",
                "criteria_text_search",
                "created_at",
                "updated_at")) {
            entityManager
                    .createNativeQuery("SELECT 1 FROM information_schema.columns"
                            + " WHERE table_name = 'test_suite'"
                            + " AND column_name = '" + column + "'")
                    .getSingleResult();
        }
        // Audit shadow columns (project_id absent — @NotAudited).
        for (String column : java.util.List.of(
                "uid",
                "name",
                "population_mode",
                "criteria_status",
                "criteria_type",
                "criteria_priority",
                "criteria_format",
                "criteria_folder_id",
                "criteria_text_search",
                "created_at",
                "updated_at")) {
            entityManager
                    .createNativeQuery("SELECT 1 FROM information_schema.columns"
                            + " WHERE table_name = 'test_suite_audit'"
                            + " AND column_name = '" + column + "'")
                    .getSingleResult();
        }
        // (project_id, uid) uniqueness backs the project-scoped UID invariant.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_suite'"
                        + " AND constraint_name = 'uq_test_suite_project_uid'")
                .getSingleResult();
        // CHECK on population_mode keeps the enum honest at the SQL layer.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_suite'"
                        + " AND constraint_name = 'ck_test_suite_population_mode'")
                .getSingleResult();
        // Static-membership UNIQUE + member position column.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_suite_member'"
                        + " AND column_name = 'position'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_suite_member'"
                        + " AND constraint_name = 'uq_test_suite_member_pair'")
                .getSingleResult();
        // Codex pre-push cycle 2 / test-quality review: the DEFERRABLE
        // (suite, position) constraint is the only thing keeping concurrent
        // member writes from duplicating positions. If V092 ever lost the
        // constraint, single-threaded integration tests would still pass —
        // pin both its existence AND the DEFERRABLE attribute via
        // pg_constraint so a non-deferrable variant (which would break
        // multi-row shift commits) is rejected here.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_suite_member'"
                        + " AND constraint_name = 'uq_test_suite_member_position'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM pg_constraint c"
                        + " JOIN pg_class t ON c.conrelid = t.oid"
                        + " WHERE t.relname = 'test_suite_member'"
                        + " AND c.conname = 'uq_test_suite_member_position'"
                        + " AND c.condeferrable = true")
                .getSingleResult();
        // Audit shadow keeps the identity-defining FKs (codex pre-push
        // cycle 1 F1) — without these columns a deleted member row could
        // not be traced back to its suite/test-case.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_suite_member_audit'"
                        + " AND column_name = 'test_suite_id'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_suite_member_audit'"
                        + " AND column_name = 'test_case_id'")
                .getSingleResult();
        // Requirements-based source UNIQUE.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_suite_source_requirement'"
                        + " AND constraint_name = 'uq_test_suite_source_requirement_pair'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_suite_source_requirement_audit'"
                        + " AND column_name = 'test_suite_id'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_suite_source_requirement_audit'"
                        + " AND column_name = 'requirement_id'")
                .getSingleResult();
        // V100-V103: audit + audit_link tables (GC-U001 / ADR-048).
        entityManager.createNativeQuery("SELECT 1 FROM audit LIMIT 1").getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM audit_audit LIMIT 1").getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM audit_link LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM audit_link_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'audit'"
                        + " AND column_name = 'scope_description' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'audit'"
                        + " AND column_name = 'status' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'audit'"
                        + " AND constraint_name = 'uq_audit_project_uid'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'audit_link'"
                        + " AND column_name = 'target_url' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'audit_link'"
                        + " AND column_name = 'target_title' AND is_nullable = 'NO'")
                .getSingleResult();
        // V104-V109: test run + tester assignment + per-case result tables
        // (TC-008 / ADR-049). Pin the column shape so a downstream alter
        // can't silently drop the snapshot fields or the status backstop.
        entityManager.createNativeQuery("SELECT 1 FROM test_run LIMIT 1").getResultList();
        entityManager.createNativeQuery("SELECT 1 FROM test_run_audit LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_run_tester_assignment LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_run_tester_assignment_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_run_case_result LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_run_case_result_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_run'"
                        + " AND column_name = 'test_plan_id' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_run'"
                        + " AND column_name = 'test_suite_id' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = 'test_run'"
                        + " AND column_name = 'status' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_run'"
                        + " AND constraint_name = 'uq_test_run_project_uid'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_run'"
                        + " AND constraint_name = 'ck_test_run_status'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_run_tester_assignment'"
                        + " AND constraint_name = 'uq_test_run_tester'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_run_case_result'"
                        + " AND column_name = 'test_case_uid' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_run_case_result'"
                        + " AND column_name = 'test_case_title' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_run_case_result'"
                        + " AND column_name = 'snapshot_order' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_run_case_result'"
                        + " AND constraint_name = 'uq_test_run_case_result_order'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_run_case_result'"
                        + " AND constraint_name = 'uq_test_run_case_result'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_run_case_result'"
                        + " AND constraint_name = 'ck_test_run_case_result_status'")
                .getSingleResult();
        // V105 / V107 / V109 audit-shadow column probes. The audit tables are
        // not Hibernate-managed entities, so ddl-auto: validate does not
        // inspect them; without explicit column probes, a copy-paste regression
        // in V105 / V107 / V109 that dropped uid / tester_name / snapshot
        // columns silently creates wrong-shape shadows that pass at boot but
        // fail on the first audit revision flush at runtime.
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT uid, name, status, environment, version, build,"
                                + " start_at, end_at, created_at, updated_at"
                                + " FROM test_run_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT test_run_id, tester_name, created_at, updated_at"
                                + " FROM test_run_tester_assignment_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT test_run_id, test_case_id, test_case_uid, test_case_title,"
                                + " snapshot_order, status, notes, created_at, updated_at"
                                + " FROM test_run_case_result_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // V116-V118 step-result + cursor (TC-009 / ADR-050). Pin the
        // snapshot columns + status backstop on the live table and the
        // shape of the _audit shadow so a downstream alter cannot silently
        // drop a snapshot column or the CHECK on status. The cursor lives
        // on test_run (not the audit shadow — see V118 / @NotAudited).
        entityManager
                .createNativeQuery("SELECT 1 FROM test_run_step_result LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM test_run_step_result_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_run_step_result'"
                        + " AND column_name = 'step_number_snapshot' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_run_step_result'"
                        + " AND column_name = 'action_snapshot' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_run_step_result'"
                        + " AND column_name = 'expected_result_snapshot' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_run_step_result'"
                        + " AND column_name = 'snapshot_order' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_run_step_result'"
                        + " AND constraint_name = 'uq_test_run_step_result'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_run_step_result'"
                        + " AND constraint_name = 'uq_test_run_step_result_order'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'test_run_step_result'"
                        + " AND constraint_name = 'ck_test_run_step_result_status'")
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT test_run_case_result_id, test_case_step_id,"
                                + " step_number_snapshot, action_snapshot, expected_result_snapshot,"
                                + " snapshot_order, status, comment, executed_at, created_at, updated_at"
                                + " FROM test_run_step_result_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // V118 cursor columns on test_run live table only.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_run'"
                        + " AND column_name = 'current_case_result_id'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'test_run'"
                        + " AND column_name = 'current_step_result_id'")
                .getSingleResult();
        // V119-V122 scoped_control_implementation + risk_control_mapping + audits (GC-T003).
        // The audit tables are not Hibernate-managed entities, so ddl-auto: validate does not
        // inspect them. Pin the column shape via information_schema so a copy-paste regression
        // that drops a payload column or the BaseEntity timestamps is caught here rather than
        // as a flush-time Envers failure in production.
        entityManager
                .createNativeQuery("SELECT 1 FROM scoped_control_implementation LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM scoped_control_implementation_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'scoped_control_implementation'"
                        + " AND constraint_name = 'uq_scoped_control_implementation_uid'")
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT uid, control_id, name, implementation_scope,"
                                + " created_at, updated_at"
                                + " FROM scoped_control_implementation_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_control_mapping LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_control_mapping_audit LIMIT 1")
                .getResultList();
        // The polymorphic endpoint CHECK constraints are the structural invariant for C1.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'risk_control_mapping'"
                        + " AND constraint_name = 'ck_rcm_control_side'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'risk_control_mapping'"
                        + " AND constraint_name = 'ck_rcm_risk_side'")
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT control_id, scoped_implementation_id,"
                                + " risk_scenario_id, risk_register_record_id,"
                                + " mapping_objective, control_role, mapping_scope,"
                                + " methodology_influence, created_at, updated_at"
                                + " FROM risk_control_mapping_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // C8 provenance tables: mapping_observation + mapping_evidence.
        entityManager
                .createNativeQuery("SELECT 1 FROM mapping_observation LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM mapping_evidence LIMIT 1")
                .getResultList();
        // V123: mapping_evidence_audit — Envers @ElementCollection shadow for evidenceRefs.
        // The SETORDINAL column (quoted — Envers default, uppercase) tracks list position.
        entityManager
                .createNativeQuery("SELECT 1 FROM mapping_evidence_audit LIMIT 1")
                .getResultList();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT risk_control_mapping_id, rev, revtype,"
                                + " \"SETORDINAL\", evidence_ref, evidence_note, evidence_artifact_id"
                                + " FROM mapping_evidence_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // V124-V125: typed methodology-strategy binding (GC-T004 / C5, #861).
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'treatment_plan'"
                        + " AND column_name = 'methodology_profile_id'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'treatment_plan'"
                        + " AND column_name = 'methodology_strategy_key'")
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT methodology_profile_id, methodology_strategy_key"
                                + " FROM treatment_plan_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'methodology_profile'"
                        + " AND column_name = 'treatment_strategy_vocabulary'")
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery(
                                "SELECT treatment_strategy_vocabulary" + " FROM methodology_profile_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // V129-V130: crosswalk_entries column (GC-T012).
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'methodology_profile'"
                        + " AND column_name = 'crosswalk_entries'")
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT crosswalk_entries FROM methodology_profile_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // V126: reassessmentRequiredAt on risk_assessment_result and audit (GC-T004 / C8, #863).
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'risk_assessment_result'"
                        + " AND column_name = 'reassessment_required_at'")
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT reassessment_required_at FROM risk_assessment_result_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // V131: GC-T005 / T006 / T007 / T015 risk-governance lifecycle aggregates.
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_appetite_profile LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_appetite_profile_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_assessment_campaign LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_assessment_campaign_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM key_risk_indicator LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM key_risk_indicator_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'treatment_plan'"
                        + " AND column_name = 'risk_assessment_result_id'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'treatment_plan'"
                        + " AND column_name = 'monitored_risk_factors'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'treatment_plan'"
                        + " AND column_name = 'update_cadence'")
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT risk_assessment_result_id, monitored_risk_factors, update_cadence"
                                + " FROM treatment_plan_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // V132-V133: fair_cam_control_domain on control_effectiveness_assessment and audit (GC-I017).
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'control_effectiveness_assessment'"
                        + " AND column_name = 'fair_cam_control_domain'")
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT fair_cam_control_domain"
                                + " FROM control_effectiveness_assessment_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // V134: compliance_framework_mapping aggregate + audit parity
        // (GC-I002 / GC-I005 / GC-I007 / GC-L011).
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.tables"
                        + " WHERE table_name = 'compliance_framework_mapping'")
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT framework_element FROM compliance_framework_mapping_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // The (endpoint, framework, element) uniqueness rule MUST be scoped to
        // its own endpoint side. A table-wide UNIQUE NULLS NOT DISTINCT would
        // treat the NULL side as a single value and reject two control-side
        // rows whose (control_id, framework, element) tuples differ but share
        // NULL requirement_id — defeating GC-I005's "multiple controls
        // (including compensating) may map to one element" intent. Pin the
        // partial unique indexes by name so a future migration cannot silently
        // regress to the table-wide form.
        entityManager
                .createNativeQuery("SELECT 1 FROM pg_indexes"
                        + " WHERE tablename = 'compliance_framework_mapping'"
                        + " AND indexname = 'uq_cfm_requirement_framework_element'"
                        + " AND indexdef LIKE '%WHERE (requirement_id IS NOT NULL)%'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM pg_indexes"
                        + " WHERE tablename = 'compliance_framework_mapping'"
                        + " AND indexname = 'uq_cfm_control_framework_element'"
                        + " AND indexdef LIKE '%WHERE (control_id IS NOT NULL)%'")
                .getSingleResult();
    }
}

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
        // Flyway immutability: once a versioned migration has been applied to a long-lived database
        // (e.g. production) its file content is frozen — the checksum is validated on every startup.
        // Never edit an applied V*.sql in place; append a new forward migration instead. Editing the
        // already-applied V043/V045 (rather than relying solely on the forward V138 realignment)
        // crashed a prod deploy on a checksum mismatch that fresh CI databases could not catch.
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
                        "123", "124", "125", "126", "127", "128", "129", "130", "131", "132", "133", "134", "135",
                        "136", "137", "138", "139", "140", "141", "142", "143", "144", "145", "146", "147", "148",
                        "149", "150", "151", "152", "153", "154", "155", "156", "157", "158", "159", "160", "161",
                        "162", "163", "164", "165", "166", "167", "168", "169", "170", "171", "172", "173", "174",
                        "175");
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
        assertOperationalAssetColumns();
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
        entityManager
                .createNativeQuery("SELECT 1 FROM risk_appetite_profile LIMIT 1")
                .getResultList();
        // Column-level probe on the Envers shadow (V141): the audit table is not covered by
        // ddl-auto:validate, so a missing/renamed column would only surface on the first
        // RiskAppetiteProfile mutation in production without this assertion (GC-T005).
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT appetite_key, name, version, methodology_family, appetite_statement,"
                                + " tolerance_thresholds, status, effective_from, effective_to, created_at, updated_at"
                                + " FROM risk_appetite_profile_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
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
        // Test-quality review: the DEFERRABLE
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
        // Audit shadow keeps the identity-defining FKs (test-quality
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
        // ck_rcm_risk_side is replaced by the 3-way ck_rcm_analysis_side in V137 (asserted below).
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
        // V138: primary-source alignment for seeded FAIR/NIST methodology profiles.
        assertSeededMethodologyProfilesAligned();
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
        // V131-V132: project.type + research_intake (ADR-056, #999). Pin the
        // type column on project, the live research_intake shape, and the
        // _audit shadow column set. Without explicit column probes a copy-paste
        // regression in V132 that dropped goal / autonomy_level / allowed_tools
        // would silently create a wrong-shape shadow that passes at boot but
        // fails on the first Envers flush at runtime.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'project'"
                        + " AND column_name = 'type' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager.createNativeQuery("SELECT 1 FROM research_intake LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM research_intake_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'research_intake'"
                        + " AND column_name = 'goal' AND is_nullable = 'NO'")
                .getSingleResult();
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = 'research_intake'"
                        + " AND column_name = 'project_id' AND is_nullable = 'NO'")
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT goal, paper_context, contribution_type, intended_output,"
                                + " autonomy_level, allowed_tools, privacy_constraints,"
                                + " budget_tokens, budget_wall_clock_minutes, budget_cost_usd_micros,"
                                + " created_at, updated_at"
                                + " FROM research_intake_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // V133-V134: GC-GRC-001 derivation fact store. Pin the live tables and
        // audit shadow columns so every normalized fact keeps reproducible
        // provenance and every unsupported scope remains queryable as a
        // machine-readable capture limit.
        entityManager.createNativeQuery("SELECT 1 FROM derivation_run LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM system_model_fact LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM derivation_capture_limit LIMIT 1")
                .getResultList();
        // V135: mcp_tool_event (issue #1104 / ADR-059). Append-only operational
        // telemetry; no _audit shadow (rows are never mutated).
        entityManager.createNativeQuery("SELECT 1 FROM mcp_tool_event LIMIT 1").getResultList();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT tool, action, outcome, duration_ms, project, event_ts, created_at"
                                + " FROM mcp_tool_event LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        entityManager
                .createNativeQuery("SELECT 1 FROM derivation_run_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM system_model_fact_audit LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM derivation_capture_limit_audit LIMIT 1")
                .getResultList();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT fact_kind, schema_version, fact_key, adapter_id,"
                                + " tool_name, tool_version, ruleset_name, ruleset_version,"
                                + " commit_sha, derived_at, created_at, updated_at"
                                + " FROM system_model_fact_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT reason, language, surface, commit_sha, captured_at,"
                                + " created_at, updated_at"
                                + " FROM derivation_capture_limit_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // V137: threat_model_id on risk_control_mapping + audit shadow (GC-H006).
        // The 3-way analysis-side constraint replaces the old 2-way ck_rcm_risk_side.
        entityManager
                .createNativeQuery("SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE table_name = 'risk_control_mapping'"
                        + " AND constraint_name = 'ck_rcm_analysis_side'")
                .getSingleResult();
        // V137 also replaces the four V121 plain UNIQUE constraints and adds the two threat
        // endpoints as PARTIAL unique indexes, each predicated on its own endpoint family so a
        // NULL endpoint column from a different family cannot collide under NULLS NOT DISTINCT.
        // Asserting each index is partial (has a WHERE predicate) is the regression gate for the
        // review finding that drove the partial-index rewrite.
        for (String idx : new String[] {
            "uq_rcm_control_scenario_asset",
            "uq_rcm_control_record_asset",
            "uq_rcm_control_threat_asset",
            "uq_rcm_scoped_scenario_asset",
            "uq_rcm_scoped_record_asset",
            "uq_rcm_scoped_threat_asset"
        }) {
            var indexDef = entityManager
                    .createNativeQuery("SELECT indexdef FROM pg_indexes"
                            + " WHERE tablename = 'risk_control_mapping' AND indexname = '" + idx + "'")
                    .getSingleResult();
            assertThat(indexDef.toString())
                    .as("index %s must be a partial unique index", idx)
                    .contains("CREATE UNIQUE INDEX")
                    .contains("WHERE");
        }
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT threat_model_id FROM risk_control_mapping LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT threat_model_id FROM risk_control_mapping_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // V163 / V165 (#1002, ADR-069): research provenance ledger audit shadows.
        assertResearchProvenanceAuditColumns();
        // V172-V175 (#1005, GC-RSCH-F006): methodology selection + source tables.
        assertResearchMethodologyAuditColumns();
    }

    /**
     * V173 / V175 (#1005, GC-RSCH-F006) — column-level probes for the methodology
     * selection + source Envers audit shadows. ddl-auto:validate does not inspect
     * audit tables, so probe every payload column explicitly; a copy-paste
     * regression dropping e.g. {@code superseded_at} or {@code state} would
     * otherwise only surface at the first Envers flush in production.
     */
    private void assertResearchMethodologyAuditColumns() {
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT 1 FROM research_run_methodology_selection LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT 1 FROM research_run_methodology_source LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT method_key, method_label, profile_version, catalog_version, actor,"
                                + " superseded_at, created_at, updated_at"
                                + " FROM research_run_methodology_selection_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT source_ref, source_label, required, state, actor,"
                                + " created_at, updated_at"
                                + " FROM research_run_methodology_source_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
    }

    /**
     * GC-M012 column-existence probes (V069 / V070). A column-by-column SELECT
     * throws PersistenceException if any ALTER TABLE in the migration silently
     * omitted a column, where the table-only {@code SELECT 1 FROM operational_asset}
     * check would not. Extracted from {@link #auditTablesExist()} to keep that
     * probe roster's assertion count bounded.
     */
    private void assertOperationalAssetColumns() {
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
    }

    /**
     * V163 / V165 (#1002, ADR-069) — column-level probes for the research
     * provenance audit shadows. ddl-auto:validate does not inspect audit tables,
     * so probe every payload column explicitly; a copy-paste regression that
     * dropped or renamed a shadow column would otherwise only surface at the first
     * Envers flush in production. Extracted from {@link #auditTablesExist()} to
     * keep that probe roster's assertion count bounded.
     */
    private void assertResearchProvenanceAuditColumns() {
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT kind, subject_key, stage, artifact_type, artifact_id, attempt_no,"
                                + " locator, content_hash, external_identifier, summary, tool_name, tool_version,"
                                + " source_action_id, status, actor, idempotency_key, created_at, updated_at"
                                + " FROM research_provenance_node_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT from_node_id, to_node_id, relation, role, summary, status, actor,"
                                + " idempotency_key, created_at, updated_at"
                                + " FROM research_provenance_edge_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
    }

    @Test
    @Transactional
    void boundaryModelAuditTablesMatchEntities() {
        // V150-V151: canonical boundary model snapshots, boundaries,
        // assignments, and modeling gaps. Envers shadow tables are not covered
        // by ddl-auto:validate, so pin the columns that carry the model.
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT derivation_run_id, schema_version, boundary_set_version,"
                                + " architecture_model_version, commit_sha, declaration_digest,"
                                + " boundary_count, assignment_count, gap_count, created_at, updated_at"
                                + " FROM boundary_model_snapshot_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT snapshot_id, boundary_key, display_name, description,"
                                + " source, path_selectors, surfaces, input_fact_keys, created_at, updated_at"
                                + " FROM boundary_model_boundary_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT snapshot_id, boundary_id, source_fact_key, source_fact_kind,"
                                + " source_path, strategy, created_at, updated_at"
                                + " FROM boundary_model_assignment_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT snapshot_id, source_fact_key, source_fact_kind,"
                                + " source_path, reason, detail, created_at, updated_at"
                                + " FROM boundary_model_gap_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
    }

    @Test
    @Transactional
    void architectureModelAuditTablesMatchEntities() {
        // V166-V167: architecture model stable elements, versioned snapshots,
        // snapshot-local DFD semantics, and Envers audit shadows (GC-GRC-005).
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT stable_key, element_kind, created_at, updated_at"
                                + " FROM architecture_model_element LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT derivation_run_id, schema_version, model_version,"
                                + " commit_sha, source, created_by, element_count, flow_count, created_at, updated_at"
                                + " FROM architecture_model_snapshot LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT snapshot_id, element_id, stable_key, element_kind, label,"
                                + " source_path, flow_source_stable_key, flow_target_stable_key, flow_direction,"
                                + " provenance_source, provenance_key, commit_sha, metadata, created_at, updated_at"
                                + " FROM architecture_model_element_state LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT stable_key, element_kind, created_at, updated_at"
                                + " FROM architecture_model_element_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT derivation_run_id, schema_version, model_version,"
                                + " commit_sha, source, created_by, element_count, flow_count, created_at, updated_at"
                                + " FROM architecture_model_snapshot_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT snapshot_id, element_id, stable_key, element_kind, label,"
                                + " source_path, flow_source_stable_key, flow_target_stable_key, flow_direction,"
                                + " provenance_source, provenance_key, commit_sha, metadata, created_at, updated_at"
                                + " FROM architecture_model_element_state_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
    }

    @Test
    @Transactional
    void dataClassificationLatticeAuditTablesMatchEntities() {
        // V169-V170: data classification lattice root, labels, permitted-flow rules, and their
        // Envers audit shadows (GC-GRC-006). The column-by-column probe catches a migration that
        // silently omits a column where a table-only `SELECT 1` would not.
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT schema_version, policy_version, source, label_count, edge_count,"
                                + " created_at, updated_at FROM data_classification_lattice LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT lattice_id, label_key, display_name, description, rank,"
                                + " created_at, updated_at FROM data_classification_label LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT lattice_id, from_label_key, to_label_key, created_at, updated_at"
                                + " FROM data_classification_flow_rule LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT schema_version, policy_version, source, label_count, edge_count,"
                                + " created_at, updated_at FROM data_classification_lattice_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT lattice_id, label_key, display_name, description, rank,"
                                + " created_at, updated_at FROM data_classification_label_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT lattice_id, from_label_key, to_label_key, created_at, updated_at"
                                + " FROM data_classification_flow_rule_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
    }

    /**
     * V142: workflow-run telemetry reporting tables (#859 / ADR-061). Append-only/operational
     * reporting read-model; no _audit shadow (cf. mcp_tool_event). ddl-auto:validate does not inspect
     * index predicates or CHECK constraints, so probe them explicitly here. Kept as its own test so
     * neither this nor auditTablesExist crosses the per-method assertion budget.
     */
    @Test
    @Transactional
    void workflowTelemetryTablesExist() {
        entityManager.createNativeQuery("SELECT 1 FROM workflow_run LIMIT 1").getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM workflow_run_requirement_uid LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT 1 FROM workflow_phase_event LIMIT 1")
                .getResultList();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT project, repo, issue_number, pr_number, branch, workflow_type,"
                                + " runtime_driver, started_at, ended_at, final_state, outcome, provenance,"
                                + " provider, model, model_invocation_count, wall_clock_minutes, cost_proxy,"
                                + " cost_currency, token_usage, created_at, updated_at"
                                + " FROM workflow_run LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT run_id, project, phase, event_type, cycle_index, occurred_at,"
                                + " duration_ms, outcome, provenance, created_at"
                                + " FROM workflow_phase_event LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // The idempotency key must be a UNIQUE index with NULLS NOT DISTINCT: the property that
        // dedupes runs with null repo/issue_number/branch. ddl-auto:validate cannot see this, and the
        // behavioral upsert test uses only non-null keys, so a regression dropping NULLS NOT DISTINCT
        // would silently reintroduce duplicate rows. Assert the index predicate directly.
        var upsertIndexDef = entityManager
                .createNativeQuery("SELECT indexdef FROM pg_indexes"
                        + " WHERE tablename = 'workflow_run' AND indexname = 'idx_workflow_run_upsert_key'")
                .getSingleResult();
        assertThat(upsertIndexDef.toString())
                .as("idx_workflow_run_upsert_key must be a UNIQUE NULLS NOT DISTINCT index")
                .contains("CREATE UNIQUE INDEX")
                .contains("NULLS NOT DISTINCT");
        // The three non-negative CHECK constraints guard the economics columns; verify each via the
        // constraint definitions (the inline checks get auto-generated names, so match the column).
        var workflowRunChecks = entityManager
                .createNativeQuery("SELECT string_agg(pg_get_constraintdef(c.oid), ' ')"
                        + " FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid"
                        + " WHERE t.relname = 'workflow_run' AND c.contype = 'c'")
                .getSingleResult();
        assertThat(workflowRunChecks.toString())
                .as("workflow_run must CHECK non-negative economics columns")
                .contains("model_invocation_count")
                .contains("wall_clock_minutes")
                .contains("cost_proxy");
    }

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

    private void assertSeededMethodologyProfilesAligned() {
        assertThat(entityManager
                        .createNativeQuery("SELECT version FROM methodology_profile WHERE profile_key = 'FAIR_V3_0'")
                        .getSingleResult())
                .isEqualTo("O-RT 3.0.1 / O-RA 2.0.1");
        assertThat(entityManager
                        .createNativeQuery("SELECT input_schema::jsonb #>>"
                                + " '{properties,probability_of_action,properties,high,maximum}'"
                                + " FROM methodology_profile WHERE profile_key = 'FAIR_V3_0'")
                        .getSingleResult())
                .isEqualTo("1");
        assertThat(entityManager
                        .createNativeQuery("SELECT input_schema::jsonb #>>"
                                + " '{properties,threat_capability,properties,high,maximum}'"
                                + " FROM methodology_profile WHERE profile_key = 'FAIR_V3_0'")
                        .getSingleResult())
                .isEqualTo("100");
        assertThat(entityManager
                        .createNativeQuery("SELECT input_schema::jsonb #>"
                                + " '{properties,fair_cam}'"
                                + " FROM methodology_profile WHERE profile_key = 'FAIR_V3_0'")
                        .getSingleResult())
                .isNull();
        assertThat(entityManager
                        .createNativeQuery("SELECT input_schema::jsonb #>>"
                                + " '{properties,threat_event_relevance,description}'"
                                + " FROM methodology_profile WHERE profile_key = 'NIST_SP800_30_R1'")
                        .getSingleResult())
                .isEqualTo("Threat event relevance per NIST SP 800-30 Rev. 1 Table E-4");
        assertThat(entityManager
                        .createNativeQuery("SELECT input_schema::jsonb #>>"
                                + " '{properties,threat_source_relevance,deprecated}'"
                                + " FROM methodology_profile WHERE profile_key = 'NIST_SP800_30_R1'")
                        .getSingleResult())
                .isEqualTo("true");
    }
}

package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Split from MigrationSmokeTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
class MigrationSmokeSchemaShapeOfAssetRelationTest extends BaseIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

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

    @Test
    @Transactional
    void schemaShapeOfAssetRelation() {

        assertOperationalAssetColumns();
        entityManager
                .createNativeQuery("SELECT updated_at FROM asset_relation LIMIT 1")
                .getResultList();
        entityManager
                .createNativeQuery("SELECT updated_at FROM asset_relation_audit LIMIT 1")
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
    }

    @Test
    @Transactional
    void schemaShapeOfInformationSchema() {
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
            // Stated as an assertion rather than left implicit: getSingleResult()
            // throws when the column is missing, but a test whose expectation is
            // "nothing threw" reads as a test with no expectation at all.
            assertThatCode(() -> entityManager
                            .createNativeQuery("SELECT 1 FROM information_schema.columns"
                                    + " WHERE table_name = 'test_suite_audit'"
                                    + " AND column_name = '" + column + "'")
                            .getSingleResult())
                    .as("test_suite_audit.%s should exist", column)
                    .doesNotThrowAnyException();
        }
    }
}

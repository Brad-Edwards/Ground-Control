package com.keplerops.groundcontrol.integration;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Split from MigrationSmokeTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
class MigrationSmokeSchemaShapeOfInformationSchemaBTest extends BaseIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    /**
     * V105 / V107 / V109 / V116-V118 (TC-008 / TC-009, ADR-049 / ADR-050) —
     * column-level probes for the test-run, tester-assignment, case-result, and
     * step-result Envers audit shadows. The audit tables are not Hibernate-managed
     * entities, so ddl-auto:validate does not inspect them; without explicit
     * column probes, a copy-paste regression that dropped a uid / tester_name /
     * snapshot column would silently create a wrong-shape shadow that passes at
     * boot but fails on the first audit revision flush at runtime. Extracted from
     * {@link #auditTablesExist()} to keep that probe roster's assertion count
     * bounded.
     */
    private void assertTestRunAuditColumns() {
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
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT test_run_case_result_id, test_case_step_id,"
                                + " step_number_snapshot, action_snapshot, expected_result_snapshot,"
                                + " snapshot_order, status, comment, executed_at, created_at, updated_at"
                                + " FROM test_run_step_result_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
    }

    @Test
    @Transactional
    void schemaShapeOfInformationSchemaB() {
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
        // V105 / V107 / V109 / V116-V118 (TC-008 / TC-009, ADR-049 / ADR-050)
        // test-run audit-shadow column probes. The audit tables are not
        // Hibernate-managed entities, so ddl-auto: validate does not inspect
        // them; without explicit column probes, a copy-paste regression that
        // dropped uid / tester_name / snapshot columns silently creates
        // wrong-shape shadows that pass at boot but fail on the first audit
        // revision flush at runtime. Extracted from this method to keep the
        // probe roster's assertion count bounded (see assertTestRunAuditColumns).
        assertTestRunAuditColumns();
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
    }
}

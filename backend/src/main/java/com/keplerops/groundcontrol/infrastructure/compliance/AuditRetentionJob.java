package com.keplerops.groundcontrol.infrastructure.compliance;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled job that purges audit records older than the configured retention period.
 *
 * <p>IMPORTANT: If you add a new Envers {@code *_audit} table, you MUST add a corresponding
 * entry to {@link #AUDIT_TABLES} so its rows are deleted before the parent {@code revinfo} rows.
 * Failing to do so will leave orphan revinfo rows or cause FK violations.
 */
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);
    private static final int BATCH_SIZE = 1000;

    /**
     * All audit tables that reference revinfo.rev. Order does not matter since they are
     * deleted by rev IN (subquery) before revinfo itself is cleaned up.
     */
    private static final List<String> AUDIT_TABLES = List.of(
            "requirement_audit",
            "requirement_relation_audit",
            "traceability_link_audit",
            "architecture_decision_record_audit",
            "operational_asset_audit",
            "asset_relation_audit",
            "asset_link_audit",
            "asset_external_id_audit",
            "observation_audit",
            "risk_scenario_audit",
            "risk_scenario_link_audit",
            "methodology_profile_audit",
            "risk_register_record_audit",
            "risk_register_record_scenario_audit",
            "risk_assessment_result_audit",
            "risk_assessment_result_observation_audit",
            "treatment_plan_audit",
            "control_test_audit",
            "control_effectiveness_assessment_audit",
            "test_case_audit",
            "test_case_step_audit",
            "test_case_gherkin_audit",
            "asset_subtype_schema_audit",
            "test_case_folder_audit",
            "test_plan_audit",
            "evidence_artifact_audit",
            "test_suite_audit",
            "test_suite_member_audit",
            "test_suite_source_requirement_audit",
            "audit_audit",
            "audit_link_audit",
            "test_run_audit",
            "test_run_tester_assignment_audit",
            "test_run_case_result_audit",
            "test_run_step_result_audit",
            "compliance_drift_event_audit");

    private final AuditRetentionProperties properties;
    private final EntityManager entityManager;

    public AuditRetentionJob(AuditRetentionProperties properties, EntityManager entityManager) {
        this.properties = properties;
        this.entityManager = entityManager;
    }

    @Scheduled(cron = "${groundcontrol.compliance.audit-retention-cron:0 0 3 * * *}")
    @Transactional
    public void purgeOldAuditRecords() {
        long cutoffMs = System.currentTimeMillis() - ((long) properties.retentionDays() * 24 * 60 * 60 * 1000);
        int totalAudit = 0;

        for (String table : AUDIT_TABLES) {
            totalAudit += deleteBatched(
                    "DELETE FROM " + table + " WHERE rev IN " + "(SELECT rev FROM revinfo WHERE revtstmp < :cutoff)",
                    cutoffMs);
        }

        int revinfo = deleteBatched("DELETE FROM revinfo WHERE revtstmp < :cutoff", cutoffMs);

        log.info(
                "Audit retention cleanup: deleted {} audit rows across {} tables, {} revinfo entries (retention={} days)",
                totalAudit,
                AUDIT_TABLES.size(),
                revinfo,
                properties.retentionDays());
    }

    private int deleteBatched(String sql, long cutoffMs) {
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = entityManager
                    .createNativeQuery(sql + " LIMIT " + BATCH_SIZE)
                    .setParameter("cutoff", cutoffMs)
                    .executeUpdate();
            totalDeleted += deleted;
            if (deleted > 0) {
                entityManager.flush();
            }
        } while (deleted == BATCH_SIZE);
        return totalDeleted;
    }
}

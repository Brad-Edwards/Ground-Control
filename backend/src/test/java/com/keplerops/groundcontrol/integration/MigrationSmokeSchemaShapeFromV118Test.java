package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Split from MigrationSmokeTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
class MigrationSmokeSchemaShapeFromV118Test extends BaseIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    /**
     * V179-V183 (#1006, ADR-080) — column-level probes for the methodology
     * requirements contract, its entries, source links, rejected alternatives, and
     * the contract Envers audit shadow. ddl-auto:validate does not inspect audit
     * tables, so probe the audited payload columns explicitly.
     */
    private void assertMethodologyContractColumns() {
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT research_run_id, selection_id, artifact_id, attempt_no,"
                                + " schema_version, actor, created_at, updated_at"
                                + " FROM methodology_requirements_contract LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT artifact_id, attempt_no, schema_version, actor,"
                                + " created_at, updated_at"
                                + " FROM methodology_requirements_contract_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT contract_id, kind, entry_key, statement, references_entry_key, actor"
                                + " FROM methodology_requirements_contract_entry LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT entry_id, source_id, locator"
                                + " FROM methodology_requirements_contract_entry_source_link LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery(
                                "SELECT contract_id, rationale_entry_id, method_key, profile_version, external"
                                        + " FROM methodology_requirements_contract_rejected_alternative LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
    }

    /**
     * V184-V187 (#1007, ADR-083) — column-level probes for the protocol plan, its
     * coverage rows, sections, and the plan Envers audit shadow. ddl-auto:validate
     * does not inspect audit tables, so probe the audited payload columns
     * explicitly.
     */
    private void assertProtocolPlanColumns() {
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT research_run_id, methodology_requirements_contract_id,"
                                + " artifact_id, attempt_no, protocol_schema_version, method_key,"
                                + " method_profile_version, actor, created_at, updated_at"
                                + " FROM protocol_plan LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT artifact_id, attempt_no, protocol_schema_version, method_key,"
                                + " method_profile_version, actor, created_at, updated_at"
                                + " FROM protocol_plan_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT protocol_plan_id, contract_entry_key, disposition,"
                                + " answer_summary, answer_provenance, rationale, deferred_to_stage,"
                                + " decision_reference, actor"
                                + " FROM protocol_plan_coverage LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT protocol_plan_id, section_key, section_kind, source_role,"
                                + " content_summary, actor"
                                + " FROM protocol_plan_section LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
    }

    /**
     * V176 / V178 (#1005, GC-RSCH-F006) — column-level probes for the methodology
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

    /**
     * V202 (#1309, ADR-084 §5) — column-level probe for the document Envers audit shadow.
     * ddl-auto:validate does not inspect audit tables, so probe every payload column explicitly; a
     * copy-paste regression that dropped or mistyped a column here would otherwise only surface as
     * an Envers flush failure on the first {@code Document} mutation in production — silently
     * invalidating the {@code age_graph_snapshot.source_revision} claim for Document-authored graph
     * content. {@code project_id} is intentionally absent ({@code @NotAudited} on
     * {@code Document.project}), matching every other audited aggregate's owning-project reference.
     * Extracted from {@link #auditTablesExist()} to keep that probe roster's assertion count
     * bounded.
     */
    private void assertDocumentAuditColumns() {
        entityManager.createNativeQuery("SELECT 1 FROM document_audit LIMIT 1").getResultList();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT title, version, description, grammar, created_by,"
                                + " created_at, updated_at"
                                + " FROM document_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
    }

    private void assertWorkflowTelemetryAuditColumns() {
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT project, repo, issue_number, pr_number, branch, workflow_type,"
                                + " runtime_driver, started_at, ended_at, final_state, outcome, provenance, provider,"
                                + " model, model_invocation_count, wall_clock_minutes, cost_proxy, cost_currency,"
                                + " token_usage, created_at, updated_at FROM workflow_run_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT run_id, project, phase, event_type, cycle_index, occurred_at,"
                                + " duration_ms, outcome, provenance, source_id, created_at"
                                + " FROM workflow_phase_event_audit LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
    }

    @Test
    @Transactional
    void schemaShapeFromV118() {
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
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT risk_control_mapping_id, rev, revtype,"
                                + " \"SETORDINAL\", evidence_ref, evidence_note, evidence_artifact_id"
                                + " FROM mapping_evidence_audit LIMIT 1")
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
        org.assertj.core.api.Assertions.assertThatCode(() -> entityManager
                        .createNativeQuery("SELECT tool, action, outcome, duration_ms, project, event_ts, created_at"
                                + " FROM mcp_tool_event LIMIT 1")
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
        // V175-V178 (#1005, GC-RSCH-F006): methodology selection + source tables.
        assertResearchMethodologyAuditColumns();
        // V179-V183 (#1006, ADR-080): methodology requirements contract tables.
        assertMethodologyContractColumns();
        // V184-V187 (#1007, ADR-083): protocol plan tables.
        assertProtocolPlanColumns();
        // V202 (#1309, ADR-084 §5): document_audit — Document joins the audited spine.
        assertDocumentAuditColumns();
        // V203 (#1311, ADR-061 amendment): workflow reporting joins the audited graph spine.
        assertWorkflowTelemetryAuditColumns();
    }

    /**
     * V142: workflow-run telemetry reporting tables (#859 / ADR-061). ddl-auto:validate does not
     * inspect index predicates or CHECK constraints, so probe them explicitly here. Kept as its own
     * test so neither this nor auditTablesExist crosses the per-method assertion budget. V203 adds
     * the audit shadows required by the graph projection.
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
                                + " duration_ms, outcome, provenance, source_id, created_at"
                                + " FROM workflow_phase_event LIMIT 1")
                        .getResultList())
                .doesNotThrowAnyException();
        // V204 (#1435): the phase-event dedup key. ddl-auto:validate sees neither index definitions
        // nor audit shadows, so if this index were dropped or weakened to non-unique, live emission
        // and issue-thread backfill would both insert the same attempt and every per-phase count,
        // first-pass-yield denominator, and rework figure would silently inflate. Probe it directly,
        // exactly as idx_workflow_run_upsert_key is probed above.
        var phaseEventSourceIndexDef = entityManager
                .createNativeQuery("SELECT indexdef FROM pg_indexes"
                        + " WHERE tablename = 'workflow_phase_event'"
                        + " AND indexname = 'idx_workflow_phase_event_source'")
                .getSingleResult();
        assertThat(phaseEventSourceIndexDef.toString())
                .as("idx_workflow_phase_event_source must be a UNIQUE index on (run_id, source_id)")
                .contains("CREATE UNIQUE INDEX")
                .contains("run_id")
                .contains("source_id");
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
}

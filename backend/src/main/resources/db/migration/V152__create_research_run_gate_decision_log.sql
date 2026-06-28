-- GC-RSCH-F004 — ADR-066. Append-only durable record of each gate decision.
-- A row is written every time a gate is resolved (human, autonomous default, or
-- otherwise), capturing the recommendation that was on the table and the
-- decision made. Rows are never mutated or deleted; the decision history lives
-- here, never in workspace decisions.md. All summary columns are bounded.
CREATE TABLE research_run_gate_decision_log (
    id                        UUID PRIMARY KEY,
    research_run_id           UUID         NOT NULL REFERENCES research_run(id),
    gate_point                VARCHAR(40)  NOT NULL,
    guarded_stage             VARCHAR(40)  NOT NULL,
    artifact_attempt_no       INTEGER,
    question_key              VARCHAR(200),
    recommendation_option_id  VARCHAR(200),
    recommendation_summary    VARCHAR(1000),
    recommendation_provenance VARCHAR(20),
    decision_outcome          VARCHAR(20)  NOT NULL,
    selected_option_id        VARCHAR(200),
    rationale_summary         VARCHAR(1000),
    policy_basis              VARCHAR(200),
    source_action_id          VARCHAR(200),
    decision_actor            VARCHAR(200),
    decided_at                TIMESTAMPTZ,
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_research_run_gate_dlog_point
        CHECK (gate_point IN ('METHOD_DECISION', 'PROTOCOL_DECISION', 'SEARCH_DECISION',
            'SYNTHESIS_DECISION', 'WRITING_DECISION')),
    CONSTRAINT ck_research_run_gate_dlog_stage
        CHECK (guarded_stage IN ('METHODOLOGY_SELECTION', 'PROTOCOL_PLANNING', 'SOURCE_SEARCH',
            'SCREENING', 'CHARTING', 'SYNTHESIS', 'ARGUMENT_CONSTRUCTION', 'PROSE_DRAFTING')),
    CONSTRAINT ck_research_run_gate_dlog_rec_prov
        CHECK (recommendation_provenance IS NULL OR recommendation_provenance IN
            ('AGENT', 'SYSTEM_POLICY', 'HUMAN_REVIEWER')),
    CONSTRAINT ck_research_run_gate_dlog_outcome
        CHECK (decision_outcome IN ('APPROVED', 'REJECTED', 'AUTO_ACCEPTED'))
);

CREATE INDEX idx_research_run_gate_dlog_run ON research_run_gate_decision_log (research_run_id);

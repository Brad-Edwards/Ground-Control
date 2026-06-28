-- GC-RSCH-F034 — ADR-067. Run-scoped review comments attached to a gate point,
-- stage, artifact, decision-log row, or the run itself. Comments open OPEN and
-- are resolved (or reopened) durably; the body is a bounded note, never raw
-- manuscript prose.
CREATE TABLE research_run_review_comment (
    id                     UUID PRIMARY KEY,
    research_run_id        UUID         NOT NULL REFERENCES research_run(id),
    target_type            VARCHAR(20)  NOT NULL,
    target_gate_point      VARCHAR(40),
    target_stage           VARCHAR(40),
    target_artifact_id     UUID,
    target_decision_log_id UUID,
    body                   VARCHAR(2000) NOT NULL,
    provenance             VARCHAR(20)  NOT NULL,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    resolution_summary     VARCHAR(1000),
    author_actor           VARCHAR(200),
    resolved_by_actor      VARCHAR(200),
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_research_run_review_comment_target
        CHECK (target_type IN ('RUN', 'GATE_POINT', 'STAGE', 'ARTIFACT', 'DECISION_LOG')),
    CONSTRAINT ck_research_run_review_comment_gate_point
        CHECK (target_gate_point IS NULL OR target_gate_point IN ('METHOD_DECISION', 'PROTOCOL_DECISION',
            'SEARCH_DECISION', 'SYNTHESIS_DECISION', 'WRITING_DECISION')),
    CONSTRAINT ck_research_run_review_comment_stage
        CHECK (target_stage IS NULL OR target_stage IN ('METHODOLOGY_SELECTION', 'PROTOCOL_PLANNING',
            'SOURCE_SEARCH', 'SCREENING', 'CHARTING', 'SYNTHESIS', 'ARGUMENT_CONSTRUCTION', 'PROSE_DRAFTING')),
    CONSTRAINT ck_research_run_review_comment_provenance
        CHECK (provenance IN ('HUMAN_REVIEW', 'AGENT_RECOMMENDATION', 'SYSTEM_CHECK')),
    CONSTRAINT ck_research_run_review_comment_status
        CHECK (status IN ('OPEN', 'RESOLVED'))
);

CREATE INDEX idx_research_run_review_comment_run ON research_run_review_comment (research_run_id);

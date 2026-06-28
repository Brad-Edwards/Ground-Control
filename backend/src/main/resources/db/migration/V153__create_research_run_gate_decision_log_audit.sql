-- GC-RSCH — Envers audit shadow for research_run_gate_decision_log (ADR-066).
--
-- research_run_id is intentionally absent (@NotAudited on the JPA mapping). All
-- other columns are audited business state. BaseEntity timestamps are mirrored
-- for retention purging.
CREATE TABLE research_run_gate_decision_log_audit (
    id                        UUID         NOT NULL,
    rev                       INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype                   SMALLINT     NOT NULL,
    gate_point                VARCHAR(40),
    guarded_stage             VARCHAR(40),
    artifact_attempt_no       INTEGER,
    question_key              VARCHAR(200),
    recommendation_option_id  VARCHAR(200),
    recommendation_summary    VARCHAR(1000),
    recommendation_provenance VARCHAR(20),
    decision_outcome          VARCHAR(20),
    selected_option_id        VARCHAR(200),
    rationale_summary         VARCHAR(1000),
    policy_basis              VARCHAR(200),
    source_action_id          VARCHAR(200),
    decision_actor            VARCHAR(200),
    decided_at                TIMESTAMPTZ,
    created_at                TIMESTAMPTZ,
    updated_at                TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

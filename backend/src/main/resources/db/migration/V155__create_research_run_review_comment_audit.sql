-- GC-RSCH — Envers audit shadow for research_run_review_comment (ADR-067).
--
-- research_run_id is intentionally absent (@NotAudited on the JPA mapping). All
-- other columns are audited business state. BaseEntity timestamps are mirrored
-- for retention purging.
CREATE TABLE research_run_review_comment_audit (
    id                     UUID         NOT NULL,
    rev                    INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype                SMALLINT     NOT NULL,
    target_type            VARCHAR(20),
    target_gate_point      VARCHAR(40),
    target_stage           VARCHAR(40),
    target_artifact_id     UUID,
    target_decision_log_id UUID,
    body                   VARCHAR(2000),
    provenance             VARCHAR(20),
    status                 VARCHAR(20),
    resolution_summary     VARCHAR(1000),
    author_actor           VARCHAR(200),
    resolved_by_actor      VARCHAR(200),
    created_at             TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

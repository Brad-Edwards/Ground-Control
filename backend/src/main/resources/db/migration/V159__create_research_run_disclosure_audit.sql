-- GC-RSCH — Envers audit shadow for research_run_disclosure (ADR-068 §4).
--
-- research_run_id is intentionally absent (@NotAudited on the JPA mapping). All
-- other columns are audited business state. BaseEntity timestamps are mirrored
-- for retention purging.
CREATE TABLE research_run_disclosure_audit (
    id                        UUID         NOT NULL,
    rev                       INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype                   SMALLINT     NOT NULL,
    final_artifact_id         UUID,
    final_attempt_no          INTEGER,
    status                    VARCHAR(20),
    ai_parts_declared_none        BOOLEAN,
    uncertainty_declared_none     BOOLEAN,
    human_approvals_declared_none BOOLEAN,
    actor                         VARCHAR(200),
    created_at                TIMESTAMPTZ,
    updated_at                TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

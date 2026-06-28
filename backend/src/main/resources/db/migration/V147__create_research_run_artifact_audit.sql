-- GC-RSCH — Envers audit shadow for research_run_artifact.
--
-- research_run_id and superseded_by_artifact_id are intentionally absent
-- (@NotAudited on the JPA mappings). All other columns are audited business
-- state. BaseEntity timestamps are mirrored for retention purging.
CREATE TABLE research_run_artifact_audit (
    id              UUID         NOT NULL,
    rev             INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype         SMALLINT     NOT NULL,
    stage           VARCHAR(40),
    artifact_type   VARCHAR(40),
    status          VARCHAR(20),
    locator         VARCHAR(500),
    content_hash    VARCHAR(128),
    idempotency_key VARCHAR(200),
    attempt_no      INTEGER,
    actor           VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

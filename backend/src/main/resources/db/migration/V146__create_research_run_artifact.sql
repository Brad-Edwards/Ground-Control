-- GC-RSCH-F003/F036 — ADR-064 §6–8. Research-run artifact manifest / checkpoint
-- record. Each row is bounded lifecycle metadata proving a stage produced its
-- output; it is the gate authority for the next stage, never the content. At
-- most one ACTIVE record of a given type per run; rework supersedes the prior
-- record. The partial unique index on idempotency_key makes a retried record
-- write reuse the existing row instead of duplicating work.
CREATE TABLE research_run_artifact (
    id                         UUID PRIMARY KEY,
    research_run_id            UUID         NOT NULL REFERENCES research_run(id),
    stage                      VARCHAR(40)  NOT NULL,
    artifact_type              VARCHAR(40)  NOT NULL,
    status                     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    locator                    VARCHAR(500),
    content_hash               VARCHAR(128),
    idempotency_key            VARCHAR(200),
    attempt_no                 INTEGER      NOT NULL DEFAULT 1,
    superseded_by_artifact_id  UUID,
    actor                      VARCHAR(200),
    created_at                 TIMESTAMPTZ  NOT NULL,
    updated_at                 TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_research_run_artifact_status
        CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'FAILED'))
);

CREATE INDEX idx_research_run_artifact_run ON research_run_artifact (research_run_id);
CREATE INDEX idx_research_run_artifact_type
    ON research_run_artifact (research_run_id, artifact_type, status);
CREATE UNIQUE INDEX uq_research_run_artifact_idempotency
    ON research_run_artifact (research_run_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
-- At most one ACTIVE record of a given type per run is a hard lifecycle
-- invariant (gating and resume read the single active manifest row), so it is
-- enforced in the database, not only by a service pre-check: concurrent
-- record/rework calls cannot leave two ACTIVE rows for the same checkpoint.
CREATE UNIQUE INDEX uq_research_run_artifact_active
    ON research_run_artifact (research_run_id, artifact_type)
    WHERE status = 'ACTIVE';

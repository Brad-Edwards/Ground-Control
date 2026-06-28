-- GC-RSCH-R004 / GC-RSCH-N002 / GC-RSCH-N004 — ADR-069 §2. One node in a
-- research run's directed provenance graph: a bounded research referent of a
-- given kind, keyed by a subject_key stable within the run/artifact attempt,
-- plus optional stable references and bounded reproducibility metadata. Stores
-- references and short summaries only — never raw research content. Append-only:
-- rework supersedes the prior ACTIVE row (status SUPERSEDED) and points it at the
-- replacement via superseded_by_node_id. Parent FK is research_run_id ->
-- research_run(id).
CREATE TABLE research_provenance_node (
    id                    UUID PRIMARY KEY,
    research_run_id       UUID          NOT NULL REFERENCES research_run(id),
    kind                  VARCHAR(30)   NOT NULL,
    subject_key           VARCHAR(200)  NOT NULL,
    stage                 VARCHAR(40),
    artifact_type         VARCHAR(40),
    artifact_id           UUID,
    attempt_no            INTEGER,
    locator               VARCHAR(500),
    content_hash          VARCHAR(128),
    external_identifier   VARCHAR(200),
    summary               VARCHAR(2000),
    tool_name             VARCHAR(200),
    tool_version          VARCHAR(100),
    source_action_id      VARCHAR(200),
    status                VARCHAR(20)   NOT NULL,
    superseded_by_node_id UUID,
    actor                 VARCHAR(200),
    idempotency_key       VARCHAR(200),
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_research_provenance_node_kind
        CHECK (kind IN ('USER_GOAL', 'METHODOLOGY_SOURCE', 'QUERY', 'CANDIDATE_SOURCE',
            'FULL_TEXT_ACCESS', 'CHARTING_CELL', 'EVIDENCE_MATRIX_CELL', 'SYNTHESIS_CLAIM',
            'ARGUMENT_MOVE', 'FINAL_PROSE')),
    CONSTRAINT ck_research_provenance_node_status
        CHECK (status IN ('ACTIVE', 'SUPERSEDED'))
);

CREATE INDEX idx_research_provenance_node_run ON research_provenance_node (research_run_id);
CREATE INDEX idx_research_provenance_node_run_kind_subject
    ON research_provenance_node (research_run_id, kind, subject_key);

-- Exactly one ACTIVE node per (run, kind, subject_key); rework supersedes.
CREATE UNIQUE INDEX uq_research_provenance_node_active
    ON research_provenance_node (research_run_id, kind, subject_key)
    WHERE status = 'ACTIVE';

-- Idempotency key is unique per run when present, so a retrying caller reuses the
-- existing node instead of duplicating work.
CREATE UNIQUE INDEX uq_research_provenance_node_idempotency
    ON research_provenance_node (research_run_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

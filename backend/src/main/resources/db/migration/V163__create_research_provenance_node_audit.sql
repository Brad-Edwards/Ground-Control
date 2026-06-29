-- GC-RSCH — Envers audit shadow for research_provenance_node (ADR-069 §4, N004).
--
-- research_run_id and superseded_by_node_id are intentionally absent (@NotAudited
-- on the JPA mapping). All other columns are audited business state. BaseEntity
-- timestamps are mirrored for retention purging.
CREATE TABLE research_provenance_node_audit (
    id                  UUID          NOT NULL,
    rev                 INTEGER       NOT NULL REFERENCES revinfo(rev),
    revtype             SMALLINT      NOT NULL,
    kind                VARCHAR(30),
    subject_key         VARCHAR(200),
    stage               VARCHAR(40),
    artifact_type       VARCHAR(40),
    artifact_id         UUID,
    attempt_no          INTEGER,
    locator             VARCHAR(500),
    content_hash        VARCHAR(128),
    external_identifier VARCHAR(200),
    summary             VARCHAR(2000),
    tool_name           VARCHAR(200),
    tool_version        VARCHAR(100),
    source_action_id    VARCHAR(200),
    status              VARCHAR(20),
    actor               VARCHAR(200),
    idempotency_key     VARCHAR(200),
    created_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

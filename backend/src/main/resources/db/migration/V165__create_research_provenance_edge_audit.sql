-- GC-RSCH — Envers audit shadow for research_provenance_edge (ADR-069 §4, N004).
--
-- research_run_id and superseded_by_edge_id are intentionally absent (@NotAudited
-- on the JPA mapping). All other columns are audited business state. BaseEntity
-- timestamps are mirrored for retention purging.
CREATE TABLE research_provenance_edge_audit (
    id              UUID          NOT NULL,
    rev             INTEGER       NOT NULL REFERENCES revinfo(rev),
    revtype         SMALLINT      NOT NULL,
    from_node_id    UUID,
    to_node_id      UUID,
    relation        VARCHAR(30),
    role            VARCHAR(200),
    summary         VARCHAR(2000),
    status          VARCHAR(20),
    actor           VARCHAR(200),
    idempotency_key VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

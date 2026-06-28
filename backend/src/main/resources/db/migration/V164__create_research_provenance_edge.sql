-- GC-RSCH-R004 / GC-RSCH-N002 — ADR-069 §2. One directed derivation edge in a
-- research run's provenance graph, from an upstream input node (from_node_id) to
-- a downstream output node (to_node_id). A downstream node is traversed backward
-- through its incoming edges to the user goal and supporting source evidence.
-- Self-edges are rejected by a check constraint; directed cycles are rejected by
-- the service. Append-only: rework supersedes the prior ACTIVE row. Both
-- endpoints reference research_provenance_node(id).
CREATE TABLE research_provenance_edge (
    id                    UUID PRIMARY KEY,
    research_run_id       UUID          NOT NULL REFERENCES research_run(id),
    from_node_id          UUID          NOT NULL REFERENCES research_provenance_node(id),
    to_node_id            UUID          NOT NULL REFERENCES research_provenance_node(id),
    relation              VARCHAR(30)   NOT NULL,
    role                  VARCHAR(200),
    summary               VARCHAR(2000),
    status                VARCHAR(20)   NOT NULL,
    superseded_by_edge_id UUID,
    actor                 VARCHAR(200),
    idempotency_key       VARCHAR(200),
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_research_provenance_edge_relation
        CHECK (relation IN ('DERIVED_FROM', 'SUPPORTS', 'SELECTED', 'CITED', 'CONTRIBUTED_TO')),
    CONSTRAINT ck_research_provenance_edge_status
        CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
    CONSTRAINT ck_research_provenance_edge_no_self
        CHECK (from_node_id <> to_node_id)
);

CREATE INDEX idx_research_provenance_edge_run ON research_provenance_edge (research_run_id);
-- Incoming-edge lookup for backward chain traversal.
CREATE INDEX idx_research_provenance_edge_to
    ON research_provenance_edge (research_run_id, to_node_id, status);
-- Outgoing-edge lookup for cycle detection.
CREATE INDEX idx_research_provenance_edge_from
    ON research_provenance_edge (research_run_id, from_node_id, status);

-- Exactly one ACTIVE edge per (run, from, to, relation); rework supersedes.
CREATE UNIQUE INDEX uq_research_provenance_edge_active
    ON research_provenance_edge (research_run_id, from_node_id, to_node_id, relation)
    WHERE status = 'ACTIVE';

-- Idempotency key is unique per run when present.
CREATE UNIQUE INDEX uq_research_provenance_edge_idempotency
    ON research_provenance_edge (research_run_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

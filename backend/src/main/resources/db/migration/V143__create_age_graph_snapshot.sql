-- ADR-062 / issue #252: AGE graph projection snapshot publication.
--
-- Versioned-snapshot publication replaces the destructive in-place rebuild of the live
-- AGE graph. Each materialization builds a new, inactive AGE graph and records it here;
-- the active snapshot a reader queries is simply the row with the greatest version, so
-- publication is INSERT-only and the "pointer swap" is the new row becoming visible at
-- commit. The previously-active snapshot is never destructively mutated, and a failed
-- refresh leaves the prior snapshot untouched.
--
-- This table is plain relational bookkeeping with no AGE dependency, so it applies on
-- plain PostgreSQL too; the AGE graphs themselves are created and dropped at runtime by
-- the snapshot publisher (AgeGraphService) and cleaner (AgeSnapshotCleaner).
CREATE SEQUENCE IF NOT EXISTS age_graph_snapshot_version_seq;

CREATE TABLE age_graph_snapshot (
    version      BIGINT      PRIMARY KEY,
    graph_name   TEXT        NOT NULL UNIQUE,
    scope        TEXT        NOT NULL,
    node_count   INTEGER     NOT NULL CHECK (node_count >= 0),
    edge_count   INTEGER     NOT NULL CHECK (edge_count >= 0),
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_by TEXT
);

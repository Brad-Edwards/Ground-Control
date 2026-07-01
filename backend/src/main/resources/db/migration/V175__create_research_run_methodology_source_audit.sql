-- GC-RSCH-F006 (#1005). Envers audit shadow for research_run_methodology_source.
--
-- selection_id is intentionally absent (@NotAudited on the JPA mapping).
-- All other payload columns are audited business state. BaseEntity timestamps
-- are mirrored for retention purging.
CREATE TABLE research_run_methodology_source_audit (
    id           UUID         NOT NULL,
    rev          INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype      SMALLINT     NOT NULL,
    source_ref   VARCHAR(500),
    source_label VARCHAR(500),
    required     BOOLEAN,
    state        VARCHAR(20),
    actor        VARCHAR(200),
    created_at   TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

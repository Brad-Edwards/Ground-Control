-- GC-RSCH-F006 (#1005). Envers audit shadow for research_run_methodology_selection.
--
-- research_run_id is intentionally absent (@NotAudited on the JPA mapping).
-- All other payload columns are audited business state. BaseEntity timestamps
-- are mirrored for retention purging.
CREATE TABLE research_run_methodology_selection_audit (
    id               UUID         NOT NULL,
    rev              INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype          SMALLINT     NOT NULL,
    method_key       VARCHAR(200),
    method_label     VARCHAR(500),
    profile_version  VARCHAR(100),
    catalog_version  VARCHAR(100),
    actor            VARCHAR(200),
    superseded_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- GC-GRC-004: Envers audit tables for canonical boundary model snapshots,
-- boundaries, assignments, and modeling gaps. Project FKs are @NotAudited.

CREATE TABLE boundary_model_snapshot_audit (
    id                           UUID        NOT NULL,
    rev                          INTEGER     NOT NULL REFERENCES revinfo(rev),
    revtype                      SMALLINT,
    derivation_run_id            UUID,
    schema_version               VARCHAR(40),
    boundary_set_version         VARCHAR(80),
    architecture_model_version   VARCHAR(120),
    commit_sha                   VARCHAR(64),
    declaration_digest           VARCHAR(80),
    boundary_count               INTEGER,
    assignment_count             INTEGER,
    gap_count                    INTEGER,
    created_at                   TIMESTAMPTZ,
    updated_at                   TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE boundary_model_boundary_audit (
    id                    UUID        NOT NULL,
    rev                   INTEGER     NOT NULL REFERENCES revinfo(rev),
    revtype               SMALLINT,
    snapshot_id           UUID,
    boundary_key          VARCHAR(120),
    display_name          VARCHAR(200),
    description           TEXT,
    source                VARCHAR(20),
    path_selectors        TEXT,
    surfaces              TEXT,
    input_fact_keys       TEXT,
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE boundary_model_assignment_audit (
    id                    UUID        NOT NULL,
    rev                   INTEGER     NOT NULL REFERENCES revinfo(rev),
    revtype               SMALLINT,
    snapshot_id           UUID,
    boundary_id           UUID,
    source_fact_key       VARCHAR(200),
    source_fact_kind      VARCHAR(40),
    source_path           VARCHAR(500),
    strategy              VARCHAR(40),
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE boundary_model_gap_audit (
    id                    UUID        NOT NULL,
    rev                   INTEGER     NOT NULL REFERENCES revinfo(rev),
    revtype               SMALLINT,
    snapshot_id           UUID,
    source_fact_key       VARCHAR(200),
    source_fact_kind      VARCHAR(40),
    source_path           VARCHAR(500),
    reason                VARCHAR(40),
    detail                TEXT,
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

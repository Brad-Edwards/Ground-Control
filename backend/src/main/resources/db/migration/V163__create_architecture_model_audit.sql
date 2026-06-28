-- GC-GRC-005: Envers audit tables for architecture model snapshots, stable
-- elements, and snapshot-local element states. Project FKs are @NotAudited.

CREATE TABLE architecture_model_element_audit (
    id                    UUID        NOT NULL,
    rev                   INTEGER     NOT NULL REFERENCES revinfo(rev),
    revtype               SMALLINT,
    stable_key            VARCHAR(200),
    element_kind          VARCHAR(40),
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE architecture_model_snapshot_audit (
    id                    UUID        NOT NULL,
    rev                   INTEGER     NOT NULL REFERENCES revinfo(rev),
    revtype               SMALLINT,
    derivation_run_id     UUID,
    schema_version        VARCHAR(40),
    model_version         VARCHAR(120),
    commit_sha            VARCHAR(64),
    source                VARCHAR(40),
    created_by            VARCHAR(100),
    element_count         INTEGER,
    flow_count            INTEGER,
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE architecture_model_element_state_audit (
    id                         UUID        NOT NULL,
    rev                        INTEGER     NOT NULL REFERENCES revinfo(rev),
    revtype                    SMALLINT,
    snapshot_id                UUID,
    element_id                 UUID,
    stable_key                 VARCHAR(200),
    element_kind               VARCHAR(40),
    label                      VARCHAR(200),
    summary                    TEXT,
    source_path                VARCHAR(500),
    trust_boundary_key         VARCHAR(120),
    data_classification_key    VARCHAR(120),
    flow_source_stable_key     VARCHAR(200),
    flow_target_stable_key     VARCHAR(200),
    flow_direction             VARCHAR(20),
    provenance_source          VARCHAR(20),
    provenance_key             VARCHAR(200),
    adapter_id                 VARCHAR(100),
    tool_name                  VARCHAR(100),
    tool_version               VARCHAR(100),
    ruleset_name               VARCHAR(200),
    ruleset_version            VARCHAR(100),
    derivation_run_id          UUID,
    commit_sha                 VARCHAR(64),
    metadata                   TEXT,
    created_at                 TIMESTAMPTZ,
    updated_at                 TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

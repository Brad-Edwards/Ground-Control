-- GC-GRC-001: Envers audit tables for derivation runs, facts, and capture limits.
-- Project FKs are @NotAudited per the repo's audited-aggregate convention.

CREATE TABLE derivation_run_audit (
    id                    UUID         NOT NULL,
    rev                   INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype               SMALLINT,
    scope_mode            VARCHAR(20),
    commit_sha            VARCHAR(64),
    base_commit_sha       VARCHAR(64),
    paths                 TEXT,
    languages             TEXT,
    surfaces              TEXT,
    requested_by          VARCHAR(200),
    requested_at          TIMESTAMPTZ,
    adapter_count         INTEGER,
    fact_count            INTEGER,
    capture_limit_count   INTEGER,
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE system_model_fact_audit (
    id                    UUID         NOT NULL,
    rev                   INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype               SMALLINT,
    derivation_run_id     UUID,
    fact_kind             VARCHAR(40),
    schema_version        VARCHAR(40),
    fact_key              VARCHAR(200),
    label                 VARCHAR(200),
    summary               TEXT,
    source_path           VARCHAR(500),
    payload               TEXT,
    adapter_id            VARCHAR(100),
    tool_name             VARCHAR(100),
    tool_version          VARCHAR(100),
    ruleset_name          VARCHAR(200),
    ruleset_version       VARCHAR(100),
    commit_sha            VARCHAR(64),
    derived_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE derivation_capture_limit_audit (
    id                    UUID         NOT NULL,
    rev                   INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype               SMALLINT,
    derivation_run_id     UUID,
    adapter_id            VARCHAR(100),
    reason                VARCHAR(40),
    language              VARCHAR(80),
    surface               VARCHAR(80),
    detail                TEXT,
    commit_sha            VARCHAR(64),
    captured_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

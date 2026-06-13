-- GC-GRC-001: server-side derivation runs, normalized facts, and capture limits.
--
-- Derived facts are persisted in Ground Control only. They are never written
-- back into the analyzed repository. Each fact carries its own provenance so
-- downstream aggregate/model builders can reproduce and judge the derivation.

CREATE TABLE derivation_run (
    id                    UUID PRIMARY KEY,
    project_id            UUID         NOT NULL REFERENCES project(id),
    scope_mode            VARCHAR(20)  NOT NULL,
    commit_sha            VARCHAR(64)  NOT NULL,
    base_commit_sha       VARCHAR(64),
    paths                 TEXT,
    languages             TEXT         NOT NULL,
    surfaces              TEXT         NOT NULL,
    requested_by          VARCHAR(200),
    requested_at          TIMESTAMPTZ  NOT NULL,
    adapter_count         INTEGER      NOT NULL,
    fact_count            INTEGER      NOT NULL,
    capture_limit_count   INTEGER      NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_derivation_run_project ON derivation_run (project_id);
CREATE INDEX idx_derivation_run_project_requested ON derivation_run (project_id, requested_at DESC);
CREATE INDEX idx_derivation_run_commit_sha ON derivation_run (commit_sha);

CREATE TABLE system_model_fact (
    id                    UUID PRIMARY KEY,
    project_id            UUID         NOT NULL REFERENCES project(id),
    derivation_run_id     UUID         NOT NULL REFERENCES derivation_run(id),
    fact_kind             VARCHAR(40)  NOT NULL,
    schema_version        VARCHAR(40)  NOT NULL,
    fact_key              VARCHAR(200) NOT NULL,
    label                 VARCHAR(200) NOT NULL,
    summary               TEXT,
    source_path           VARCHAR(500),
    payload               TEXT,
    adapter_id            VARCHAR(100) NOT NULL,
    tool_name             VARCHAR(100) NOT NULL,
    tool_version          VARCHAR(100) NOT NULL,
    ruleset_name          VARCHAR(200) NOT NULL,
    ruleset_version       VARCHAR(100) NOT NULL,
    commit_sha            VARCHAR(64)  NOT NULL,
    derived_at            TIMESTAMPTZ  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_system_model_fact_project ON system_model_fact (project_id);
CREATE INDEX idx_system_model_fact_run ON system_model_fact (derivation_run_id);
CREATE INDEX idx_system_model_fact_project_kind ON system_model_fact (project_id, fact_kind);
CREATE INDEX idx_system_model_fact_project_derived ON system_model_fact (project_id, derived_at DESC);
CREATE INDEX idx_system_model_fact_commit_sha ON system_model_fact (commit_sha);

CREATE TABLE derivation_capture_limit (
    id                    UUID PRIMARY KEY,
    project_id            UUID         NOT NULL REFERENCES project(id),
    derivation_run_id     UUID         NOT NULL REFERENCES derivation_run(id),
    adapter_id            VARCHAR(100),
    reason                VARCHAR(40)  NOT NULL,
    language              VARCHAR(80)  NOT NULL,
    surface               VARCHAR(80)  NOT NULL,
    detail                TEXT,
    commit_sha            VARCHAR(64)  NOT NULL,
    captured_at           TIMESTAMPTZ  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_derivation_capture_limit_project ON derivation_capture_limit (project_id);
CREATE INDEX idx_derivation_capture_limit_run ON derivation_capture_limit (derivation_run_id);
CREATE INDEX idx_derivation_capture_limit_project_reason ON derivation_capture_limit (project_id, reason);
CREATE INDEX idx_derivation_capture_limit_project_captured ON derivation_capture_limit (project_id, captured_at DESC);

-- GC-GRC-005: first-class project-scoped architecture model aggregate.
--
-- Snapshots are the authoritative versioned DFD state. Elements provide stable
-- graph/link identity across snapshots; element_state rows carry the immutable
-- snapshot-local semantics and provenance.

CREATE TABLE architecture_model_element (
    id                    UUID PRIMARY KEY,
    project_id            UUID          NOT NULL REFERENCES project(id),
    stable_key            VARCHAR(200)  NOT NULL,
    element_kind          VARCHAR(40)   NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL
);

CREATE UNIQUE INDEX uq_architecture_model_element_key
    ON architecture_model_element (project_id, stable_key);
CREATE INDEX idx_architecture_model_element_project
    ON architecture_model_element (project_id);

CREATE TABLE architecture_model_snapshot (
    id                    UUID PRIMARY KEY,
    project_id            UUID          NOT NULL REFERENCES project(id),
    derivation_run_id     UUID          REFERENCES derivation_run(id),
    schema_version        VARCHAR(40)   NOT NULL,
    model_version         VARCHAR(120)  NOT NULL,
    commit_sha            VARCHAR(64)   NOT NULL,
    source                VARCHAR(40)   NOT NULL,
    created_by            VARCHAR(100),
    element_count         INTEGER       NOT NULL,
    flow_count            INTEGER       NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL
);

CREATE UNIQUE INDEX uq_architecture_model_snapshot_version
    ON architecture_model_snapshot (project_id, model_version);
CREATE INDEX idx_architecture_model_snapshot_project
    ON architecture_model_snapshot (project_id);
CREATE INDEX idx_architecture_model_snapshot_derivation_run
    ON architecture_model_snapshot (derivation_run_id);

CREATE TABLE architecture_model_element_state (
    id                         UUID PRIMARY KEY,
    project_id                 UUID          NOT NULL REFERENCES project(id),
    snapshot_id                UUID          NOT NULL REFERENCES architecture_model_snapshot(id),
    element_id                 UUID          NOT NULL REFERENCES architecture_model_element(id),
    stable_key                 VARCHAR(200)  NOT NULL,
    element_kind               VARCHAR(40)   NOT NULL,
    label                      VARCHAR(200)  NOT NULL,
    summary                    TEXT,
    source_path                VARCHAR(500),
    trust_boundary_key         VARCHAR(120),
    data_classification_key    VARCHAR(120),
    flow_source_stable_key     VARCHAR(200),
    flow_target_stable_key     VARCHAR(200),
    flow_direction             VARCHAR(20),
    provenance_source          VARCHAR(20)   NOT NULL,
    provenance_key             VARCHAR(200)  NOT NULL,
    adapter_id                 VARCHAR(100),
    tool_name                  VARCHAR(100),
    tool_version               VARCHAR(100),
    ruleset_name               VARCHAR(200),
    ruleset_version            VARCHAR(100),
    derivation_run_id          UUID,
    commit_sha                 VARCHAR(64)   NOT NULL,
    metadata                   TEXT,
    created_at                 TIMESTAMPTZ   NOT NULL,
    updated_at                 TIMESTAMPTZ   NOT NULL
);

CREATE UNIQUE INDEX uq_architecture_model_state_key
    ON architecture_model_element_state (snapshot_id, stable_key);
CREATE INDEX idx_architecture_model_state_project
    ON architecture_model_element_state (project_id);
CREATE INDEX idx_architecture_model_state_snapshot
    ON architecture_model_element_state (snapshot_id);
CREATE INDEX idx_architecture_model_state_element
    ON architecture_model_element_state (element_id);
CREATE INDEX idx_architecture_model_state_flow_source
    ON architecture_model_element_state (project_id, flow_source_stable_key);
CREATE INDEX idx_architecture_model_state_flow_target
    ON architecture_model_element_state (project_id, flow_target_stable_key);

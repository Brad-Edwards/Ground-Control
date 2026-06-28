-- GC-GRC-004: canonical boundary model snapshots over derivation facts.
--
-- The boundary model is a versioned output of a derivation run. TRUST_BOUNDARY
-- system-model facts and declared .ground-control.yaml boundaries are inputs;
-- assignments and modeling gaps are persisted here so downstream GRC coverage
-- and drift checks do not have to infer them from loose fact payloads.

CREATE TABLE boundary_model_snapshot (
    id                           UUID PRIMARY KEY,
    project_id                   UUID          NOT NULL REFERENCES project(id),
    derivation_run_id            UUID          NOT NULL REFERENCES derivation_run(id),
    schema_version               VARCHAR(40)   NOT NULL,
    boundary_set_version         VARCHAR(80)   NOT NULL,
    architecture_model_version   VARCHAR(120)  NOT NULL,
    commit_sha                   VARCHAR(64)   NOT NULL,
    declaration_digest           VARCHAR(80)   NOT NULL,
    boundary_count               INTEGER       NOT NULL,
    assignment_count             INTEGER       NOT NULL,
    gap_count                    INTEGER       NOT NULL,
    created_at                   TIMESTAMPTZ   NOT NULL,
    updated_at                   TIMESTAMPTZ   NOT NULL
);

CREATE UNIQUE INDEX uq_boundary_model_snapshot_run ON boundary_model_snapshot (project_id, derivation_run_id);
CREATE INDEX idx_boundary_model_snapshot_project ON boundary_model_snapshot (project_id);
CREATE INDEX idx_boundary_model_snapshot_version ON boundary_model_snapshot (project_id, boundary_set_version);

CREATE TABLE boundary_model_boundary (
    id                    UUID PRIMARY KEY,
    project_id            UUID          NOT NULL REFERENCES project(id),
    snapshot_id           UUID          NOT NULL REFERENCES boundary_model_snapshot(id),
    boundary_key          VARCHAR(120)  NOT NULL,
    display_name          VARCHAR(200)  NOT NULL,
    description           TEXT,
    source                VARCHAR(20)   NOT NULL,
    path_selectors        TEXT          NOT NULL,
    surfaces              TEXT          NOT NULL,
    input_fact_keys       TEXT          NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL
);

CREATE UNIQUE INDEX uq_boundary_model_boundary_key ON boundary_model_boundary (snapshot_id, boundary_key);
CREATE INDEX idx_boundary_model_boundary_project ON boundary_model_boundary (project_id);
CREATE INDEX idx_boundary_model_boundary_snapshot ON boundary_model_boundary (snapshot_id);

CREATE TABLE boundary_model_assignment (
    id                    UUID PRIMARY KEY,
    project_id            UUID          NOT NULL REFERENCES project(id),
    snapshot_id           UUID          NOT NULL REFERENCES boundary_model_snapshot(id),
    boundary_id           UUID          NOT NULL REFERENCES boundary_model_boundary(id),
    source_fact_key       VARCHAR(200)  NOT NULL,
    source_fact_kind      VARCHAR(40)   NOT NULL,
    source_path           VARCHAR(500)  NOT NULL,
    strategy              VARCHAR(40)   NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_boundary_model_assignment_project ON boundary_model_assignment (project_id);
CREATE INDEX idx_boundary_model_assignment_snapshot ON boundary_model_assignment (snapshot_id);
CREATE INDEX idx_boundary_model_assignment_boundary ON boundary_model_assignment (boundary_id);
CREATE INDEX idx_boundary_model_assignment_fact ON boundary_model_assignment (project_id, source_fact_key);

CREATE TABLE boundary_model_gap (
    id                    UUID PRIMARY KEY,
    project_id            UUID          NOT NULL REFERENCES project(id),
    snapshot_id           UUID          NOT NULL REFERENCES boundary_model_snapshot(id),
    source_fact_key       VARCHAR(200)  NOT NULL,
    source_fact_kind      VARCHAR(40)   NOT NULL,
    source_path           VARCHAR(500),
    reason                VARCHAR(40)   NOT NULL,
    detail                TEXT          NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_boundary_model_gap_project ON boundary_model_gap (project_id);
CREATE INDEX idx_boundary_model_gap_snapshot ON boundary_model_gap (snapshot_id);
CREATE INDEX idx_boundary_model_gap_reason ON boundary_model_gap (project_id, reason);
CREATE INDEX idx_boundary_model_gap_fact ON boundary_model_gap (project_id, source_fact_key);

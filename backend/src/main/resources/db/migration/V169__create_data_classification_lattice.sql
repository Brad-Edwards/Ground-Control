-- GC-GRC-006: project-scoped data classification lattice.
--
-- The lattice is the authoritative server-side information-flow policy: a label
-- taxonomy plus an explicit permitted-flow relation (stored as its reflexive-
-- transitive closure so the allow decision is total). One lattice row per project
-- marks a CUSTOM policy; its absence means the shipped default lattice applies.
-- Label assignments are NOT stored here: they already live on
-- architecture_model_element_state.data_classification_key and version with the
-- model snapshot.

CREATE TABLE data_classification_lattice (
    id              UUID PRIMARY KEY,
    project_id      UUID         NOT NULL REFERENCES project(id),
    schema_version  VARCHAR(60)  NOT NULL,
    policy_version  VARCHAR(80)  NOT NULL,
    source          VARCHAR(20)  NOT NULL,
    label_count     INTEGER      NOT NULL,
    edge_count      INTEGER      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_data_classification_lattice_project
    ON data_classification_lattice (project_id);

CREATE TABLE data_classification_label (
    id            UUID PRIMARY KEY,
    project_id    UUID         NOT NULL REFERENCES project(id),
    lattice_id    UUID         NOT NULL REFERENCES data_classification_lattice(id),
    label_key     VARCHAR(120) NOT NULL,
    display_name  VARCHAR(200) NOT NULL,
    description   TEXT,
    rank          INTEGER,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_data_classification_label_key
    ON data_classification_label (lattice_id, label_key);
CREATE INDEX idx_data_classification_label_project
    ON data_classification_label (project_id);
CREATE INDEX idx_data_classification_label_lattice
    ON data_classification_label (lattice_id);

CREATE TABLE data_classification_flow_rule (
    id              UUID PRIMARY KEY,
    project_id      UUID         NOT NULL REFERENCES project(id),
    lattice_id      UUID         NOT NULL REFERENCES data_classification_lattice(id),
    from_label_key  VARCHAR(120) NOT NULL,
    to_label_key    VARCHAR(120) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_data_classification_flow_rule
    ON data_classification_flow_rule (lattice_id, from_label_key, to_label_key);
CREATE INDEX idx_data_classification_flow_rule_project
    ON data_classification_flow_rule (project_id);
CREATE INDEX idx_data_classification_flow_rule_lattice
    ON data_classification_flow_rule (lattice_id);

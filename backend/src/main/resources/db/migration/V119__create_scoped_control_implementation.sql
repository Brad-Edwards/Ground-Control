-- GC-T003 C1: First-class scoped control implementation aggregate.
-- A named, project-scoped deployment of a catalog control with optional
-- operational asset context and structured implementation scope description.

CREATE TABLE scoped_control_implementation (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    project_id           UUID         NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    uid                  VARCHAR(50)  NOT NULL,
    control_id           UUID         NOT NULL REFERENCES control(id) ON DELETE CASCADE,
    name                 VARCHAR(200) NOT NULL,
    implementation_scope TEXT,
    operational_asset_id UUID REFERENCES operational_asset(id) ON DELETE SET NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_scoped_control_implementation PRIMARY KEY (id),
    CONSTRAINT uq_scoped_control_implementation_uid UNIQUE (project_id, uid)
);

CREATE INDEX idx_scoped_control_impl_project ON scoped_control_implementation(project_id);
CREATE INDEX idx_scoped_control_impl_control ON scoped_control_implementation(control_id);
CREATE INDEX idx_scoped_control_impl_asset   ON scoped_control_implementation(operational_asset_id)
    WHERE operational_asset_id IS NOT NULL;

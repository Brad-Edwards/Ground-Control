CREATE TABLE decision_analysis_record (
    id                     UUID PRIMARY KEY,
    project_id             UUID NOT NULL REFERENCES project(id),
    uid                    VARCHAR(30) NOT NULL,
    title                  VARCHAR(200) NOT NULL,
    model_name             VARCHAR(100) NOT NULL,
    summary                TEXT,
    inputs                 TEXT,
    simulation_parameters  TEXT,
    results                TEXT,
    alternatives           TEXT,
    chosen_alternative     VARCHAR(200),
    rationale              TEXT,
    created_by             VARCHAR(100),
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_decision_analysis_record_project_uid UNIQUE (project_id, uid)
);

CREATE INDEX idx_decision_analysis_record_project_id ON decision_analysis_record(project_id);

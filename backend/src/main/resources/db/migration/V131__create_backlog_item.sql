CREATE TABLE backlog_item (
    id                                    UUID PRIMARY KEY,
    project_id                            UUID NOT NULL REFERENCES project(id),
    uid                                   VARCHAR(30) NOT NULL,
    title                                 VARCHAR(200) NOT NULL,
    description                           TEXT,
    status                                VARCHAR(30) NOT NULL DEFAULT 'CANDIDATE',
    user_business_value                   TEXT,
    time_criticality                      TEXT,
    risk_reduction_opportunity_enablement TEXT,
    job_duration                          TEXT,
    created_by                            VARCHAR(100),
    created_at                            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                            TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_backlog_item_project_uid UNIQUE (project_id, uid),
    CONSTRAINT ck_backlog_item_status CHECK (
        status IN ('CANDIDATE', 'READY', 'IN_PROGRESS', 'DONE', 'ARCHIVED')
    )
);

CREATE INDEX idx_backlog_item_project_id ON backlog_item(project_id);
CREATE INDEX idx_backlog_item_status ON backlog_item(status);

CREATE TABLE backlog_item_audit (
    id                                    UUID NOT NULL,
    rev                                   INTEGER NOT NULL REFERENCES revinfo(rev),
    revtype                               SMALLINT NOT NULL,
    uid                                   VARCHAR(30),
    title                                 VARCHAR(200),
    description                           TEXT,
    status                                VARCHAR(30),
    user_business_value                   TEXT,
    time_criticality                      TEXT,
    risk_reduction_opportunity_enablement TEXT,
    job_duration                          TEXT,
    created_by                            VARCHAR(100),
    created_at                            TIMESTAMP WITH TIME ZONE,
    updated_at                            TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id, rev)
);

CREATE TABLE decision_analysis_record_audit (
    id                     UUID NOT NULL,
    rev                    INTEGER NOT NULL REFERENCES revinfo(rev),
    revtype                SMALLINT NOT NULL,
    uid                    VARCHAR(30),
    title                  VARCHAR(200),
    model_name             VARCHAR(100),
    summary                TEXT,
    inputs                 TEXT,
    simulation_parameters  TEXT,
    results                TEXT,
    alternatives           TEXT,
    chosen_alternative     VARCHAR(200),
    rationale              TEXT,
    created_by             VARCHAR(100),
    created_at             TIMESTAMP WITH TIME ZONE,
    updated_at             TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id, rev)
);

-- GC-T005 (#260): organizational risk appetite and tolerance profiles.
-- A versioned, project-scoped policy aggregate. Identity is (project_id,
-- appetite_key, version); each version carries an explicit effective window so
-- "appetite in force as of date X" is a first-class query. tolerance_thresholds
-- holds the methodology-appropriate tolerance ceilings as a JSON list
-- (JacksonTextCollectionConverters.ToleranceThresholdListConverter).
CREATE TABLE risk_appetite_profile (
    id                   UUID PRIMARY KEY,
    project_id           UUID         NOT NULL REFERENCES project(id),
    appetite_key         VARCHAR(100) NOT NULL,
    name                 VARCHAR(200) NOT NULL,
    version              VARCHAR(50)  NOT NULL,
    methodology_family   VARCHAR(30)  NOT NULL,
    appetite_statement   TEXT,
    tolerance_thresholds TEXT,
    status               VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    effective_from       TIMESTAMPTZ  NOT NULL,
    effective_to         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_risk_appetite_profile_project_key_version UNIQUE (project_id, appetite_key, version)
);

CREATE INDEX idx_risk_appetite_profile_project ON risk_appetite_profile (project_id);
CREATE INDEX idx_risk_appetite_profile_key ON risk_appetite_profile (project_id, appetite_key);

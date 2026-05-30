-- GC-T005 / T006 / T007 / T015: Risk Governance Lifecycle aggregates.
--   * risk_appetite_profile: versioned per-project appetite + tolerance bands
--   * risk_assessment_campaign: phase state machine (planning..closed)
--   * key_risk_indicator: threshold-based monitoring + breach signal
--   * treatment_plan extension: monitoredRiskFactors, updateCadence,
--     riskAssessmentResultId FK (GC-T015)
-- Plus the matching Envers _audit shadow tables per ADR-026 audit-parity rule.

-- ---------------------------------------------------------------------------
-- GC-T005: Risk Appetite Profile
-- ---------------------------------------------------------------------------
CREATE TABLE risk_appetite_profile (
    id                   UUID PRIMARY KEY,
    project_id           UUID         NOT NULL REFERENCES project(id),
    profile_key          VARCHAR(100) NOT NULL,
    name                 VARCHAR(200) NOT NULL,
    version              VARCHAR(50)  NOT NULL,
    appetite_statement   TEXT,
    owner                VARCHAR(200),
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    tolerances           TEXT,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_risk_appetite_profile_project_key_version UNIQUE (project_id, profile_key, version)
);

CREATE INDEX idx_risk_appetite_profile_project ON risk_appetite_profile (project_id);
CREATE INDEX idx_risk_appetite_profile_key ON risk_appetite_profile (project_id, profile_key);

CREATE TABLE risk_appetite_profile_audit (
    id                   UUID         NOT NULL,
    rev                  INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype              SMALLINT,
    profile_key          VARCHAR(100),
    name                 VARCHAR(200),
    version              VARCHAR(50),
    appetite_statement   TEXT,
    owner                VARCHAR(200),
    is_active            BOOLEAN,
    tolerances           TEXT,
    created_at           TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- ---------------------------------------------------------------------------
-- GC-T006: Risk Assessment Campaign
-- ---------------------------------------------------------------------------
CREATE TABLE risk_assessment_campaign (
    id                       UUID PRIMARY KEY,
    project_id               UUID         NOT NULL REFERENCES project(id),
    uid                      VARCHAR(50)  NOT NULL,
    title                    VARCHAR(200) NOT NULL,
    owner                    VARCHAR(200),
    objective                TEXT,
    phase                    VARCHAR(30)  NOT NULL DEFAULT 'PLANNING',
    methodology_profile_id   UUID         REFERENCES methodology_profile(id) ON DELETE SET NULL,
    appetite_profile_id      UUID         REFERENCES risk_appetite_profile(id) ON DELETE SET NULL,
    scheduled_start          TIMESTAMPTZ,
    scheduled_end            TIMESTAMPTZ,
    scope                    TEXT,
    approval_metadata        TEXT,
    scoped_asset_ids         TEXT,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_risk_assessment_campaign_project_uid UNIQUE (project_id, uid)
);

CREATE INDEX idx_risk_assessment_campaign_project ON risk_assessment_campaign (project_id);
CREATE INDEX idx_risk_assessment_campaign_phase ON risk_assessment_campaign (project_id, phase);

CREATE TABLE risk_assessment_campaign_audit (
    id                       UUID         NOT NULL,
    rev                      INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype                  SMALLINT,
    uid                      VARCHAR(50),
    title                    VARCHAR(200),
    owner                    VARCHAR(200),
    objective                TEXT,
    phase                    VARCHAR(30),
    methodology_profile_id   UUID,
    appetite_profile_id      UUID,
    scheduled_start          TIMESTAMPTZ,
    scheduled_end            TIMESTAMPTZ,
    scope                    TEXT,
    approval_metadata        TEXT,
    scoped_asset_ids         TEXT,
    created_at               TIMESTAMPTZ,
    updated_at               TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- ---------------------------------------------------------------------------
-- GC-T007: Key Risk Indicator
-- ---------------------------------------------------------------------------
CREATE TABLE key_risk_indicator (
    id                       UUID PRIMARY KEY,
    project_id               UUID         NOT NULL REFERENCES project(id),
    uid                      VARCHAR(50)  NOT NULL,
    name                     VARCHAR(200) NOT NULL,
    description              TEXT,
    metric_unit              VARCHAR(50),
    yellow_threshold         NUMERIC(38, 10),
    red_threshold            NUMERIC(38, 10),
    direction                VARCHAR(20)  NOT NULL DEFAULT 'HIGHER_IS_WORSE',
    owner                    VARCHAR(200),
    risk_register_record_id  UUID         REFERENCES risk_register_record(id) ON DELETE SET NULL,
    risk_scenario_id         UUID         REFERENCES risk_scenario(id) ON DELETE SET NULL,
    current_value            NUMERIC(38, 10),
    current_band             VARCHAR(10),
    last_measured_at         TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_key_risk_indicator_project_uid UNIQUE (project_id, uid)
);

CREATE INDEX idx_key_risk_indicator_project ON key_risk_indicator (project_id);
CREATE INDEX idx_key_risk_indicator_band ON key_risk_indicator (project_id, current_band)
    WHERE current_band IS NOT NULL;
CREATE INDEX idx_key_risk_indicator_register ON key_risk_indicator (risk_register_record_id)
    WHERE risk_register_record_id IS NOT NULL;
CREATE INDEX idx_key_risk_indicator_scenario ON key_risk_indicator (risk_scenario_id)
    WHERE risk_scenario_id IS NOT NULL;

CREATE TABLE key_risk_indicator_audit (
    id                       UUID         NOT NULL,
    rev                      INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype                  SMALLINT,
    uid                      VARCHAR(50),
    name                     VARCHAR(200),
    description              TEXT,
    metric_unit              VARCHAR(50),
    yellow_threshold         NUMERIC(38, 10),
    red_threshold            NUMERIC(38, 10),
    direction                VARCHAR(20),
    owner                    VARCHAR(200),
    risk_register_record_id  UUID,
    risk_scenario_id         UUID,
    current_value            NUMERIC(38, 10),
    current_band             VARCHAR(10),
    last_measured_at         TIMESTAMPTZ,
    created_at               TIMESTAMPTZ,
    updated_at               TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- ---------------------------------------------------------------------------
-- GC-T015: treatment_plan extension columns
-- ---------------------------------------------------------------------------
ALTER TABLE treatment_plan
    ADD COLUMN risk_assessment_result_id UUID
        REFERENCES risk_assessment_result(id) ON DELETE SET NULL,
    ADD COLUMN monitored_risk_factors TEXT,
    ADD COLUMN update_cadence VARCHAR(50);

ALTER TABLE treatment_plan_audit
    ADD COLUMN risk_assessment_result_id UUID,
    ADD COLUMN monitored_risk_factors TEXT,
    ADD COLUMN update_cadence VARCHAR(50);

CREATE INDEX idx_treatment_plan_rar ON treatment_plan (risk_assessment_result_id)
    WHERE risk_assessment_result_id IS NOT NULL;

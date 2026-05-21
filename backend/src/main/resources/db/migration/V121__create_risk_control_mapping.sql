-- GC-T003 C1-C4 + C8: Canonical risk-control mapping aggregate.
-- Polymorphic endpoints: exactly one control-side FK and exactly one risk-side FK,
-- enforced by CHECK constraints. Asset context (C2), objective/role/scope (C3),
-- methodology influence (C4), and C8 provenance tables follow below.

CREATE TABLE risk_control_mapping (
    id                          UUID        NOT NULL DEFAULT gen_random_uuid(),
    project_id                  UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,

    -- Control-side endpoint (exactly one of control_id / scoped_implementation_id)
    control_id                  UUID REFERENCES control(id) ON DELETE CASCADE,
    scoped_implementation_id    UUID REFERENCES scoped_control_implementation(id) ON DELETE CASCADE,

    -- Risk-side endpoint (exactly one of risk_scenario_id / risk_register_record_id)
    risk_scenario_id            UUID REFERENCES risk_scenario(id) ON DELETE CASCADE,
    risk_register_record_id     UUID REFERENCES risk_register_record(id) ON DELETE CASCADE,

    -- C2: Asset / operational-boundary context (optional)
    operational_asset_id        UUID REFERENCES operational_asset(id) ON DELETE SET NULL,

    -- C3: Mapping-specific fields
    mapping_objective           TEXT,
    control_role                VARCHAR(20) NOT NULL,
    mapping_scope               TEXT,

    -- C4: Methodology-specific influence
    methodology_profile_id      UUID REFERENCES methodology_profile(id) ON DELETE SET NULL,
    methodology_influence        TEXT,  -- JSON via JacksonTextCollectionConverters

    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_risk_control_mapping PRIMARY KEY (id),

    -- Exactly-one-control-side invariant
    CONSTRAINT ck_rcm_control_side CHECK (
        (control_id IS NOT NULL AND scoped_implementation_id IS NULL)
        OR (control_id IS NULL AND scoped_implementation_id IS NOT NULL)
    ),
    -- Exactly-one-risk-side invariant
    CONSTRAINT ck_rcm_risk_side CHECK (
        (risk_scenario_id IS NOT NULL AND risk_register_record_id IS NULL)
        OR (risk_scenario_id IS NULL AND risk_register_record_id IS NOT NULL)
    ),

    -- Uniqueness: one mapping per (control endpoint, risk endpoint, asset context) tuple.
    -- NULL asset = no asset context (treated as a distinct value for uniqueness via partial index below).
    CONSTRAINT uq_rcm_control_scenario_asset
        UNIQUE NULLS NOT DISTINCT (control_id, risk_scenario_id, operational_asset_id),
    CONSTRAINT uq_rcm_control_record_asset
        UNIQUE NULLS NOT DISTINCT (control_id, risk_register_record_id, operational_asset_id),
    CONSTRAINT uq_rcm_scoped_scenario_asset
        UNIQUE NULLS NOT DISTINCT (scoped_implementation_id, risk_scenario_id, operational_asset_id),
    CONSTRAINT uq_rcm_scoped_record_asset
        UNIQUE NULLS NOT DISTINCT (scoped_implementation_id, risk_register_record_id, operational_asset_id)
);

-- Indexes for reverse lookup queries (C1/C5/C6 performance)
CREATE INDEX idx_rcm_project         ON risk_control_mapping(project_id);
CREATE INDEX idx_rcm_control         ON risk_control_mapping(control_id) WHERE control_id IS NOT NULL;
CREATE INDEX idx_rcm_scoped_impl     ON risk_control_mapping(scoped_implementation_id)
    WHERE scoped_implementation_id IS NOT NULL;
CREATE INDEX idx_rcm_risk_scenario   ON risk_control_mapping(risk_scenario_id)
    WHERE risk_scenario_id IS NOT NULL;
CREATE INDEX idx_rcm_risk_record     ON risk_control_mapping(risk_register_record_id)
    WHERE risk_register_record_id IS NOT NULL;
CREATE INDEX idx_rcm_asset           ON risk_control_mapping(operational_asset_id)
    WHERE operational_asset_id IS NOT NULL;

-- C8: Mapping-owned observations provenance edge
CREATE TABLE mapping_observation (
    risk_control_mapping_id UUID NOT NULL REFERENCES risk_control_mapping(id) ON DELETE CASCADE,
    observation_id          UUID NOT NULL REFERENCES observation(id) ON DELETE CASCADE,
    CONSTRAINT pk_mapping_observation PRIMARY KEY (risk_control_mapping_id, observation_id)
);

CREATE INDEX idx_mapping_obs_mapping     ON mapping_observation(risk_control_mapping_id);
CREATE INDEX idx_mapping_obs_observation ON mapping_observation(observation_id);

-- C8: Mapping-owned evidence refs element collection
CREATE TABLE mapping_evidence (
    risk_control_mapping_id UUID         NOT NULL REFERENCES risk_control_mapping(id) ON DELETE CASCADE,
    evidence_ref            VARCHAR(2000) NOT NULL,
    evidence_note           VARCHAR(500),
    evidence_artifact_id    UUID  -- optional FK to evidence_artifact; soft reference, no FK constraint
);

CREATE INDEX idx_mapping_evidence_mapping ON mapping_evidence(risk_control_mapping_id);

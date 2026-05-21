-- GC-T003: Hibernate Envers audit tables for risk_control_mapping.
-- @NotAudited on: project_id, operational_asset_id, methodology_profile_id.
-- mapping_observation is @NotAudited (provenance edge, not audited independently).
-- mapping_evidence is an @ElementCollection — Envers does not audit these by default.

CREATE TABLE risk_control_mapping_audit (
    id                          UUID        NOT NULL,
    rev                         INTEGER     NOT NULL REFERENCES revinfo(rev),
    revtype                     SMALLINT,
    control_id                  UUID,
    scoped_implementation_id    UUID,
    risk_scenario_id            UUID,
    risk_register_record_id     UUID,
    mapping_objective           TEXT,
    control_role                VARCHAR(20),
    mapping_scope               TEXT,
    methodology_influence        TEXT,
    created_at                  TIMESTAMPTZ,
    updated_at                  TIMESTAMPTZ,

    CONSTRAINT pk_risk_control_mapping_audit PRIMARY KEY (id, rev)
);

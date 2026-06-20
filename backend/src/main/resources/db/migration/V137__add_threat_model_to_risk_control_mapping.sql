-- GC-H006: Extend risk_control_mapping to accept a threat_model entry as the analysis-side endpoint.
-- Generalizes the exactly-one-risk-side invariant to exactly-one-analysis-side across
-- {threat_model_id, risk_scenario_id, risk_register_record_id}.

ALTER TABLE risk_control_mapping
    ADD COLUMN threat_model_id UUID REFERENCES threat_model(id) ON DELETE CASCADE;

-- Drop the old 2-way invariant and replace with 3-way exactly-one constraint.
ALTER TABLE risk_control_mapping
    DROP CONSTRAINT ck_rcm_risk_side;

ALTER TABLE risk_control_mapping
    ADD CONSTRAINT ck_rcm_analysis_side CHECK (
        (
            (threat_model_id IS NOT NULL)::int
            + (risk_scenario_id IS NOT NULL)::int
            + (risk_register_record_id IS NOT NULL)::int
        ) = 1
    );

-- Uniqueness is per (control endpoint, analysis endpoint, asset context) tuple, but it must be
-- scoped to the *active* endpoint family. The original V121 constraints were plain
-- `UNIQUE NULLS NOT DISTINCT` over the nullable endpoint columns, which is wrong for a polymorphic
-- table: a row belonging to a different family leaves these columns NULL, and `NULLS NOT DISTINCT`
-- then treats every such row as colliding on (endpoint, NULL, NULL). For example two control→record
-- mappings (risk_scenario_id NULL) of the same control with no asset both project to
-- (control_id, NULL, NULL) on the control_scenario constraint and the second is falsely rejected.
-- Adding the threat endpoint makes this reachable for the shipped scenario/record paths too.
--
-- Fix the whole family: replace the four V121 constraints (and skip adding the two threat
-- constraints as plain constraints) with PARTIAL unique indexes whose predicate restricts each
-- index to rows that actually belong to its endpoint family. operational_asset_id stays nullable
-- and keeps NULLS NOT DISTINCT so that two otherwise-identical mappings with no asset still collide.
ALTER TABLE risk_control_mapping DROP CONSTRAINT uq_rcm_control_scenario_asset;
ALTER TABLE risk_control_mapping DROP CONSTRAINT uq_rcm_control_record_asset;
ALTER TABLE risk_control_mapping DROP CONSTRAINT uq_rcm_scoped_scenario_asset;
ALTER TABLE risk_control_mapping DROP CONSTRAINT uq_rcm_scoped_record_asset;

CREATE UNIQUE INDEX uq_rcm_control_scenario_asset
    ON risk_control_mapping (control_id, risk_scenario_id, operational_asset_id)
    NULLS NOT DISTINCT
    WHERE control_id IS NOT NULL AND risk_scenario_id IS NOT NULL;

CREATE UNIQUE INDEX uq_rcm_control_record_asset
    ON risk_control_mapping (control_id, risk_register_record_id, operational_asset_id)
    NULLS NOT DISTINCT
    WHERE control_id IS NOT NULL AND risk_register_record_id IS NOT NULL;

CREATE UNIQUE INDEX uq_rcm_control_threat_asset
    ON risk_control_mapping (control_id, threat_model_id, operational_asset_id)
    NULLS NOT DISTINCT
    WHERE control_id IS NOT NULL AND threat_model_id IS NOT NULL;

CREATE UNIQUE INDEX uq_rcm_scoped_scenario_asset
    ON risk_control_mapping (scoped_implementation_id, risk_scenario_id, operational_asset_id)
    NULLS NOT DISTINCT
    WHERE scoped_implementation_id IS NOT NULL AND risk_scenario_id IS NOT NULL;

CREATE UNIQUE INDEX uq_rcm_scoped_record_asset
    ON risk_control_mapping (scoped_implementation_id, risk_register_record_id, operational_asset_id)
    NULLS NOT DISTINCT
    WHERE scoped_implementation_id IS NOT NULL AND risk_register_record_id IS NOT NULL;

CREATE UNIQUE INDEX uq_rcm_scoped_threat_asset
    ON risk_control_mapping (scoped_implementation_id, threat_model_id, operational_asset_id)
    NULLS NOT DISTINCT
    WHERE scoped_implementation_id IS NOT NULL AND threat_model_id IS NOT NULL;

-- Index for reverse lookup queries.
CREATE INDEX idx_rcm_threat_model ON risk_control_mapping(threat_model_id)
    WHERE threat_model_id IS NOT NULL;

-- Envers audit shadow: threat_model_id must appear in the audit table.
ALTER TABLE risk_control_mapping_audit
    ADD COLUMN threat_model_id UUID;

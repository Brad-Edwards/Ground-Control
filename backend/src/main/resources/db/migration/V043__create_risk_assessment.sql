CREATE OR REPLACE FUNCTION gc_uuid_from_text(source_text TEXT)
RETURNS UUID
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT (
        substr(md5(source_text), 1, 8) || '-' ||
        substr(md5(source_text), 9, 4) || '-' ||
        substr(md5(source_text), 13, 4) || '-' ||
        substr(md5(source_text), 17, 4) || '-' ||
        substr(md5(source_text), 21, 12)
    )::uuid
$$;

CREATE TABLE methodology_profile (
    id            UUID PRIMARY KEY,
    project_id    UUID         NOT NULL REFERENCES project(id),
    profile_key   VARCHAR(100) NOT NULL,
    name          VARCHAR(200) NOT NULL,
    version       VARCHAR(50)  NOT NULL,
    family        VARCHAR(30)  NOT NULL,
    description   TEXT,
    input_schema  TEXT,
    output_schema TEXT,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_methodology_profile_project_key_version UNIQUE (project_id, profile_key, version)
);

CREATE INDEX idx_methodology_profile_project ON methodology_profile (project_id);
CREATE INDEX idx_methodology_profile_key ON methodology_profile (project_id, profile_key, version);

CREATE TABLE risk_register_record (
    id                  UUID PRIMARY KEY,
    project_id          UUID         NOT NULL REFERENCES project(id),
    uid                 VARCHAR(50)  NOT NULL,
    title               VARCHAR(200) NOT NULL,
    owner               VARCHAR(200),
    status              VARCHAR(20)  NOT NULL DEFAULT 'IDENTIFIED',
    review_cadence      VARCHAR(100),
    next_review_at      TIMESTAMPTZ,
    category_tags       TEXT,
    decision_metadata   TEXT,
    asset_scope_summary TEXT,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_risk_register_record_project_uid UNIQUE (project_id, uid)
);

CREATE INDEX idx_risk_register_record_project ON risk_register_record (project_id);
CREATE INDEX idx_risk_register_record_status ON risk_register_record (status);

CREATE TABLE risk_register_record_scenario (
    risk_register_record_id UUID NOT NULL REFERENCES risk_register_record(id) ON DELETE CASCADE,
    risk_scenario_id        UUID NOT NULL REFERENCES risk_scenario(id) ON DELETE CASCADE,
    PRIMARY KEY (risk_register_record_id, risk_scenario_id)
);

CREATE INDEX idx_rrs_scenario ON risk_register_record_scenario (risk_scenario_id);

CREATE TABLE risk_assessment_result (
    id                    UUID PRIMARY KEY,
    project_id            UUID         NOT NULL REFERENCES project(id),
    risk_scenario_id      UUID         NOT NULL REFERENCES risk_scenario(id) ON DELETE CASCADE,
    methodology_profile_id UUID        NOT NULL REFERENCES methodology_profile(id),
    risk_register_record_id UUID       REFERENCES risk_register_record(id) ON DELETE SET NULL,
    analyst_identity      VARCHAR(200),
    assumptions           TEXT,
    input_factors         TEXT,
    observation_date      TIMESTAMPTZ,
    assessment_at         TIMESTAMPTZ,
    time_horizon          VARCHAR(100),
    confidence            VARCHAR(50),
    uncertainty_metadata  TEXT,
    computed_outputs      TEXT,
    approval_state        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    evidence_refs         TEXT,
    notes                 TEXT,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_risk_assessment_result_project ON risk_assessment_result (project_id);
CREATE INDEX idx_risk_assessment_result_scenario ON risk_assessment_result (risk_scenario_id);
CREATE INDEX idx_risk_assessment_result_record ON risk_assessment_result (risk_register_record_id);

CREATE TABLE risk_assessment_result_observation (
    risk_assessment_result_id UUID NOT NULL REFERENCES risk_assessment_result(id) ON DELETE CASCADE,
    observation_id            UUID NOT NULL REFERENCES observation(id) ON DELETE CASCADE,
    PRIMARY KEY (risk_assessment_result_id, observation_id)
);

CREATE INDEX idx_raro_observation ON risk_assessment_result_observation (observation_id);

CREATE TABLE treatment_plan (
    id                      UUID PRIMARY KEY,
    project_id              UUID         NOT NULL REFERENCES project(id),
    uid                     VARCHAR(50)  NOT NULL,
    title                   VARCHAR(200) NOT NULL,
    risk_register_record_id UUID         NOT NULL REFERENCES risk_register_record(id) ON DELETE CASCADE,
    risk_scenario_id        UUID         REFERENCES risk_scenario(id) ON DELETE SET NULL,
    strategy                VARCHAR(20)  NOT NULL,
    owner                   VARCHAR(200),
    rationale               TEXT,
    due_date                TIMESTAMPTZ,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'PLANNED',
    action_items            TEXT,
    reassessment_triggers   TEXT,
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_treatment_plan_project_uid UNIQUE (project_id, uid)
);

CREATE INDEX idx_treatment_plan_project ON treatment_plan (project_id);
CREATE INDEX idx_treatment_plan_record ON treatment_plan (risk_register_record_id);

INSERT INTO methodology_profile (
    id,
    project_id,
    profile_key,
    name,
    version,
    family,
    description,
    input_schema,
    output_schema,
    status,
    created_at,
    updated_at
)
SELECT
    gc_uuid_from_text(project.id::text || ':LEGACY_QUALITATIVE_V1:1'),
    project.id,
    'LEGACY_QUALITATIVE_V1',
    'Legacy Qualitative',
    '1',
    'CUSTOM',
    'Compatibility profile for migrated pre-methodology qualitative assessments.',
    NULL,
    NULL,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM project
UNION ALL
SELECT
    gc_uuid_from_text(project.id::text || ':FAIR_V3_0:3.0'),
    project.id,
    'FAIR_V3_0',
    'FAIR',
    '3.0',
    'FAIR',
    'Seeded FAIR methodology profile.',
    NULL,
    NULL,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM project
UNION ALL
SELECT
    gc_uuid_from_text(project.id::text || ':NIST_SP800_30_R1:1'),
    project.id,
    'NIST_SP800_30_R1',
    'NIST SP 800-30 Rev. 1',
    '1',
    'NIST_SP800_30_R1',
    'Seeded NIST SP 800-30 Rev. 1 methodology profile.',
    NULL,
    NULL,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM project
UNION ALL
SELECT
    gc_uuid_from_text(project.id::text || ':ISO_27005_V2022:2022'),
    project.id,
    'ISO_27005_V2022',
    'ISO 27005',
    '2022',
    'ISO_27005',
    'Seeded ISO 27005 methodology profile.',
    NULL,
    NULL,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM project;

INSERT INTO risk_register_record (
    id,
    project_id,
    uid,
    title,
    owner,
    status,
    review_cadence,
    next_review_at,
    category_tags,
    decision_metadata,
    asset_scope_summary,
    created_at,
    updated_at
)
SELECT
    gc_uuid_from_text('risk-register:' || risk_scenario.id::text),
    risk_scenario.project_id,
    'RR-' || risk_scenario.uid,
    risk_scenario.title,
    NULL,
    CASE risk_scenario.status
        WHEN 'DRAFT' THEN 'IDENTIFIED'
        WHEN 'IDENTIFIED' THEN 'IDENTIFIED'
        WHEN 'ASSESSED' THEN 'ASSESSED'
        WHEN 'TREATED' THEN 'TREATING'
        WHEN 'ACCEPTED' THEN 'ACCEPTED'
        WHEN 'CLOSED' THEN 'CLOSED'
        ELSE 'IDENTIFIED'
    END,
    NULL,
    NULL,
    NULL,
    NULLIF(
        jsonb_strip_nulls(
            jsonb_build_object(
                'legacy_scenario_status', risk_scenario.status,
                'legacy_observation_refs', risk_scenario.observation_refs,
                'legacy_topology_context', risk_scenario.topology_context
            )
        )::text,
        '{}'
    ),
    risk_scenario.affected_object,
    risk_scenario.created_at,
    risk_scenario.updated_at
FROM risk_scenario;

INSERT INTO risk_register_record_scenario (risk_register_record_id, risk_scenario_id)
SELECT
    gc_uuid_from_text('risk-register:' || risk_scenario.id::text),
    risk_scenario.id
FROM risk_scenario;

DO $$
BEGIN
    IF to_regclass('public.risk_assessment') IS NOT NULL THEN
        EXECUTE $sql$
            INSERT INTO risk_assessment_result (
                id,
                project_id,
                risk_scenario_id,
                methodology_profile_id,
                risk_register_record_id,
                analyst_identity,
                assumptions,
                input_factors,
                observation_date,
                assessment_at,
                time_horizon,
                confidence,
                uncertainty_metadata,
                computed_outputs,
                approval_state,
                evidence_refs,
                notes,
                created_at,
                updated_at
            )
            SELECT
                risk_assessment.id,
                risk_scenario.project_id,
                risk_scenario.id,
                gc_uuid_from_text(risk_scenario.project_id::text || ':LEGACY_QUALITATIVE_V1:1'),
                gc_uuid_from_text('risk-register:' || risk_scenario.id::text),
                COALESCE(NULLIF(risk_scenario.created_by, ''), NULLIF(risk_assessment.decision_owner, '')),
                NULL,
                NULL,
                NULL,
                risk_assessment.assessed_at,
                risk_scenario.time_horizon,
                NULL,
                NULL,
                NULLIF(
                    jsonb_strip_nulls(
                        jsonb_build_object(
                            'legacy_likelihood', risk_assessment.likelihood,
                            'legacy_impact', risk_assessment.impact,
                            'legacy_residual_likelihood', risk_assessment.residual_likelihood,
                            'legacy_residual_impact', risk_assessment.residual_impact
                        )
                    )::text,
                    '{}'
                ),
                CASE
                    WHEN risk_assessment.assessed_at IS NOT NULL THEN 'APPROVED'
                    ELSE 'DRAFT'
                END,
                NULL,
                risk_assessment.notes,
                risk_assessment.created_at,
                risk_assessment.updated_at
            FROM risk_assessment
            JOIN risk_scenario ON risk_scenario.id = risk_assessment.risk_scenario_id;

            UPDATE risk_register_record
            SET
                owner = COALESCE(risk_assessment.decision_owner, risk_register_record.owner),
                next_review_at = COALESCE(risk_assessment.review_due_at, risk_register_record.next_review_at),
                decision_metadata = NULLIF(
                    jsonb_strip_nulls(
                        COALESCE(risk_register_record.decision_metadata, '{}')::jsonb ||
                        jsonb_build_object(
                            'legacy_acceptance_rationale', risk_assessment.acceptance_rationale,
                            'legacy_treatment_strategy', risk_assessment.treatment_strategy
                        )
                    )::text,
                    '{}'
                ),
                updated_at = GREATEST(risk_register_record.updated_at, risk_assessment.updated_at)
            FROM risk_scenario
            JOIN risk_assessment ON risk_assessment.risk_scenario_id = risk_scenario.id
            WHERE risk_register_record.id = gc_uuid_from_text('risk-register:' || risk_scenario.id::text);

            INSERT INTO treatment_plan (
                id,
                project_id,
                uid,
                title,
                risk_register_record_id,
                risk_scenario_id,
                strategy,
                owner,
                rationale,
                due_date,
                status,
                action_items,
                reassessment_triggers,
                created_at,
                updated_at
            )
            SELECT
                gc_uuid_from_text('treatment-plan:' || risk_scenario.id::text),
                risk_scenario.project_id,
                'TP-' || risk_scenario.uid,
                'Treatment plan for ' || risk_scenario.uid,
                gc_uuid_from_text('risk-register:' || risk_scenario.id::text),
                risk_scenario.id,
                CASE COALESCE(risk_assessment.treatment_strategy, '')
                    WHEN 'MITIGATE' THEN 'MITIGATE'
                    WHEN 'ACCEPT' THEN 'ACCEPT'
                    WHEN 'TRANSFER' THEN 'TRANSFER'
                    WHEN 'SHARE' THEN 'SHARE'
                    WHEN 'AVOID' THEN 'AVOID'
                    ELSE 'OTHER'
                END,
                risk_assessment.decision_owner,
                COALESCE(risk_assessment.acceptance_rationale, risk_assessment.treatment_plan),
                risk_assessment.review_due_at,
                CASE risk_scenario.status
                    WHEN 'TREATED' THEN 'IN_PROGRESS'
                    WHEN 'ACCEPTED' THEN 'COMPLETED'
                    WHEN 'CLOSED' THEN 'COMPLETED'
                    ELSE 'PLANNED'
                END,
                CASE
                    WHEN risk_assessment.treatment_plan IS NOT NULL
                        THEN jsonb_build_array(jsonb_build_object('description', risk_assessment.treatment_plan))::text
                    ELSE NULL
                END,
                NULL,
                COALESCE(risk_assessment.created_at, risk_scenario.created_at),
                COALESCE(risk_assessment.updated_at, risk_scenario.updated_at)
            FROM risk_assessment
            JOIN risk_scenario ON risk_scenario.id = risk_assessment.risk_scenario_id
            WHERE risk_assessment.treatment_strategy IS NOT NULL
               OR risk_assessment.treatment_plan IS NOT NULL
               OR risk_assessment.decision_owner IS NOT NULL
               OR risk_assessment.acceptance_rationale IS NOT NULL
               OR risk_assessment.review_due_at IS NOT NULL;

            DROP TABLE risk_assessment;
        $sql$;
    END IF;
END $$;

UPDATE risk_scenario
SET status = CASE status
    WHEN 'DRAFT' THEN 'DRAFT'
    WHEN 'CLOSED' THEN 'ARCHIVED'
    ELSE 'ACTIVE'
END;

ALTER TABLE risk_scenario DROP COLUMN IF EXISTS observation_refs;
ALTER TABLE risk_scenario DROP COLUMN IF EXISTS topology_context;

ALTER TABLE asset_link ALTER COLUMN target_type TYPE VARCHAR(40);
ALTER TABLE asset_link ALTER COLUMN target_identifier DROP NOT NULL;
ALTER TABLE asset_link ADD COLUMN target_entity_id UUID;
CREATE INDEX idx_asset_link_target_entity ON asset_link(target_type, target_entity_id);
CREATE UNIQUE INDEX uq_asset_link_internal_target
    ON asset_link(asset_id, target_type, target_entity_id, link_type)
    WHERE target_entity_id IS NOT NULL;

ALTER TABLE risk_scenario_link ALTER COLUMN target_type TYPE VARCHAR(40);
ALTER TABLE risk_scenario_link ALTER COLUMN target_identifier DROP NOT NULL;
ALTER TABLE risk_scenario_link ADD COLUMN target_entity_id UUID;
CREATE INDEX idx_risk_scenario_link_target_entity ON risk_scenario_link(target_type, target_entity_id);
CREATE UNIQUE INDEX uq_risk_scenario_link_internal_target
    ON risk_scenario_link(risk_scenario_id, target_type, target_entity_id, link_type)
    WHERE target_entity_id IS NOT NULL;

DROP FUNCTION gc_uuid_from_text(TEXT);

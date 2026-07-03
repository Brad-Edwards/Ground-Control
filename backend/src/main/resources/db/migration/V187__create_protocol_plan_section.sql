-- GC-RSCH-F009 / ADR-083 §3 (#1007). One method-specific output section of a
-- protocol plan. Rows are written once with the plan (immutable snapshot, not
-- audited separately). section_key is a stable, plan-unique key; several
-- sections may share a section_kind (e.g. multiple SOURCE_ROLES sections for
-- taxonomy development, one per source role). source_role is populated only
-- for SOURCE_ROLES sections on the taxonomy-development method.
CREATE TABLE protocol_plan_section (
    id                UUID          PRIMARY KEY,
    protocol_plan_id  UUID          NOT NULL REFERENCES protocol_plan(id),
    section_key       VARCHAR(200)  NOT NULL,
    section_kind      VARCHAR(60)   NOT NULL,
    source_role       VARCHAR(40),
    content_summary   VARCHAR(2000) NOT NULL,
    actor             VARCHAR(200),
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_protocol_plan_section_kind
        CHECK (section_kind IN (
            'PCC_SCOPE_FRAMING', 'INFORMATION_SOURCES', 'SEARCH_STRATEGY', 'ELIGIBILITY_CRITERIA',
            'DATABASES_SEARCH_STRINGS', 'SCREENING', 'DATA_EXTRACTION', 'CHARTING',
            'RISK_OF_BIAS_POSTURE', 'SYNTHESIS_PLAN', 'SYNTHESIS_REPORTING', 'REPORTING_STANDARD',
            'CERTAINTY_CLAIM_LIMITS', 'CONSULTATION_POSTURE', 'CRITICAL_APPRAISAL_DECISION',
            'PROTOCOL_REGISTRATION', 'MAPPING_QUESTIONS', 'SEARCH_SCREENING_PLAN', 'CODING_MAP_SCHEMA',
            'CLASSIFICATION_PROVENANCE', 'VISUALIZATION_OUTPUT', 'CLAIM_LIMITS', 'THEORETICAL_FRAME',
            'SELECTION_RATIONALE', 'APPRAISAL_CRITIQUE_DIMENSIONS', 'SYNTHESIS_ARGUMENT_POSTURE',
            'INCLUSION_LIMITS', 'BOUNDED_PURPOSE', 'SEED_SOURCE_STRATEGY', 'INCLUSION_RATIONALE',
            'COMPARISON_DIMENSIONS', 'NON_EXHAUSTIVENESS_DISCLOSURE', 'META_CHARACTERISTIC',
            'UNIT_OF_ANALYSIS', 'SOURCE_ROLES', 'STARTING_CONCEPTS', 'CONSTRUCTION_PROCEDURE',
            'ITERATION_LOG_PROTOCOL', 'ENDING_CONDITIONS', 'EVALUATION_PLAN', 'VALIDITY_THREATS',
            'METHOD_LIMITS', 'NON_CLAIMS')),
    CONSTRAINT ck_protocol_plan_section_source_role
        CHECK (source_role IS NULL OR source_role IN (
            'TAXONOMY_INSTANCE_CORPUS', 'BACKGROUND_FRAMING', 'METHODOLOGY_LITERATURE',
            'VALIDATION_EVALUATION')),
    CONSTRAINT uq_protocol_plan_section_key UNIQUE (protocol_plan_id, section_key)
);

CREATE INDEX idx_protocol_plan_section_plan
    ON protocol_plan_section (protocol_plan_id);

-- GC-RSCH-F008 / GC-RSCH-F009 / ADR-083 (#1007). The structured phase-2
-- protocol plan that sits behind the PROTOCOL_PLAN artifact manifest and
-- answers one active ADR-080 methodology requirements contract attempt.
-- Exactly one plan exists per artifact attempt (unique artifact_id); rework
-- records a new artifact attempt and therefore a new plan. Bounded metadata
-- and references only — never raw plans, queries, prompts, or source text.
CREATE TABLE protocol_plan (
    id                                    UUID         PRIMARY KEY,
    research_run_id                       UUID         NOT NULL REFERENCES research_run(id),
    methodology_requirements_contract_id  UUID         NOT NULL REFERENCES methodology_requirements_contract(id),
    artifact_id                           UUID         NOT NULL REFERENCES research_run_artifact(id),
    attempt_no                            INTEGER      NOT NULL,
    protocol_schema_version               VARCHAR(40)  NOT NULL,
    method_key                            VARCHAR(200) NOT NULL,
    method_profile_version                VARCHAR(100),
    actor                                 VARCHAR(200),
    created_at                            TIMESTAMPTZ  NOT NULL,
    updated_at                            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_protocol_plan_artifact UNIQUE (artifact_id)
);

CREATE INDEX idx_protocol_plan_run
    ON protocol_plan (research_run_id);

-- GC-RSCH-F007 / ADR-079 (#1006). The structured phase-1 methodology
-- requirements contract that sits behind the METHODOLOGY_REQUIREMENTS artifact
-- manifest. Exactly one contract exists per artifact attempt (unique artifact_id);
-- rework records a new artifact attempt and therefore a new contract. Bounded
-- metadata and references only — never raw source text, prompts, or PDFs.
CREATE TABLE methodology_requirements_contract (
    id              UUID         PRIMARY KEY,
    research_run_id UUID         NOT NULL REFERENCES research_run(id),
    selection_id    UUID         NOT NULL REFERENCES research_run_methodology_selection(id),
    artifact_id     UUID         NOT NULL REFERENCES research_run_artifact(id),
    attempt_no      INTEGER      NOT NULL,
    schema_version  VARCHAR(40)  NOT NULL,
    actor           VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_methodology_requirements_contract_artifact UNIQUE (artifact_id)
);

CREATE INDEX idx_methodology_requirements_contract_run
    ON methodology_requirements_contract (research_run_id);

-- GC-RSCH-F007 / GC-RSCH-R002 / ADR-080 §3 (#1006). Links a contract entry to a
-- methodology source that grounds it. The source must belong to the same run's
-- active methodology selection and be in READ state — a claim with no READ
-- source link is never accepted (no model memory as evidence). locator is a
-- bounded artifact-relative anchor (section/page); never source text.
CREATE TABLE methodology_requirements_contract_entry_source_link (
    id         UUID         PRIMARY KEY,
    entry_id   UUID         NOT NULL REFERENCES methodology_requirements_contract_entry(id),
    source_id  UUID         NOT NULL REFERENCES research_run_methodology_source(id),
    locator    VARCHAR(500),
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_contract_entry_source UNIQUE (entry_id, source_id)
);

CREATE INDEX idx_contract_entry_source_link_entry
    ON methodology_requirements_contract_entry_source_link (entry_id);

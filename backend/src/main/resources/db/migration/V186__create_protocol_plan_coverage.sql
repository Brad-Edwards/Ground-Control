-- GC-RSCH-F008 / ADR-083 §2 (#1007). One coverage disposition for a single
-- ADR-080 REQUIREMENT / OPEN_PROTOCOL_QUESTION contract entry within a
-- protocol plan. Rows are written once with the plan (immutable snapshot, not
-- audited separately). contract_entry_key is a stable, plan-unique key
-- referencing the same-run methodology requirements contract entry.
CREATE TABLE protocol_plan_coverage (
    id                  UUID          PRIMARY KEY,
    protocol_plan_id    UUID          NOT NULL REFERENCES protocol_plan(id),
    contract_entry_key  VARCHAR(200)  NOT NULL,
    disposition         VARCHAR(40)   NOT NULL,
    answer_summary      VARCHAR(2000),
    answer_provenance   VARCHAR(40),
    rationale           VARCHAR(2000),
    deferred_to_stage   VARCHAR(40),
    decision_reference  VARCHAR(200),
    actor               VARCHAR(200),
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_protocol_plan_coverage_disposition
        CHECK (disposition IN (
            'FILLED', 'RESOLVED_BY_USER_DECISION', 'DEFERRED_NON_BLOCKING',
            'NOT_APPLICABLE_WITH_RATIONALE', 'BLOCKING_DECISION_REQUIRED')),
    CONSTRAINT ck_protocol_plan_coverage_answer_provenance
        CHECK (answer_provenance IS NULL OR answer_provenance IN (
            'METHODOLOGY_SOURCE', 'RESEARCH_INTAKE', 'USER_DECISION',
            'CITED_SOURCE', 'DEFERRED_PILOT', 'ADAPTER_OUTPUT')),
    CONSTRAINT ck_protocol_plan_coverage_deferred_to_stage
        CHECK (deferred_to_stage IS NULL OR deferred_to_stage IN (
            'METHODOLOGY_SELECTION', 'PROTOCOL_PLANNING', 'SOURCE_SEARCH', 'SCREENING',
            'CHARTING', 'SYNTHESIS', 'ARGUMENT_CONSTRUCTION', 'PROSE_DRAFTING')),
    CONSTRAINT uq_protocol_plan_coverage_key UNIQUE (protocol_plan_id, contract_entry_key)
);

CREATE INDEX idx_protocol_plan_coverage_plan
    ON protocol_plan_coverage (protocol_plan_id);

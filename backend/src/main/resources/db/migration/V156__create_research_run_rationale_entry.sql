-- GC-RSCH-N012 — ADR-068. Append-only rationale-ledger entries. Each row records
-- why one load-bearing decision was made (methodology choice, search/exclusion
-- call, charted value, synthesis or writing claim), its evidentiary basis and
-- provenance. Entries are never mutated; the ledger is the durable trail behind
-- the manuscript. All summary columns are bounded.
CREATE TABLE research_run_rationale_entry (
    id                 UUID PRIMARY KEY,
    research_run_id    UUID          NOT NULL REFERENCES research_run(id),
    stage              VARCHAR(40)   NOT NULL,
    artifact_type      VARCHAR(40),
    artifact_id        UUID,
    attempt_no         INTEGER,
    gate_point         VARCHAR(40),
    kind               VARCHAR(30)   NOT NULL,
    evidence_basis     VARCHAR(30)   NOT NULL,
    provenance         VARCHAR(30)   NOT NULL,
    subject_key        VARCHAR(200)  NOT NULL,
    rationale_summary  VARCHAR(2000) NOT NULL,
    evidence_locator   VARCHAR(500),
    confidence_summary VARCHAR(500),
    actor              VARCHAR(200),
    recorded_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_research_run_rationale_stage
        CHECK (stage IN ('METHODOLOGY_SELECTION', 'PROTOCOL_PLANNING', 'SOURCE_SEARCH', 'SCREENING',
            'CHARTING', 'SYNTHESIS', 'ARGUMENT_CONSTRUCTION', 'PROSE_DRAFTING')),
    CONSTRAINT ck_research_run_rationale_artifact_type
        CHECK (artifact_type IS NULL OR artifact_type IN ('METHODOLOGY_REQUIREMENTS', 'PROTOCOL_PLAN',
            'SEARCH_LOG', 'SCREENING_RESULT', 'CHARTING_DATA', 'SYNTHESIS', 'ARGUMENT_MAP', 'MANUSCRIPT')),
    CONSTRAINT ck_research_run_rationale_gate_point
        CHECK (gate_point IS NULL OR gate_point IN ('METHOD_DECISION', 'PROTOCOL_DECISION', 'SEARCH_DECISION',
            'SYNTHESIS_DECISION', 'WRITING_DECISION')),
    CONSTRAINT ck_research_run_rationale_kind
        CHECK (kind IN ('METHODOLOGY_CHOICE', 'SEARCH_DECISION', 'EXCLUSION', 'CHARTED_VALUE',
            'SYNTHESIS_CLAIM', 'WRITING_CLAIM')),
    CONSTRAINT ck_research_run_rationale_evidence_basis
        CHECK (evidence_basis IN ('METHODOLOGY_SOURCE', 'USER_DECISION', 'CITED_SOURCE', 'FULL_TEXT_SPAN',
            'CHARTED_CELL', 'EVIDENCE_MATRIX_CELL', 'ARGUMENT_MAP_PREMISE', 'MANUSCRIPT_CITATION',
            'POLICY_DEFAULT', 'EXPLICIT_LIMITATION')),
    CONSTRAINT ck_research_run_rationale_provenance
        CHECK (provenance IN ('HUMAN', 'AGENT_RECOMMENDATION', 'AUTONOMOUS_DEFAULT', 'IMPORTED_ARTIFACT',
            'ADAPTER'))
);

CREATE INDEX idx_research_run_rationale_run ON research_run_rationale_entry (research_run_id);

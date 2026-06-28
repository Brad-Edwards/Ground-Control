-- GC-RSCH-N013 — ADR-068 §4. One declared item within a research_run_disclosure:
-- an AI-generated portion of the manuscript or an unresolved uncertainty. An
-- UNRESOLVED_UNCERTAINTY entry carries an uncertainty_category. Entries may
-- cross-reference the rationale, decision-log, and review-comment rows that
-- motivate them. Parent FK is disclosure_id -> research_run_disclosure(id).
CREATE TABLE research_run_disclosure_entry (
    id                   UUID PRIMARY KEY,
    disclosure_id        UUID          NOT NULL REFERENCES research_run_disclosure(id),
    family               VARCHAR(30)   NOT NULL,
    uncertainty_category VARCHAR(30),
    section_key          VARCHAR(200),
    locator              VARCHAR(500),
    model_label          VARCHAR(200),
    summary              VARCHAR(2000) NOT NULL,
    rationale_entry_id   UUID,
    decision_log_id      UUID,
    review_comment_id    UUID,
    actor                VARCHAR(200),
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_research_run_disclosure_entry_family
        CHECK (family IN ('AI_GENERATED_PART', 'UNRESOLVED_UNCERTAINTY')),
    CONSTRAINT ck_research_run_disclosure_entry_category
        CHECK (uncertainty_category IS NULL OR uncertainty_category IN ('SCIENTIFIC', 'ACCESS_GAP',
            'WORKFLOW_ERROR', 'UNRESOLVED_REVIEW'))
);

CREATE INDEX idx_research_run_disclosure_entry_disclosure ON research_run_disclosure_entry (disclosure_id);

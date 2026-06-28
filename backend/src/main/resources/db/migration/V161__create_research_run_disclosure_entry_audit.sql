-- GC-RSCH — Envers audit shadow for research_run_disclosure_entry (ADR-068 §4).
--
-- disclosure_id is intentionally absent (@NotAudited on the JPA mapping). All
-- other columns are audited business state. BaseEntity timestamps are mirrored
-- for retention purging.
CREATE TABLE research_run_disclosure_entry_audit (
    id                   UUID          NOT NULL,
    rev                  INTEGER       NOT NULL REFERENCES revinfo(rev),
    revtype              SMALLINT      NOT NULL,
    family               VARCHAR(30),
    uncertainty_category VARCHAR(30),
    section_key          VARCHAR(200),
    locator              VARCHAR(500),
    model_label          VARCHAR(200),
    summary              VARCHAR(2000),
    rationale_entry_id   UUID,
    decision_log_id      UUID,
    review_comment_id    UUID,
    actor                VARCHAR(200),
    created_at           TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

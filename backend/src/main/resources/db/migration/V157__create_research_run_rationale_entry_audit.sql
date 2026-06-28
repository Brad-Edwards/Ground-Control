-- GC-RSCH — Envers audit shadow for research_run_rationale_entry (ADR-068).
--
-- research_run_id is intentionally absent (@NotAudited on the JPA mapping). All
-- other columns are audited business state. BaseEntity timestamps are mirrored
-- for retention purging.
CREATE TABLE research_run_rationale_entry_audit (
    id                 UUID          NOT NULL,
    rev                INTEGER       NOT NULL REFERENCES revinfo(rev),
    revtype            SMALLINT      NOT NULL,
    stage              VARCHAR(40),
    artifact_type      VARCHAR(40),
    artifact_id        UUID,
    attempt_no         INTEGER,
    gate_point         VARCHAR(40),
    kind               VARCHAR(30),
    evidence_basis     VARCHAR(30),
    provenance         VARCHAR(30),
    subject_key        VARCHAR(200),
    rationale_summary  VARCHAR(2000),
    evidence_locator   VARCHAR(500),
    confidence_summary VARCHAR(500),
    actor              VARCHAR(200),
    recorded_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

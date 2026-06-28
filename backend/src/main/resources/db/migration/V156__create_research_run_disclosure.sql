-- GC-RSCH-N013 — ADR-068 §4. AI-use and unresolved-uncertainty disclosure tied
-- to a run's final manuscript artifact. CURRENT until the manuscript is
-- superseded (then STALE). Completion gating requires a CURRENT disclosure whose
-- final_artifact_id matches the active manuscript and whose two declaration
-- families are covered.
CREATE TABLE research_run_disclosure (
    id                        UUID PRIMARY KEY,
    research_run_id           UUID        NOT NULL REFERENCES research_run(id),
    final_artifact_id         UUID        NOT NULL,
    final_attempt_no          INTEGER     NOT NULL,
    status                    VARCHAR(20) NOT NULL DEFAULT 'CURRENT',
    ai_parts_declared_none        BOOLEAN     NOT NULL DEFAULT FALSE,
    uncertainty_declared_none     BOOLEAN     NOT NULL DEFAULT FALSE,
    human_approvals_declared_none BOOLEAN     NOT NULL DEFAULT FALSE,
    actor                         VARCHAR(200),
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_research_run_disclosure_status
        CHECK (status IN ('CURRENT', 'STALE'))
);

CREATE INDEX idx_research_run_disclosure_run ON research_run_disclosure (research_run_id);

-- At most one CURRENT disclosure per run: createDisclosure is idempotent for the
-- active manuscript and a manuscript rework marks the prior disclosure STALE, so
-- a second CURRENT row is a defect. The partial unique index makes that invariant
-- a database guarantee rather than relying on application checks alone (ADR-068 §4).
CREATE UNIQUE INDEX uq_research_run_disclosure_current
    ON research_run_disclosure (research_run_id)
    WHERE status = 'CURRENT';

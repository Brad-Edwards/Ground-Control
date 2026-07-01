-- GC-RSCH-F006 (#1005, ADR-073). Tracks which methodology was selected for a research
-- run and when. At most one row per run is active (superseded_at IS NULL) at any time;
-- the partial unique index enforces this at the DB level. Superseded rows are retained
-- for audit purposes.
CREATE TABLE research_run_methodology_selection (
    id               UUID         PRIMARY KEY,
    research_run_id  UUID         NOT NULL REFERENCES research_run(id),
    method_key       VARCHAR(200) NOT NULL,
    method_label     VARCHAR(500),
    profile_version  VARCHAR(100),
    catalog_version  VARCHAR(100),
    actor            VARCHAR(200),
    superseded_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_methodology_selection_run ON research_run_methodology_selection (research_run_id);

-- Only one active (superseded_at IS NULL) selection per run at a time.
CREATE UNIQUE INDEX uq_methodology_selection_active_per_run
    ON research_run_methodology_selection (research_run_id)
    WHERE superseded_at IS NULL;

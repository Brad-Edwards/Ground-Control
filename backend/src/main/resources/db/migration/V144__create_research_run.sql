-- GC-RSCH-R001/R003/F003/F036/N007/N011 — ADR-064 / ADR-065.
--
-- Research run lifecycle aggregate (root). Project-scoped (project_id, uid)
-- unique. current_stage and status are separate axes; the CHECK constraints
-- backstop the JPA-side closed enums. Budget caps are snapshotted from
-- research_intake at start; observed usage and the bounded source-disposition
-- counts back the N011 observability snapshot. last_error_* is a bounded
-- failure observation (no stack traces / raw content).
CREATE TABLE research_run (
    id                        UUID PRIMARY KEY,
    project_id                UUID         NOT NULL REFERENCES project(id),
    uid                       VARCHAR(50)  NOT NULL,
    current_stage             VARCHAR(40)  NOT NULL DEFAULT 'METHODOLOGY_SELECTION',
    status                    VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS',
    autonomy_level            VARCHAR(20)  NOT NULL,
    intended_output           VARCHAR(40),
    owner_actor               VARCHAR(200),
    budget_tokens             BIGINT,
    budget_wall_clock_minutes INTEGER,
    budget_cost_usd_micros    BIGINT,
    observed_tokens           BIGINT       NOT NULL DEFAULT 0,
    observed_cost_usd_micros  BIGINT       NOT NULL DEFAULT 0,
    candidate_sources         INTEGER      NOT NULL DEFAULT 0,
    screened_included         INTEGER      NOT NULL DEFAULT 0,
    screened_excluded         INTEGER      NOT NULL DEFAULT 0,
    charted_full_text         INTEGER      NOT NULL DEFAULT 0,
    access_gaps               INTEGER      NOT NULL DEFAULT 0,
    last_error_code           VARCHAR(100),
    last_error_class          VARCHAR(40),
    last_error_summary        VARCHAR(500),
    last_error_at             TIMESTAMPTZ,
    started_at                TIMESTAMPTZ  NOT NULL,
    stopped_at                TIMESTAMPTZ,
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_research_run_project_uid UNIQUE (project_id, uid),
    CONSTRAINT ck_research_run_stage
        CHECK (current_stage IN ('METHODOLOGY_SELECTION', 'PROTOCOL_PLANNING', 'SOURCE_SEARCH',
            'SCREENING', 'CHARTING', 'SYNTHESIS', 'ARGUMENT_CONSTRUCTION', 'PROSE_DRAFTING')),
    CONSTRAINT ck_research_run_status
        CHECK (status IN ('IN_PROGRESS', 'BLOCKED', 'STOPPED', 'FAILED', 'COMPLETED')),
    CONSTRAINT ck_research_run_autonomy
        CHECK (autonomy_level IN ('COPILOT', 'AUTONOMOUS'))
);

CREATE INDEX idx_research_run_project ON research_run (project_id);
CREATE INDEX idx_research_run_status  ON research_run (project_id, status);
CREATE INDEX idx_research_run_stage   ON research_run (project_id, current_stage);

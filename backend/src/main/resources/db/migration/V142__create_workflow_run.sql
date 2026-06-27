-- Issue #859 (ADR-061): workflow-run telemetry & economics reporting read-model.
-- A correlation/projection surface, not the workflow engine (ADR-028). Rows are operational
-- telemetry refined by idempotent re-ingestion; no Envers audit shadow (cf. ADR-059 mcp_tool_event).
-- Only the closed, redacted field set is stored — never prompts, completions, tokens, or raw payloads.

CREATE TABLE workflow_run (
    id                     UUID          PRIMARY KEY,
    project                VARCHAR(200)  NOT NULL,
    repo                   VARCHAR(200),
    issue_number           INTEGER,
    pr_number              INTEGER,
    branch                 VARCHAR(300),
    workflow_type          VARCHAR(100)  NOT NULL,
    runtime_driver         VARCHAR(100),
    started_at             TIMESTAMPTZ,
    ended_at               TIMESTAMPTZ,
    final_state            VARCHAR(40)   NOT NULL,
    outcome                VARCHAR(40)   NOT NULL,
    provenance             VARCHAR(40)   NOT NULL,
    provider               VARCHAR(100),
    model                  VARCHAR(200),
    model_invocation_count INTEGER       CHECK (model_invocation_count >= 0),
    wall_clock_minutes     INTEGER       CHECK (wall_clock_minutes >= 0),
    cost_proxy             NUMERIC(14,4) CHECK (cost_proxy >= 0),
    cost_currency          VARCHAR(10),
    token_usage            BIGINT        CHECK (token_usage >= 0),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Ingestion idempotency key: (project, repo, issue_number, branch). UNIQUE so two concurrent
-- observations of the same run cannot both miss the service SELECT and insert duplicate rows (which
-- would overcount the reporting aggregate). NULLS NOT DISTINCT (Postgres 15+) treats null repo /
-- issue_number / branch as equal, so the key is enforced even for partial run identities; the
-- service's null-safe upsert (findRunForUpsert) plus a conflict-safe retry handle the rare race.
CREATE UNIQUE INDEX idx_workflow_run_upsert_key
    ON workflow_run (project, repo, issue_number, branch) NULLS NOT DISTINCT;

-- Aggregation access patterns: project-scoped window and time window. The aggregate query anchors on
-- COALESCE(started_at, created_at), so created_at is indexed too (also used for the run-list order).
CREATE INDEX idx_workflow_run_project_started ON workflow_run (project, started_at);
CREATE INDEX idx_workflow_run_started ON workflow_run (started_at);
CREATE INDEX idx_workflow_run_project_created ON workflow_run (project, created_at);

-- Element-collection child table for the run's in-scope requirement UIDs (queried by the aggregate
-- requirement filter via EXISTS).
CREATE TABLE workflow_run_requirement_uid (
    run_id          UUID         NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    requirement_uid VARCHAR(100) NOT NULL,
    PRIMARY KEY (run_id, requirement_uid)
);

CREATE TABLE workflow_phase_event (
    id          UUID         PRIMARY KEY,
    run_id      UUID         NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    project     VARCHAR(200) NOT NULL,
    phase       VARCHAR(100) NOT NULL,
    event_type  VARCHAR(40)  NOT NULL,
    cycle_index INTEGER      CHECK (cycle_index >= 0),
    occurred_at TIMESTAMPTZ  NOT NULL,
    duration_ms BIGINT       CHECK (duration_ms >= 0),
    outcome     VARCHAR(100),
    provenance  VARCHAR(40)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Per-phase hot-spot aggregation (project-scoped, time-windowed) and run-scoped lookups.
CREATE INDEX idx_workflow_phase_event_project_phase_occurred ON workflow_phase_event (project, phase, occurred_at);
CREATE INDEX idx_workflow_phase_event_occurred ON workflow_phase_event (occurred_at);
CREATE INDEX idx_workflow_phase_event_run ON workflow_phase_event (run_id);

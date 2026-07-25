-- Issue #1311 / ADR-061 amendment: the workflow reporting model now feeds the
-- revision-addressed mixed graph, so every projected entity participates in Envers.
-- workflow_run_requirement_uid is intentionally absent because the graph contributor
-- never reads that @NotAudited collection.

CREATE TABLE workflow_run_audit (
    id                     UUID         NOT NULL,
    rev                    INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype                SMALLINT     NOT NULL,
    project                VARCHAR(200),
    repo                   VARCHAR(200),
    issue_number           INTEGER,
    pr_number              INTEGER,
    branch                 VARCHAR(300),
    workflow_type          VARCHAR(100),
    runtime_driver         VARCHAR(100),
    started_at             TIMESTAMPTZ,
    ended_at               TIMESTAMPTZ,
    final_state            VARCHAR(40),
    outcome                VARCHAR(40),
    provenance             VARCHAR(40),
    provider               VARCHAR(100),
    model                  VARCHAR(200),
    model_invocation_count INTEGER,
    wall_clock_minutes     INTEGER,
    cost_proxy             NUMERIC(14,4),
    cost_currency          VARCHAR(10),
    token_usage            BIGINT,
    created_at             TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE workflow_phase_event_audit (
    id          UUID         NOT NULL,
    rev         INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype     SMALLINT     NOT NULL,
    run_id      UUID,
    project     VARCHAR(200),
    phase       VARCHAR(100),
    event_type  VARCHAR(40),
    cycle_index INTEGER,
    occurred_at TIMESTAMPTZ,
    duration_ms BIGINT,
    outcome     VARCHAR(100),
    provenance  VARCHAR(40),
    created_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

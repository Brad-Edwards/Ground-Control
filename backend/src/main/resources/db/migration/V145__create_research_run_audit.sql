-- GC-RSCH — Envers audit shadow for research_run.
--
-- project_id is intentionally absent (@NotAudited on the JPA mapping). All
-- other columns are audited business state. BaseEntity timestamps (created_at,
-- updated_at) are mirrored so AuditRetentionJob can age run revisions alongside
-- the rest of the audit tables.
CREATE TABLE research_run_audit (
    id                        UUID         NOT NULL,
    rev                       INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype                   SMALLINT     NOT NULL,
    uid                       VARCHAR(50),
    current_stage             VARCHAR(40),
    status                    VARCHAR(20),
    autonomy_level            VARCHAR(20),
    intended_output           VARCHAR(40),
    owner_actor               VARCHAR(200),
    budget_tokens             BIGINT,
    budget_wall_clock_minutes INTEGER,
    budget_cost_usd_micros    BIGINT,
    observed_tokens           BIGINT,
    observed_cost_usd_micros  BIGINT,
    candidate_sources         INTEGER,
    screened_included         INTEGER,
    screened_excluded         INTEGER,
    charted_full_text         INTEGER,
    access_gaps               INTEGER,
    last_error_code           VARCHAR(100),
    last_error_class          VARCHAR(40),
    last_error_summary        VARCHAR(500),
    last_error_at             TIMESTAMPTZ,
    started_at                TIMESTAMPTZ,
    stopped_at                TIMESTAMPTZ,
    created_at                TIMESTAMPTZ,
    updated_at                TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

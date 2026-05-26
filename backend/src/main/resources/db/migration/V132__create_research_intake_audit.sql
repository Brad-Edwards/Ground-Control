-- GC-RSCH-F001, GC-RSCH-N011: Hibernate Envers audit table for research_intake.
-- See ADR-056.
--
-- @NotAudited on the project FK (matches the codebase pattern for Project
-- back-refs from audited aggregates; Project itself is not audited). All
-- other columns are tracked.

CREATE TABLE research_intake_audit (
    id                          UUID         NOT NULL,
    rev                         INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype                     SMALLINT,
    goal                        TEXT,
    paper_context               TEXT,
    contribution_type           VARCHAR(40),
    intended_output             VARCHAR(40),
    autonomy_level              VARCHAR(20),
    allowed_tools               TEXT,
    privacy_constraints         TEXT,
    budget_tokens               BIGINT,
    budget_wall_clock_minutes   INTEGER,
    budget_cost_usd_micros      BIGINT,
    created_at                  TIMESTAMPTZ,
    updated_at                  TIMESTAMPTZ,

    CONSTRAINT pk_research_intake_audit PRIMARY KEY (id, rev)
);

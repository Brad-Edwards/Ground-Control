-- GC-RSCH-F001, GC-RSCH-R007: research project type + intake aggregate.
-- See ADR-056.
--
-- 1. Add `type` to `project`. Existing rows default to SOFTWARE (the implicit
--    historical default before this PR). Column is NOT NULL going forward;
--    API + service layers enforce the closed enum (SOFTWARE | GRC | RESEARCH).
-- 2. Create `research_intake` 1:1 with `project` (only present when
--    project.type = RESEARCH). Bean Validation + service guard enforce the
--    "intake required iff type = RESEARCH" invariant; the DB carries the
--    1:1 shape via UNIQUE on project_id.

ALTER TABLE project
    ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'SOFTWARE';

-- Drop the DEFAULT after backfill so every new project row must declare type
-- explicitly at the application layer (the API surface enforces a closed enum;
-- a silent DB default would hide validation gaps).
ALTER TABLE project
    ALTER COLUMN type DROP DEFAULT;

CREATE TABLE research_intake (
    id                          UUID                     PRIMARY KEY,
    project_id                  UUID                     NOT NULL UNIQUE
                                                         REFERENCES project(id) ON DELETE CASCADE,
    goal                        TEXT                     NOT NULL,
    paper_context               TEXT,
    contribution_type           VARCHAR(40)              NOT NULL,
    intended_output             VARCHAR(40)              NOT NULL,
    autonomy_level              VARCHAR(20)              NOT NULL,
    allowed_tools               TEXT                     NOT NULL,
    privacy_constraints         TEXT,
    budget_tokens               BIGINT,
    budget_wall_clock_minutes   INTEGER,
    budget_cost_usd_micros      BIGINT,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_research_intake_project_id ON research_intake(project_id);

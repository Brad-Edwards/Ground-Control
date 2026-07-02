-- GC-RSCH-F006 (#1005). One source entry within a methodology selection. The
-- (selection_id, source_ref) pair is unique. Sources start as ATTEMPTED; required
-- sources must reach READ before the METHODOLOGY_REQUIREMENTS artifact gate opens.
CREATE TABLE research_run_methodology_source (
    id           UUID         PRIMARY KEY,
    selection_id UUID         NOT NULL REFERENCES research_run_methodology_selection(id),
    source_ref   VARCHAR(500) NOT NULL,
    source_label VARCHAR(500),
    required     BOOLEAN      NOT NULL DEFAULT FALSE,
    state        VARCHAR(20)  NOT NULL,
    actor        VARCHAR(200),
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_methodology_source_state CHECK (state IN ('ATTEMPTED', 'OBTAINED', 'READ', 'BLOCKED')),
    CONSTRAINT uq_methodology_source_ref UNIQUE (selection_id, source_ref)
);

CREATE INDEX idx_methodology_source_selection ON research_run_methodology_source (selection_id);

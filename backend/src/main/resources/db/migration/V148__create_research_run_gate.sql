-- GC-RSCH-R003 — ADR-064 §4–5. Run-scoped human-gate policy snapshot combined
-- with its durable decision record. One row per gate point per run, created at
-- start with the behavior resolved from autonomy + overrides. The decision is
-- recorded here, never in workspace decisions.md. rationale_summary is a bounded
-- summary only.
CREATE TABLE research_run_gate (
    id                 UUID PRIMARY KEY,
    research_run_id    UUID         NOT NULL REFERENCES research_run(id),
    gate_point         VARCHAR(40)  NOT NULL,
    behavior           VARCHAR(20)  NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    decision_outcome   VARCHAR(20),
    selected_option_id VARCHAR(200),
    rationale_summary  VARCHAR(1000),
    policy_basis       VARCHAR(200),
    resolved_by_actor  VARCHAR(200),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_research_run_gate_point UNIQUE (research_run_id, gate_point),
    CONSTRAINT ck_research_run_gate_point
        CHECK (gate_point IN ('METHOD_DECISION', 'PROTOCOL_DECISION', 'SEARCH_DECISION',
            'SYNTHESIS_DECISION', 'WRITING_DECISION')),
    CONSTRAINT ck_research_run_gate_behavior
        CHECK (behavior IN ('REQUIRE_HUMAN', 'AUTONOMOUS_DEFAULT', 'DISABLED')),
    CONSTRAINT ck_research_run_gate_status
        CHECK (status IN ('PENDING', 'RESOLVED')),
    CONSTRAINT ck_research_run_gate_outcome
        CHECK (decision_outcome IS NULL OR decision_outcome IN ('APPROVED', 'REJECTED', 'AUTO_ACCEPTED'))
);

CREATE INDEX idx_research_run_gate_run ON research_run_gate (research_run_id);

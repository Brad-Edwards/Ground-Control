-- GC-RSCH — Envers audit shadow for research_run_gate.
--
-- research_run_id is intentionally absent (@NotAudited on the JPA mapping). All
-- other columns are audited business state. BaseEntity timestamps are mirrored
-- for retention purging.
CREATE TABLE research_run_gate_audit (
    id                 UUID         NOT NULL,
    rev                INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype            SMALLINT     NOT NULL,
    gate_point         VARCHAR(40),
    behavior           VARCHAR(20),
    status             VARCHAR(20),
    decision_outcome   VARCHAR(20),
    selected_option_id VARCHAR(200),
    rationale_summary  VARCHAR(1000),
    policy_basis       VARCHAR(200),
    resolved_by_actor  VARCHAR(200),
    created_at         TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

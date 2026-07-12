-- GC-RSCH-R005 / ADR-086 §3 (#1008). Envers audit shadow for
-- research_run_operation_authorization. research_run_id is intentionally absent
-- (@NotAudited on the JPA mapping); all other payload columns are audited
-- business state. BaseEntity timestamps are mirrored for retention purging.
CREATE TABLE research_run_operation_authorization_audit (
    id                UUID         NOT NULL,
    rev               INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype           SMALLINT     NOT NULL,
    operation_kind    VARCHAR(40),
    tool_id           VARCHAR(200),
    sandbox_profile   VARCHAR(120),
    data_class        VARCHAR(20),
    destination_class VARCHAR(30),
    requested_form    VARCHAR(20),
    target_class      VARCHAR(120),
    state             VARCHAR(20),
    policy_basis      VARCHAR(500),
    proposing_actor   VARCHAR(200),
    deciding_actor    VARCHAR(200),
    source_action_id  VARCHAR(200),
    expires_at        TIMESTAMPTZ,
    summary           VARCHAR(2000),
    attempt_no        INTEGER,
    created_at        TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

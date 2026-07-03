-- GC-RSCH-R005 / GC-RSCH-N005 / GC-RSCH-N006 — ADR-084 §3 (#1008). Durable,
-- run-scoped authorization record for one research high-risk operation. Bounded
-- facts only — no prompts, generated code, PDFs, page bodies, cookies,
-- credentials, external-write payloads, or absolute paths. Default-deny: a record
-- lands PROPOSED; an admin/operator decision moves it to APPROVED/DENIED, and a
-- one-time-use APPROVED record is spent to CONSUMED. CHECK constraints backstop
-- the JPA-side closed enums. source_action_id is unique per run (retry-safe
-- idempotency).
CREATE TABLE research_run_operation_authorization (
    id                UUID         PRIMARY KEY,
    research_run_id   UUID         NOT NULL REFERENCES research_run(id),
    operation_kind    VARCHAR(40)  NOT NULL,
    tool_id           VARCHAR(200),
    sandbox_profile   VARCHAR(120),
    data_class        VARCHAR(20)  NOT NULL,
    destination_class VARCHAR(30)  NOT NULL,
    requested_form    VARCHAR(20)  NOT NULL,
    target_class      VARCHAR(120),
    state             VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED',
    policy_basis      VARCHAR(500),
    proposing_actor   VARCHAR(200),
    deciding_actor    VARCHAR(200),
    source_action_id  VARCHAR(200),
    expires_at        TIMESTAMPTZ,
    summary           VARCHAR(2000),
    attempt_no        INTEGER      NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_research_op_auth_kind
        CHECK (operation_kind IN ('GENERATED_CODE_EXECUTION', 'BROWSER_ACTIVITY',
            'LAB_HARDWARE_ACTION', 'EXTERNAL_WRITE')),
    CONSTRAINT ck_research_op_auth_data_class
        CHECK (data_class IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_research_op_auth_destination
        CHECK (destination_class IN ('LOCAL', 'AI_PROVIDER', 'CITATION_PROVIDER', 'VERSION_CONTROL',
            'REFERENCE_MANAGER', 'BROWSER_TARGET', 'EXTERNAL_STORAGE', 'LAB_HARDWARE', 'OTHER_EXTERNAL')),
    CONSTRAINT ck_research_op_auth_form
        CHECK (requested_form IN ('NONE', 'DERIVED_METADATA', 'SUMMARY', 'RAW_CONTENT')),
    CONSTRAINT ck_research_op_auth_state
        CHECK (state IN ('PROPOSED', 'APPROVED', 'DENIED', 'CONSUMED', 'EXPIRED'))
);

CREATE INDEX idx_research_op_auth_run
    ON research_run_operation_authorization (research_run_id);

-- Run-scoped idempotency: at most one record per (run, source_action_id).
CREATE UNIQUE INDEX uq_research_op_auth_source_action
    ON research_run_operation_authorization (research_run_id, source_action_id)
    WHERE source_action_id IS NOT NULL;

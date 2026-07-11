-- GC-O009 (b) / GC-P024 / GC-Q016 (b) (#1279): append-only audit of operator-signal attempts against
-- workflow executions. Every attempt is recorded — allowed OR denied — so the gate-authority trail is
-- queryable independently of Temporal history. Bounded, redacted field set: ids, enums, and a bounded
-- reason only; no GitHub/bearer tokens, prompts, completions, or raw request payloads.
-- Not an @Audited entity (it IS the audit log), so there is no _audit companion table.
-- Project is stored as the Ground Control project identifier string (ADR-028 workflow-execution
-- surface is identifier-scoped), consistent with workflow_run — not a project(id) foreign key.
CREATE TABLE operator_signal_audit (
    id                    UUID          PRIMARY KEY,
    actor                 VARCHAR(200)  NOT NULL,
    project               VARCHAR(200)  NOT NULL,
    workflow_id           VARCHAR(500)  NOT NULL,
    run_id                VARCHAR(200),
    signal_type           VARCHAR(40)   NOT NULL,
    contract_version      VARCHAR(100)  NOT NULL,
    authorization_outcome VARCHAR(20)   NOT NULL,
    reason                VARCHAR(1000),
    retry_from_phase      VARCHAR(40),
    reviewer              VARCHAR(40),
    disposition           VARCHAR(40),
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_operator_signal_audit_signal_type
        CHECK (signal_type IN ('CANCEL', 'RETRY_FROM', 'REVIEW_CAP_DISPOSITION')),
    CONSTRAINT ck_operator_signal_audit_authorization_outcome
        CHECK (authorization_outcome IN ('ALLOWED', 'DENIED')),
    CONSTRAINT ck_operator_signal_audit_retry_from_phase
        CHECK (retry_from_phase IS NULL OR retry_from_phase IN (
            'A_PLAN_IMPLEMENT', 'B_QUALITY_GATE', 'C_STAGE_COMMIT_PUSH',
            'D_SHIP_PIPELINE', 'E_POST_MERGE_RECONCILE')),
    CONSTRAINT ck_operator_signal_audit_reviewer
        CHECK (reviewer IS NULL OR reviewer IN ('CODEX', 'TEST_QUALITY')),
    CONSTRAINT ck_operator_signal_audit_disposition
        CHECK (disposition IS NULL OR disposition IN ('PROCEED', 'ONE_MORE_CYCLE', 'ESCALATE_TO_HUMAN'))
);

CREATE INDEX idx_operator_signal_audit_workflow_created
    ON operator_signal_audit (workflow_id, created_at DESC);

CREATE INDEX idx_operator_signal_audit_project_created
    ON operator_signal_audit (project, created_at DESC);

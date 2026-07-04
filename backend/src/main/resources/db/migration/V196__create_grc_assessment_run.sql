-- GC-GRC-016 (#1129): durable on-demand GRC assessment lane run record.
-- Bounded metadata only: no raw source, diffs, scanner output, prompts, or secrets.
CREATE TABLE grc_assessment_run (
    id                        UUID         PRIMARY KEY,
    project_id                UUID         NOT NULL REFERENCES project(id),
    mode                      VARCHAR(30)  NOT NULL,
    scope_type                VARCHAR(40)  NOT NULL,
    scope_values              TEXT,
    commit_sha                VARCHAR(64),
    base_commit_sha           VARCHAR(64),
    languages                 TEXT,
    surfaces                  TEXT,
    declared_boundaries       TEXT,
    threat_pack_id            VARCHAR(200),
    threat_pack_version       VARCHAR(100),
    review_policy             VARCHAR(30)  NOT NULL,
    review_decision           VARCHAR(30)  NOT NULL DEFAULT 'REQUEST_REVIEW',
    state                     VARCHAR(30)  NOT NULL DEFAULT 'READY_FOR_REVIEW',
    reviewed_by               VARCHAR(200),
    reviewed_at               TIMESTAMPTZ,
    review_rationale          VARCHAR(2000),
    idempotency_key           VARCHAR(200),
    partition_count           INTEGER      NOT NULL DEFAULT 0,
    deduped_partition_count   INTEGER      NOT NULL DEFAULT 0,
    duplicate_partition_count INTEGER      NOT NULL DEFAULT 0,
    partitions                TEXT,
    merge_summary             TEXT,
    graph_effects             TEXT,
    graph_effect_count        INTEGER      NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_grc_assessment_mode
        CHECK (mode IN ('MODEL', 'REASSESS', 'RE_SCREEN')),
    CONSTRAINT ck_grc_assessment_scope_type
        CHECK (scope_type IN ('WHOLE_PROJECT', 'PACKAGE_PATH_SET', 'BOUNDARY', 'ASSET',
            'NAMED_THREAT_SET', 'NAMED_RISK_SET', 'STALE_DRIFT_SET')),
    CONSTRAINT ck_grc_assessment_review_policy
        CHECK (review_policy IN ('REQUIRED', 'OPTIONAL', 'DISABLED')),
    CONSTRAINT ck_grc_assessment_review_decision
        CHECK (review_decision IN ('REQUEST_REVIEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_grc_assessment_state
        CHECK (state IN ('READY_FOR_REVIEW', 'COMMITTED', 'REJECTED')),
    CONSTRAINT ck_grc_assessment_counts_nonnegative
        CHECK (partition_count >= 0 AND deduped_partition_count >= 0
            AND duplicate_partition_count >= 0 AND graph_effect_count >= 0)
);

CREATE INDEX idx_grc_assessment_run_project_created
    ON grc_assessment_run (project_id, created_at DESC);

CREATE UNIQUE INDEX uq_grc_assessment_run_idempotency
    ON grc_assessment_run (project_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

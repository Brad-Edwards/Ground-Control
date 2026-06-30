-- V172: evidence_campaign_audit (GC-S005).
--
-- Envers shadow table for evidence_campaign. The project FK is @NotAudited
-- (ADR-038), so project_id is intentionally absent here. AuditRetentionJob ages
-- out rows using the BaseEntity timestamps, so the shadow includes them.
CREATE TABLE evidence_campaign_audit (
    id                      UUID         NOT NULL,
    rev                     INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype                 SMALLINT,
    uid                     VARCHAR(50),
    name                    VARCHAR(200),
    frequency               VARCHAR(20),
    status                  VARCHAR(20),
    adapter_name            VARCHAR(100),
    scope_type              VARCHAR(120),
    schema_id               VARCHAR(120),
    connection_profile_id   VARCHAR(200),
    connection_endpoint     VARCHAR(500),
    credential_ref          VARCHAR(200),
    scope_criteria          TEXT,
    target_control_ids      TEXT,
    retention_days          INTEGER,
    next_run_at             TIMESTAMPTZ,
    last_run_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- V172: evidence_campaign (GC-S005).
--
-- Scheduled, project-scoped evidence-collection campaign. next_run_at is the
-- scheduling cursor; the sweep claims a due campaign by conditionally advancing
-- this cursor (EvidenceCampaignRepository.markClaimedIfDue) so two concurrent
-- ticks cannot double-run the same window. credential_ref is an indirection key
-- only — the raw secret is never stored here.
CREATE TABLE evidence_campaign (
    id                      UUID PRIMARY KEY,
    project_id              UUID          NOT NULL REFERENCES project(id),
    uid                     VARCHAR(50)   NOT NULL,
    name                    VARCHAR(200)  NOT NULL,
    frequency               VARCHAR(20)   NOT NULL,
    status                  VARCHAR(20)   NOT NULL,
    adapter_name            VARCHAR(100)  NOT NULL,
    scope_type              VARCHAR(120)  NOT NULL,
    schema_id               VARCHAR(120),
    connection_profile_id   VARCHAR(200)  NOT NULL,
    connection_endpoint     VARCHAR(500)  NOT NULL,
    credential_ref          VARCHAR(200)  NOT NULL,
    scope_criteria          TEXT,
    target_control_ids      TEXT,
    retention_days          INTEGER,
    next_run_at             TIMESTAMPTZ   NOT NULL,
    last_run_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_evidence_campaign_project_uid UNIQUE (project_id, uid)
);

CREATE INDEX idx_evidence_campaign_project ON evidence_campaign (project_id);
CREATE INDEX idx_evidence_campaign_due     ON evidence_campaign (status, next_run_at);

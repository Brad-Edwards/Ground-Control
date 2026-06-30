-- V173: evidence_campaign_run (GC-S005).
--
-- One execution of an evidence_campaign over a discrete window. Runs are
-- immutable operational telemetry (not Envers-audited) and are aged out by
-- EvidenceCampaignService.pruneExpiredRuns per the parent campaign's
-- retention_days. The (campaign_id, window_start) uniqueness constraint ensures
-- a scheduling window is recorded at most once even under a re-entrant sweep.
CREATE TABLE evidence_campaign_run (
    id                      UUID PRIMARY KEY,
    campaign_id             UUID          NOT NULL REFERENCES evidence_campaign(id),
    project_id              UUID          NOT NULL REFERENCES project(id),
    status                  VARCHAR(20)   NOT NULL,
    window_start            TIMESTAMPTZ   NOT NULL,
    window_end              TIMESTAMPTZ   NOT NULL,
    started_at              TIMESTAMPTZ,
    finished_at             TIMESTAMPTZ,
    artifact_count          INTEGER       NOT NULL,
    error_count             INTEGER       NOT NULL,
    sanitized_error         TEXT,
    produced_artifact_ids   TEXT,
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_evidence_campaign_run_window UNIQUE (campaign_id, window_start)
);

CREATE INDEX idx_evidence_campaign_run_campaign ON evidence_campaign_run (campaign_id, window_start DESC);
CREATE INDEX idx_evidence_campaign_run_project  ON evidence_campaign_run (project_id);
CREATE INDEX idx_evidence_campaign_run_finished ON evidence_campaign_run (finished_at);

-- V131: evidence_artifact.expires_at + validity_window_days (GC-I004 / ADR-045 §8).
--
-- Optional point-in-time expiry for evidence artifacts. Setting expires_at
-- does NOT mutate the artifact after that instant — the append-only contract
-- declared in ADR-045 is preserved. The compliance drift detector + the
-- EvidenceExpirySweepJob derive current-state expiry events from the column;
-- the row is never overwritten by expiry.
--
-- Partial index on expires_at — most artifacts will not carry an expiry, so
-- the sweep query (WHERE expires_at IS NOT NULL AND expires_at <= now) only
-- needs the small subset.
ALTER TABLE evidence_artifact
    ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN validity_window_days INTEGER;

CREATE INDEX idx_evidence_artifact_expires_at
    ON evidence_artifact (expires_at)
    WHERE expires_at IS NOT NULL;

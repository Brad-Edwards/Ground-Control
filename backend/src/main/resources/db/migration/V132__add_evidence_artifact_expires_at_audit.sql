-- V132: evidence_artifact_audit parity for V131 (Envers shadow).
ALTER TABLE evidence_artifact_audit
    ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN validity_window_days INTEGER;

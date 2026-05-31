-- GC-I017 / ADR-026 audit parity: add fair_cam_control_domain column to the
-- Envers shadow table for control_effectiveness_assessment.
ALTER TABLE control_effectiveness_assessment_audit
    ADD COLUMN fair_cam_control_domain VARCHAR(40) NULL;

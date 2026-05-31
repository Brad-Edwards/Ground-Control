-- GC-I017: add FAIR-CAM control-domain attribution to
-- control_effectiveness_assessment.
--
-- FAIR-CAM partitions controls into three functional domains
-- (LOSS_EVENT_CONTROL, VARIANCE_MANAGEMENT_CONTROL, DECISION_SUPPORT_CONTROL).
-- The attribution rides on the existing assessment row rather than a parallel
-- aggregate so historical assessments can be back-filled in place without
-- breaking the existing operating_effectiveness contract from GC-I013.
-- Nullable so non-FAIR programs and legacy rows continue to validate;
-- FAIR-CAM analytics emit an explicit per-assessment limitation when the
-- domain is missing rather than synthesizing a default.
ALTER TABLE control_effectiveness_assessment
    ADD COLUMN fair_cam_control_domain VARCHAR(40) NULL;

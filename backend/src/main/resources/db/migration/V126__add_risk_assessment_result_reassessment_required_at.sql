-- GC-T004 / C8 (#863): durable reassessment signal on RiskAssessmentResult.
-- Set synchronously by ReassessmentSignalService when an upstream treatment,
-- asset, or control change implicates the assessment row.
ALTER TABLE risk_assessment_result
    ADD COLUMN reassessment_required_at TIMESTAMP WITH TIME ZONE;

-- Partial index — most rows will be null. The signal-bearing rows are the
-- small minority and the route-on-it queries (graph projection, future
-- consumers) all filter by IS NOT NULL.
CREATE INDEX idx_risk_assessment_result_reassessment_required_at
    ON risk_assessment_result (reassessment_required_at)
    WHERE reassessment_required_at IS NOT NULL;

ALTER TABLE risk_assessment_result_audit
    ADD COLUMN reassessment_required_at TIMESTAMP WITH TIME ZONE;

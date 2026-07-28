-- Issue #1355 (ADR-090 amendment): the station-result axis becomes persistable, and the findings a
-- station observed become subordinate rows linked to the terminal event of its attempt.
--
-- ADR-090 section 3 makes station result a separate axis from the lifecycle event type: COMPLETED
-- means the phase finished, not that its inspection passed. Until now nothing could express that
-- distinction, so the first aggregate built over this data would have read COMPLETED as a pass and
-- reported a first-pass yield that was an artifact of the schema.
--
-- Forward-only: V142 (the base table), V203 (its Envers shadow), and V204 (source_id) are not edited.

ALTER TABLE workflow_phase_event ADD COLUMN station_id VARCHAR(100);
ALTER TABLE workflow_phase_event ADD COLUMN station_result VARCHAR(40);
ALTER TABLE workflow_phase_event_audit ADD COLUMN station_id VARCHAR(100);
ALTER TABLE workflow_phase_event_audit ADD COLUMN station_result VARCHAR(40);

-- Every pre-existing row was written by a source that never captured a verdict. UNOBSERVED is the
-- honest value: it keeps those rows out of every formula denominator instead of letting them be
-- counted as passes. Deliberately NOT derived from event_type, from the free-text outcome, or from
-- the absence of a later failure — inferring any of those would manufacture the yield history
-- ADR-090 exists to prevent.
UPDATE workflow_phase_event SET station_result = 'UNOBSERVED' WHERE station_result IS NULL;
UPDATE workflow_phase_event_audit SET station_result = 'UNOBSERVED' WHERE station_result IS NULL;

ALTER TABLE workflow_phase_event ALTER COLUMN station_result SET NOT NULL;
ALTER TABLE workflow_phase_event ALTER COLUMN station_result SET DEFAULT 'UNOBSERVED';

-- Aggregates scan by station and verdict; the run scope keeps the index selective per work item.
CREATE INDEX idx_workflow_phase_event_station
    ON workflow_phase_event (project, station_id, station_result);

CREATE TABLE workflow_gate_finding (
    id              UUID         PRIMARY KEY,
    run_id          UUID         NOT NULL REFERENCES workflow_run(id) ON DELETE CASCADE,
    phase_event_id  UUID         NOT NULL REFERENCES workflow_phase_event(id) ON DELETE CASCADE,
    project         VARCHAR(200) NOT NULL,
    station_id      VARCHAR(100) NOT NULL,
    source_kind     VARCHAR(20)  NOT NULL,
    source_id       VARCHAR(100) NOT NULL,
    finding_key     VARCHAR(200) NOT NULL,
    category        VARCHAR(300),
    severity        VARCHAR(60),
    classification  VARCHAR(20),
    disposition     VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    -- Where closing a finding without fixing it was authorized (ADR-029 decision record). Required
    -- by the domain for WONTFIX and NOT_APPLICABLE and refused for FIXED, which the station's next
    -- attempt evidences on its own. Nullable here because OPEN and FIXED rows carry none.
    authorization_reference VARCHAR(500),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);

-- Identity is scoped to the attempt that observed it. This is what makes an at-least-once delivery
-- converge: a retried batch hits the constraint and returns the stored attempt rather than
-- appending a second copy of every finding and doubling the count.
CREATE UNIQUE INDEX idx_workflow_gate_finding_identity
    ON workflow_gate_finding (phase_event_id, finding_key);

-- The three aggregate axes issue #1355 requires: by reviewer/detector, by category, by severity.
CREATE INDEX idx_workflow_gate_finding_source
    ON workflow_gate_finding (project, station_id, source_id);
CREATE INDEX idx_workflow_gate_finding_disposition
    ON workflow_gate_finding (project, disposition);

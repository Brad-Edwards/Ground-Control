-- V137: compliance_drift_event (GC-I004).
--
-- Append-only drift event row published by ComplianceDriftDetectorService
-- when a synchronous control / evidence / code-change signal indicates a
-- compliance-posture shift. Posture itself is a read projection over these
-- events plus the existing compliance-framework-mapping aggregate (cluster 4
-- consumer); this table is the durable signal stream, not a posture cache.
--
-- The row carries low-cardinality categorical fields only (category, source
-- type, source id). Free-text summary is short, length-capped, and never
-- echoes evidence content. Drift events that surface to GitHub issues
-- sanitize artifact content per ADR-029 at the issue boundary.
CREATE TABLE compliance_drift_event (
    id                  UUID PRIMARY KEY,
    project_id          UUID         NOT NULL REFERENCES project(id),
    -- High-level category: CONTROL_STATE_CHANGED / EVIDENCE_EXPIRED /
    -- CODE_CHANGE_IMPACT.
    category            VARCHAR(50)  NOT NULL,
    -- Severity of the inferred posture move: INFO / WARN / SEVERE.
    severity            VARCHAR(20)  NOT NULL,
    -- The first-class entity whose state moved (control, evidence_artifact,
    -- code_file). source_entity_type matches a GraphEntityType when one
    -- exists; source_entity_id is the project-scoped UUID.
    source_entity_type  VARCHAR(60)  NOT NULL,
    source_entity_id    UUID         NOT NULL,
    -- Optional control-effectiveness, evidence, or framework-mapping target
    -- the event implicates downstream. When null the event names a source
    -- only (no inferred dependent yet).
    affected_entity_type VARCHAR(60),
    affected_entity_id  UUID,
    -- Short summary; service-layer cap is 1000 chars and we mirror it here so
    -- the DB rejects any caller that bypasses the service.
    summary             VARCHAR(1000) NOT NULL,
    detected_at         TIMESTAMPTZ  NOT NULL,
    -- Free-text actor name, populated from ActorHolder when present.
    detected_by         VARCHAR(200),
    -- Resolution markers: drift events are append-only — they're never
    -- updated to "fixed"; instead a new event of category RESOLUTION is
    -- published when the posture moves back. acknowledged_at is the one
    -- service-managed mutation (set-once) so dashboards can hide noisy
    -- acknowledged events without losing the row.
    acknowledged_at     TIMESTAMPTZ,
    acknowledged_by     VARCHAR(200),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_compliance_drift_event_project
    ON compliance_drift_event (project_id);
CREATE INDEX idx_compliance_drift_event_project_detected
    ON compliance_drift_event (project_id, detected_at DESC);
CREATE INDEX idx_compliance_drift_event_category
    ON compliance_drift_event (category);
CREATE INDEX idx_compliance_drift_event_source
    ON compliance_drift_event (source_entity_type, source_entity_id);
CREATE INDEX idx_compliance_drift_event_unacknowledged
    ON compliance_drift_event (project_id)
    WHERE acknowledged_at IS NULL;

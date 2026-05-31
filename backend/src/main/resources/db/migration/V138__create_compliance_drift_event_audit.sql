-- V138: compliance_drift_event_audit (Envers shadow for V137).
CREATE TABLE compliance_drift_event_audit (
    id                  UUID         NOT NULL,
    rev                 INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype             SMALLINT,
    project_id          UUID,
    category            VARCHAR(50),
    severity            VARCHAR(20),
    source_entity_type  VARCHAR(60),
    source_entity_id    UUID,
    affected_entity_type VARCHAR(60),
    affected_entity_id  UUID,
    summary             VARCHAR(1000),
    detected_at         TIMESTAMPTZ,
    detected_by         VARCHAR(200),
    acknowledged_at     TIMESTAMPTZ,
    acknowledged_by     VARCHAR(200),
    created_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

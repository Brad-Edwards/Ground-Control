-- GC-T003: Hibernate Envers audit table for the mapping_evidence @ElementCollection.
-- RiskControlMapping is @Audited and evidenceRefs is a List<MappingEvidenceRef>
-- (an @Embeddable @ElementCollection). Hibernate Envers 6.x audits @ElementCollection
-- members by default; it requires this shadow table or schema validation fails at boot.
--
-- Envers names the list-position column SETORDINAL (the default value of
-- embeddableSetOrdinalPropertyName, see EnversSettings.DEFAULT_SETORDINAL_FIELD).
-- The primary key mirrors the Envers middle-table convention:
-- (rev, risk_control_mapping_id, SETORDINAL).

CREATE TABLE mapping_evidence_audit (
    risk_control_mapping_id UUID         NOT NULL,
    rev                     INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype                 SMALLINT,
    "SETORDINAL"            INTEGER,
    evidence_ref            VARCHAR(2000),
    evidence_note           VARCHAR(500),
    evidence_artifact_id    UUID,

    CONSTRAINT pk_mapping_evidence_audit PRIMARY KEY (rev, risk_control_mapping_id, "SETORDINAL")
);

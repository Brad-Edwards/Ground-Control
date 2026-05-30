-- GC-P012: provenance shadow for graph-native GRC interchange imports.
--
-- Captures the client-supplied createdAt / updatedAt / sourceSystem from an
-- imported interchange envelope so the domain entity timestamps remain owned
-- by BaseEntity (per ADR-045) while the original-source temporal metadata is
-- preserved for retrospective traceability.
CREATE TABLE grc_interchange_provenance (
    id                       UUID PRIMARY KEY,
    project_id               UUID NOT NULL REFERENCES project(id),
    entity_kind              VARCHAR(40) NOT NULL,
    entity_id                UUID NOT NULL,
    external_uid             VARCHAR(120) NOT NULL,
    source_system            VARCHAR(120),
    source_created_at        TIMESTAMP WITH TIME ZONE,
    source_updated_at        TIMESTAMP WITH TIME ZONE,
    imported_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    imported_by              VARCHAR(100),
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_grc_interchange_provenance_uid UNIQUE (project_id, entity_kind, external_uid),
    CONSTRAINT ck_grc_interchange_entity_kind CHECK (
        entity_kind IN ('OPERATIONAL_ASSET', 'RISK_SCENARIO', 'CONTROL', 'FINDING', 'EVIDENCE_ARTIFACT')
    )
);

CREATE INDEX idx_grc_interchange_provenance_entity ON grc_interchange_provenance(entity_kind, entity_id);
CREATE INDEX idx_grc_interchange_provenance_project ON grc_interchange_provenance(project_id);

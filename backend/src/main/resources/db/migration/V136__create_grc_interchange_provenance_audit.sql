CREATE TABLE grc_interchange_provenance_audit (
    id                       UUID NOT NULL,
    rev                      INTEGER NOT NULL REFERENCES revinfo(rev),
    revtype                  SMALLINT NOT NULL,
    entity_kind              VARCHAR(40),
    entity_id                UUID,
    external_uid             VARCHAR(120),
    source_system            VARCHAR(120),
    source_created_at        TIMESTAMP WITH TIME ZONE,
    source_updated_at        TIMESTAMP WITH TIME ZONE,
    imported_at              TIMESTAMP WITH TIME ZONE,
    imported_by              VARCHAR(100),
    created_at               TIMESTAMP WITH TIME ZONE,
    updated_at               TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id, rev)
);

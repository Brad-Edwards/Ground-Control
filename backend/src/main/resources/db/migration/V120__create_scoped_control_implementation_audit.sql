-- GC-T003: Hibernate Envers audit table for scoped_control_implementation.
-- @NotAudited on project and operational_asset FKs; all other columns tracked.

CREATE TABLE scoped_control_implementation_audit (
    id                   UUID        NOT NULL,
    rev                  INTEGER     NOT NULL REFERENCES revinfo(rev),
    revtype              SMALLINT,
    uid                  VARCHAR(50),
    control_id           UUID,
    name                 VARCHAR(200),
    implementation_scope TEXT,
    created_at           TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ,

    CONSTRAINT pk_scoped_control_implementation_audit PRIMARY KEY (id, rev)
);

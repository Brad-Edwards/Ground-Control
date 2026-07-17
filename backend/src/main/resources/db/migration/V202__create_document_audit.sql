-- ADR-084 §5 (#1309): Envers audit shadow for document. project_id is intentionally
-- absent (@NotAudited on the JPA mapping, matching every other audited aggregate's
-- owning-project reference); all other payload columns are audited business state.
-- BaseEntity timestamps are mirrored for retention purging.
CREATE TABLE document_audit (
    id          UUID         NOT NULL,
    rev         INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype     SMALLINT     NOT NULL,
    title       VARCHAR(200),
    version     VARCHAR(50),
    description TEXT,
    grammar     TEXT,
    created_by  VARCHAR(100),
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

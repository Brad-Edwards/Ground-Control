-- GC-RSCH-F007 / ADR-079 (#1006). Envers audit shadow for
-- methodology_requirements_contract.
--
-- research_run_id and selection_id are intentionally absent (@NotAudited on the
-- JPA mapping); all other payload columns are audited business state. BaseEntity
-- timestamps are mirrored for retention purging.
CREATE TABLE methodology_requirements_contract_audit (
    id              UUID         NOT NULL,
    rev             INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype         SMALLINT     NOT NULL,
    artifact_id     UUID,
    attempt_no      INTEGER,
    schema_version  VARCHAR(40),
    actor           VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

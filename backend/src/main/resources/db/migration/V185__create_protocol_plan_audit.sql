-- GC-RSCH-F008 / ADR-083 (#1007). Envers audit shadow for protocol_plan.
--
-- research_run_id and methodology_requirements_contract_id are intentionally
-- absent (@NotAudited on the JPA mapping); all other payload columns are
-- audited business state. BaseEntity timestamps are mirrored for retention
-- purging.
CREATE TABLE protocol_plan_audit (
    id                       UUID         NOT NULL,
    rev                      INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype                  SMALLINT     NOT NULL,
    artifact_id              UUID,
    attempt_no               INTEGER,
    protocol_schema_version  VARCHAR(40),
    method_key               VARCHAR(200),
    method_profile_version   VARCHAR(100),
    actor                    VARCHAR(200),
    created_at               TIMESTAMPTZ,
    updated_at               TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

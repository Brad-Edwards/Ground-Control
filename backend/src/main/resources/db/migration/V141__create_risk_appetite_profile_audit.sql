-- GC-T005 (#260) audit parity: Envers shadow table for risk_appetite_profile.
-- Mirrors the audited columns (project_id is @NotAudited and therefore omitted,
-- matching methodology_profile_audit). Columns are nullable per Envers convention.
CREATE TABLE risk_appetite_profile_audit (
    id                   UUID         NOT NULL,
    rev                  INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype              SMALLINT,
    appetite_key         VARCHAR(100),
    name                 VARCHAR(200),
    version              VARCHAR(50),
    methodology_family   VARCHAR(30),
    appetite_statement   TEXT,
    tolerance_thresholds TEXT,
    status               VARCHAR(20),
    effective_from       TIMESTAMPTZ,
    effective_to         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- GC-I002 / GC-I005 / GC-I007 / GC-L011: Compliance Framework Mapping aggregate.
--
-- Single aggregate that maps an internal endpoint (a Requirement OR a Control)
-- to a single compliance-framework element. Exactly one of requirement_id /
-- control_id is set per row, enforced by CHECK ck_cfm_endpoint_xor. The
-- framework side is represented by an enum identifier (SOC2, SOX, ISO_27001,
-- NIST_CSF, PCI_DSS) plus an optional free-form framework_identifier string
-- for genuine externals that do not yet have a first-class enum constant.

CREATE TABLE compliance_framework_mapping (
    id                          UUID         NOT NULL DEFAULT gen_random_uuid(),
    project_id                  UUID         NOT NULL REFERENCES project(id) ON DELETE CASCADE,

    -- Polymorphic source endpoint (exactly one of requirement_id / control_id)
    requirement_id              UUID         REFERENCES requirement(id) ON DELETE CASCADE,
    control_id                  UUID         REFERENCES control(id) ON DELETE CASCADE,

    -- Framework side: seeded identifier OR free-form external (mutually permitted)
    framework                   VARCHAR(40)  NOT NULL,
    framework_identifier        VARCHAR(200),
    framework_version           VARCHAR(60),
    framework_element           VARCHAR(200) NOT NULL,

    -- Per-mapping qualification
    coverage_level              VARCHAR(20)  NOT NULL,
    rationale                   TEXT,

    created_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_compliance_framework_mapping PRIMARY KEY (id),

    -- Exactly-one-source-endpoint invariant (GC-I002 covers requirements;
    -- GC-I005 covers controls; one aggregate row handles both).
    CONSTRAINT ck_cfm_endpoint_xor CHECK (
        (requirement_id IS NOT NULL AND control_id IS NULL)
        OR (requirement_id IS NULL AND control_id IS NOT NULL)
    ),

    -- One mapping per (endpoint, framework, element) tuple. NULLS NOT DISTINCT
    -- so the "endpoint" half of the tuple treats the null side as a single
    -- value distinct from any UUID — mirrors the V121 risk_control_mapping
    -- approach for polymorphic-endpoint uniqueness.
    CONSTRAINT uq_cfm_requirement_framework_element
        UNIQUE NULLS NOT DISTINCT (requirement_id, framework, framework_element),
    CONSTRAINT uq_cfm_control_framework_element
        UNIQUE NULLS NOT DISTINCT (control_id, framework, framework_element)
);

CREATE INDEX idx_cfm_project              ON compliance_framework_mapping(project_id);
CREATE INDEX idx_cfm_requirement          ON compliance_framework_mapping(requirement_id)
    WHERE requirement_id IS NOT NULL;
CREATE INDEX idx_cfm_control              ON compliance_framework_mapping(control_id)
    WHERE control_id IS NOT NULL;
CREATE INDEX idx_cfm_framework            ON compliance_framework_mapping(project_id, framework);
CREATE INDEX idx_cfm_framework_element    ON compliance_framework_mapping(project_id, framework, framework_element);

-- Envers audit table parity. @NotAudited on project_id / requirement_id /
-- control_id (the polymorphic FKs reference @NotAudited related entities and
-- match the V122 risk_control_mapping_audit pattern).
CREATE TABLE compliance_framework_mapping_audit (
    id                          UUID         NOT NULL,
    rev                         INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype                     SMALLINT,
    framework                   VARCHAR(40),
    framework_identifier        VARCHAR(200),
    framework_version           VARCHAR(60),
    framework_element           VARCHAR(200),
    coverage_level              VARCHAR(20),
    rationale                   TEXT,
    created_at                  TIMESTAMPTZ,
    updated_at                  TIMESTAMPTZ,

    CONSTRAINT pk_compliance_framework_mapping_audit PRIMARY KEY (id, rev)
);

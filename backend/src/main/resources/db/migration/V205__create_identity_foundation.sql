-- GC-P024 / ADR-085 (#1282): audited identity and RBAC foundation. V059 remains
-- the authoritative browser principal store until #1411.
CREATE TABLE identity_user (
    id           UUID PRIMARY KEY,
    login_name   VARCHAR(64)  NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    kind         VARCHAR(20)  NOT NULL,
    state        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_identity_user_login CHECK (
        login_name = lower(login_name)
        AND char_length(login_name) BETWEEN 2 AND 64
        AND login_name ~ '^[a-z][a-z0-9._-]{1,63}$'
    ),
    CONSTRAINT ck_identity_user_kind CHECK (kind IN ('HUMAN', 'SERVICE')),
    CONSTRAINT ck_identity_user_state CHECK (state IN ('ACTIVE', 'SUSPENDED', 'DISABLED'))
);

CREATE TABLE identity_group (
    id           UUID PRIMARY KEY,
    name         VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    state        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_identity_group_name CHECK (
        name = lower(name)
        AND char_length(name) BETWEEN 2 AND 100
        AND name ~ '^[a-z][a-z0-9._-]{1,99}$'
    ),
    CONSTRAINT ck_identity_group_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE identity_role (
    id           UUID PRIMARY KEY,
    role_key     VARCHAR(64)  NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    description  VARCHAR(1000),
    built_in     BOOLEAN      NOT NULL DEFAULT FALSE,
    state        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_identity_role_key CHECK (role_key ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT ck_identity_role_state CHECK (state IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE group_membership (
    id              UUID PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES identity_user(id),
    group_id        UUID        NOT NULL REFERENCES identity_group(id),
    state           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    effective_from  TIMESTAMPTZ,
    effective_until TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_group_membership_state CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_group_membership_window CHECK (
        effective_from IS NULL OR effective_until IS NULL OR effective_until > effective_from
    )
);
CREATE UNIQUE INDEX uq_group_membership_active
    ON group_membership(user_id, group_id) WHERE state = 'ACTIVE';
CREATE INDEX idx_group_membership_effective_user
    ON group_membership(user_id, group_id, state, effective_from, effective_until);

CREATE TABLE role_permission_assignment (
    id         UUID PRIMARY KEY,
    role_id    UUID        NOT NULL REFERENCES identity_role(id),
    permission VARCHAR(80) NOT NULL,
    state      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_role_permission_state CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_role_permission_catalog CHECK (permission IN (
        'API_ACCESS', 'IDENTITY_ADMIN', 'EMBEDDINGS_ADMIN', 'ANALYSIS_SWEEP',
        'PACK_REGISTRY_ADMIN', 'MCP_USAGE_READ', 'WORKFLOW_RUN_CROSS_PROJECT_READ',
        'RESEARCH_OPERATION_AUTHORIZE', 'PROJECT_READ', 'PROJECT_WRITE',
        'PROJECT_ACCESS_ADMIN'
    ))
);
CREATE UNIQUE INDEX uq_role_permission_active
    ON role_permission_assignment(role_id, permission) WHERE state = 'ACTIVE';
CREATE INDEX idx_role_permission_lookup
    ON role_permission_assignment(permission, state, role_id);

CREATE TABLE role_grant (
    id              UUID PRIMARY KEY,
    role_id         UUID        NOT NULL REFERENCES identity_role(id),
    user_id         UUID REFERENCES identity_user(id),
    group_id        UUID REFERENCES identity_group(id),
    project_id      UUID REFERENCES project(id),
    state           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    effective_from  TIMESTAMPTZ,
    effective_until TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_role_grant_subject CHECK ((user_id IS NULL) <> (group_id IS NULL)),
    CONSTRAINT ck_role_grant_state CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_role_grant_window CHECK (
        effective_from IS NULL OR effective_until IS NULL OR effective_until > effective_from
    )
);
CREATE UNIQUE INDEX uq_role_grant_user_global_active
    ON role_grant(role_id, user_id) WHERE state = 'ACTIVE' AND user_id IS NOT NULL AND project_id IS NULL;
CREATE UNIQUE INDEX uq_role_grant_group_global_active
    ON role_grant(role_id, group_id) WHERE state = 'ACTIVE' AND group_id IS NOT NULL AND project_id IS NULL;
CREATE UNIQUE INDEX uq_role_grant_user_project_active
    ON role_grant(role_id, user_id, project_id)
    WHERE state = 'ACTIVE' AND user_id IS NOT NULL AND project_id IS NOT NULL;
CREATE UNIQUE INDEX uq_role_grant_group_project_active
    ON role_grant(role_id, group_id, project_id)
    WHERE state = 'ACTIVE' AND group_id IS NOT NULL AND project_id IS NOT NULL;
CREATE INDEX idx_role_grant_user_effective
    ON role_grant(user_id, project_id, state, effective_from, effective_until);
CREATE INDEX idx_role_grant_group_effective
    ON role_grant(group_id, project_id, state, effective_from, effective_until);

CREATE TABLE project_access_grant (
    id              UUID PRIMARY KEY,
    user_id         UUID REFERENCES identity_user(id),
    group_id        UUID REFERENCES identity_group(id),
    project_id      UUID        NOT NULL REFERENCES project(id),
    state           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    effective_from  TIMESTAMPTZ,
    effective_until TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_project_access_subject CHECK ((user_id IS NULL) <> (group_id IS NULL)),
    CONSTRAINT ck_project_access_state CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_project_access_window CHECK (
        effective_from IS NULL OR effective_until IS NULL OR effective_until > effective_from
    )
);
CREATE UNIQUE INDEX uq_project_access_user_active
    ON project_access_grant(user_id, project_id) WHERE state = 'ACTIVE' AND user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_project_access_group_active
    ON project_access_grant(group_id, project_id) WHERE state = 'ACTIVE' AND group_id IS NOT NULL;
CREATE INDEX idx_project_access_user_effective
    ON project_access_grant(user_id, project_id, state, effective_from, effective_until);
CREATE INDEX idx_project_access_group_effective
    ON project_access_grant(group_id, project_id, state, effective_from, effective_until);

-- Stable compatibility roles only. No V059/config principal is imported here.
INSERT INTO identity_role
    (id, role_key, display_name, description, built_in, state, created_at, updated_at)
VALUES
    ('12820000-0000-0000-0000-000000000001', 'USER', 'Compatibility user',
     'Temporary data projection of the legacy ROLE_USER authority', TRUE, 'ACTIVE',
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('12820000-0000-0000-0000-000000000002', 'ADMIN', 'Compatibility administrator',
     'Temporary data projection of the legacy ROLE_ADMIN authority', TRUE, 'ACTIVE',
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO role_permission_assignment
    (id, role_id, permission, state, created_at, updated_at)
VALUES
    ('12820000-0000-0001-0000-000000000001', '12820000-0000-0000-0000-000000000001',
     'API_ACCESS', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('12820000-0000-0002-0000-000000000001', '12820000-0000-0000-0000-000000000002',
     'API_ACCESS', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('12820000-0000-0002-0000-000000000002', '12820000-0000-0000-0000-000000000002',
     'IDENTITY_ADMIN', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('12820000-0000-0002-0000-000000000003', '12820000-0000-0000-0000-000000000002',
     'EMBEDDINGS_ADMIN', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('12820000-0000-0002-0000-000000000004', '12820000-0000-0000-0000-000000000002',
     'ANALYSIS_SWEEP', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('12820000-0000-0002-0000-000000000005', '12820000-0000-0000-0000-000000000002',
     'PACK_REGISTRY_ADMIN', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('12820000-0000-0002-0000-000000000006', '12820000-0000-0000-0000-000000000002',
     'MCP_USAGE_READ', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('12820000-0000-0002-0000-000000000007', '12820000-0000-0000-0000-000000000002',
     'WORKFLOW_RUN_CROSS_PROJECT_READ', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('12820000-0000-0002-0000-000000000008', '12820000-0000-0000-0000-000000000002',
     'RESEARCH_OPERATION_AUTHORIZE', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('12820000-0000-0002-0000-000000000009', '12820000-0000-0000-0000-000000000002',
     'PROJECT_READ', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('12820000-0000-0002-0000-000000000010', '12820000-0000-0000-0000-000000000002',
     'PROJECT_WRITE', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('12820000-0000-0002-0000-000000000011', '12820000-0000-0000-0000-000000000002',
     'PROJECT_ACCESS_ADMIN', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

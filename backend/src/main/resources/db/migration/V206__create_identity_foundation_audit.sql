-- GC-P024 / ADR-085 (#1282): Envers shadows for every identity/access aggregate.
CREATE TABLE identity_user_audit (
    id UUID NOT NULL, rev INTEGER NOT NULL REFERENCES revinfo(rev), revtype SMALLINT NOT NULL,
    login_name VARCHAR(64), display_name VARCHAR(200), kind VARCHAR(20), state VARCHAR(20),
    created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, PRIMARY KEY (id, rev)
);

CREATE TABLE identity_group_audit (
    id UUID NOT NULL, rev INTEGER NOT NULL REFERENCES revinfo(rev), revtype SMALLINT NOT NULL,
    name VARCHAR(100), display_name VARCHAR(200), state VARCHAR(20),
    created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, PRIMARY KEY (id, rev)
);

CREATE TABLE group_membership_audit (
    id UUID NOT NULL, rev INTEGER NOT NULL REFERENCES revinfo(rev), revtype SMALLINT NOT NULL,
    user_id UUID, group_id UUID, state VARCHAR(20), effective_from TIMESTAMPTZ,
    effective_until TIMESTAMPTZ, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE identity_role_audit (
    id UUID NOT NULL, rev INTEGER NOT NULL REFERENCES revinfo(rev), revtype SMALLINT NOT NULL,
    role_key VARCHAR(64), display_name VARCHAR(200), description VARCHAR(1000),
    built_in BOOLEAN, state VARCHAR(20), created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE role_permission_assignment_audit (
    id UUID NOT NULL, rev INTEGER NOT NULL REFERENCES revinfo(rev), revtype SMALLINT NOT NULL,
    role_id UUID, permission VARCHAR(80), state VARCHAR(20),
    created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, PRIMARY KEY (id, rev)
);

CREATE TABLE role_grant_audit (
    id UUID NOT NULL, rev INTEGER NOT NULL REFERENCES revinfo(rev), revtype SMALLINT NOT NULL,
    role_id UUID, user_id UUID, group_id UUID, project_id UUID, state VARCHAR(20),
    effective_from TIMESTAMPTZ, effective_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, PRIMARY KEY (id, rev)
);

CREATE TABLE project_access_grant_audit (
    id UUID NOT NULL, rev INTEGER NOT NULL REFERENCES revinfo(rev), revtype SMALLINT NOT NULL,
    user_id UUID, group_id UUID, project_id UUID, state VARCHAR(20),
    effective_from TIMESTAMPTZ, effective_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, PRIMARY KEY (id, rev)
);

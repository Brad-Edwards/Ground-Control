-- GC-GRC-006: Envers audit tables for the data classification lattice aggregate.
-- Project FKs are @NotAudited; the lattice FK on labels/rules is audited so a
-- policy revision is fully reconstructable. Tampering with the policy or taxonomy
-- would defeat deterministic leak detection (GC-TM-010), so the change history is
-- a security control, not just bookkeeping.

CREATE TABLE data_classification_lattice_audit (
    id              UUID        NOT NULL,
    rev             INTEGER     NOT NULL REFERENCES revinfo(rev),
    revtype         SMALLINT,
    schema_version  VARCHAR(60),
    policy_version  VARCHAR(80),
    source          VARCHAR(20),
    label_count     INTEGER,
    edge_count      INTEGER,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE data_classification_label_audit (
    id            UUID        NOT NULL,
    rev           INTEGER     NOT NULL REFERENCES revinfo(rev),
    revtype       SMALLINT,
    lattice_id    UUID,
    label_key     VARCHAR(120),
    display_name  VARCHAR(200),
    description   TEXT,
    rank          INTEGER,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE data_classification_flow_rule_audit (
    id              UUID        NOT NULL,
    rev             INTEGER     NOT NULL REFERENCES revinfo(rev),
    revtype         SMALLINT,
    lattice_id      UUID,
    from_label_key  VARCHAR(120),
    to_label_key    VARCHAR(120),
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- Issue #1355: Envers shadow for workflow_gate_finding.
--
-- The entity is @Audited because `disposition` is the one field that legitimately changes after
-- insert, and its transition history is exactly what a dispute about "was this ever fixed" needs.
-- Every other column is effectively immutable, so the shadow is small and its revisions are the
-- disposition trail.
--
-- Follows V203's shape: no NOT NULL on the mirrored columns (a DEL revtype writes only the key),
-- and no unique index (uniqueness is a live-table invariant, not an audit-table one).

CREATE TABLE workflow_gate_finding_audit (
    id             UUID         NOT NULL,
    rev            INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype        SMALLINT     NOT NULL,
    run_id         UUID,
    phase_event_id UUID,
    project        VARCHAR(200),
    station_id     VARCHAR(100),
    source_kind    VARCHAR(20),
    source_id      VARCHAR(100),
    finding_key    VARCHAR(200),
    category       VARCHAR(300),
    severity       VARCHAR(60),
    classification VARCHAR(20),
    disposition    VARCHAR(20),
    authorization_reference VARCHAR(500),
    occurred_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

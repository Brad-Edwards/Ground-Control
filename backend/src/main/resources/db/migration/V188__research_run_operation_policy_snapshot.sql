-- GC-RSCH-R005 / GC-RSCH-N005 / GC-RSCH-N006 — ADR-085 §2 (#1008). Snapshot the
-- high-risk operation policy onto the run at start so later intake edits never
-- re-authorize an active or completed run. allowed_tools is the tool inventory,
-- egress_policy is the structured default-deny egress allowlist (JSON), and
-- privacy_constraints is preserved operator context (display-only). Existing
-- rows default to an empty inventory / empty policy (local-only).
ALTER TABLE research_run
    ADD COLUMN allowed_tools       TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN privacy_constraints TEXT,
    ADD COLUMN egress_policy       TEXT NOT NULL DEFAULT '[]';

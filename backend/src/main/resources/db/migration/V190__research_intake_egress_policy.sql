-- GC-RSCH-N006 — ADR-086 §2 (#1008). Structured, default-deny data-egress policy
-- declared at project intake and snapshotted onto each run at start. JSON list of
-- egress allowances (dataClass, destinationClass, allowedForm, purpose). Existing
-- rows default to an empty policy (local-only). Distinct from the free-text
-- privacy_constraints, which is operator context, not the enforcement input.
ALTER TABLE research_intake
    ADD COLUMN egress_policy TEXT NOT NULL DEFAULT '[]';

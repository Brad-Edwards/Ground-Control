-- GC-RSCH-N006 / ADR-085 §2 (#1008). Envers audit shadow column for the intake
-- egress policy added in V190. Nullable per Envers audit-column convention.
ALTER TABLE research_intake_audit
    ADD COLUMN egress_policy TEXT;

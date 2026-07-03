-- GC-RSCH-R005 / ADR-086 §2 (#1008). Envers audit shadow columns for the run
-- policy snapshot added in V188. Audit columns are nullable (Envers records the
-- value at each revision; pre-existing revisions have none).
ALTER TABLE research_run_audit
    ADD COLUMN allowed_tools       TEXT,
    ADD COLUMN privacy_constraints TEXT,
    ADD COLUMN egress_policy       TEXT;

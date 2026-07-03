-- GC-RSCH-N006 / ADR-085 §2 (#1008). Envers audit shadow column for the artifact
-- data class added in V192. Nullable per Envers audit-column convention.
ALTER TABLE research_run_artifact_audit
    ADD COLUMN data_class VARCHAR(20);

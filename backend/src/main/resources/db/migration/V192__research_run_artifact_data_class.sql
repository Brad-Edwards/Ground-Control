-- GC-RSCH-N006 — ADR-085 §2 (#1008). Optional privacy/access classification of a
-- research artifact's material. Null = unclassified. The class is the input the
-- egress policy checks before any external disclosure. The CHECK backstops the
-- JPA-side closed enum.
ALTER TABLE research_run_artifact
    ADD COLUMN data_class VARCHAR(20);

ALTER TABLE research_run_artifact
    ADD CONSTRAINT ck_research_run_artifact_data_class
        CHECK (data_class IS NULL OR data_class IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'));

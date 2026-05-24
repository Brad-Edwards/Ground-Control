-- GC-T004 / C5 (#861): typed methodology-strategy binding for TreatmentStrategy.OTHER.
-- A normal FK (NOT "ON DELETE SET NULL"): silently nulling the profile would leave
-- strategy = OTHER without the vocabulary that gives it meaning.
ALTER TABLE treatment_plan
    ADD COLUMN methodology_profile_id   UUID REFERENCES methodology_profile(id),
    ADD COLUMN methodology_strategy_key VARCHAR(100);

CREATE INDEX idx_treatment_plan_methodology_profile
    ON treatment_plan (methodology_profile_id)
    WHERE methodology_profile_id IS NOT NULL;

ALTER TABLE treatment_plan_audit
    ADD COLUMN methodology_profile_id   UUID,
    ADD COLUMN methodology_strategy_key VARCHAR(100);

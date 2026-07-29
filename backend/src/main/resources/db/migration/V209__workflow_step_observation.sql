-- Issue #1354 (ADR-090 amendment, 2026-07-29): ADR-036 per-step telemetry becomes a durable
-- observation carried by the existing phase-event row instead of a gitignored JSONL file. No new
-- entity, table, or payload bag — the closed event shape gains only the ADR-036 facts that have no
-- existing owner, plus the `emitter` discriminator that keeps a routed-step cost observation from
-- being counted as a lifecycle/station attempt.
--
-- Forward-only: V142 (base table), V203 (Envers shadow), V204 (source_id), and V207 (station-result
-- axis) are not edited.

-- The emitter discriminator. Every pre-existing row was written by the ADR-061 lifecycle/station
-- path, so that is the honest backfill; a step observation is the only thing that writes
-- ADR036_STEP_JSONL. NOT NULL + DEFAULT on the live table because every future write states one
-- (the service defaults an unstated emitter to the ADR-061 value); the Envers shadow stays nullable
-- like every other audited column, since a delete revision carries no live state.
ALTER TABLE workflow_phase_event ADD COLUMN emitter VARCHAR(40);
ALTER TABLE workflow_phase_event_audit ADD COLUMN emitter VARCHAR(40);
UPDATE workflow_phase_event SET emitter = 'ADR061_WORKFLOW_TELEMETRY' WHERE emitter IS NULL;
UPDATE workflow_phase_event_audit SET emitter = 'ADR061_WORKFLOW_TELEMETRY' WHERE emitter IS NULL;
ALTER TABLE workflow_phase_event ALTER COLUMN emitter SET NOT NULL;
ALTER TABLE workflow_phase_event ALTER COLUMN emitter SET DEFAULT 'ADR061_WORKFLOW_TELEMETRY';

-- ADR-036 step facts. All nullable: they are absent on every lifecycle/station row and populated
-- only for an ADR036_STEP_JSONL emission. `tier` is the provider-neutral capability tier;
-- `expected_model` + `model_matches_expected` are the analysis-only tier/model consistency
-- assertion (never a gate). `step_alias` is the numbered SKILL step kept purely as a diagnostic
-- alias — identity lives in `phase` (stage id) and the catalogue-resolved `station_id`.
ALTER TABLE workflow_phase_event ADD COLUMN measurement_version VARCHAR(40);
ALTER TABLE workflow_phase_event ADD COLUMN step_alias VARCHAR(40);
ALTER TABLE workflow_phase_event ADD COLUMN tier VARCHAR(40);
ALTER TABLE workflow_phase_event ADD COLUMN model VARCHAR(200);
ALTER TABLE workflow_phase_event ADD COLUMN expected_model VARCHAR(200);
ALTER TABLE workflow_phase_event ADD COLUMN model_matches_expected BOOLEAN;
ALTER TABLE workflow_phase_event ADD COLUMN input_tokens BIGINT;
ALTER TABLE workflow_phase_event ADD COLUMN output_tokens BIGINT;

ALTER TABLE workflow_phase_event_audit ADD COLUMN measurement_version VARCHAR(40);
ALTER TABLE workflow_phase_event_audit ADD COLUMN step_alias VARCHAR(40);
ALTER TABLE workflow_phase_event_audit ADD COLUMN tier VARCHAR(40);
ALTER TABLE workflow_phase_event_audit ADD COLUMN model VARCHAR(200);
ALTER TABLE workflow_phase_event_audit ADD COLUMN expected_model VARCHAR(200);
ALTER TABLE workflow_phase_event_audit ADD COLUMN model_matches_expected BOOLEAN;
ALTER TABLE workflow_phase_event_audit ADD COLUMN input_tokens BIGINT;
ALTER TABLE workflow_phase_event_audit ADD COLUMN output_tokens BIGINT;

-- Per-step queries select the ADR-036 emitter from the existing run-scoped event surface, and the
-- lifecycle/graph consumers exclude it; both scan emitter within a run, so a partial index on the
-- step emitter keeps those residual predicates selective without widening the common lifecycle path.
CREATE INDEX idx_workflow_phase_event_emitter
    ON workflow_phase_event (run_id, emitter);

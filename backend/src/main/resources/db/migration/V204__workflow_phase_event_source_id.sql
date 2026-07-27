-- Issue #1435 (ADR-061 / ADR-090): deterministic identity for a phase event's logical fact.
--
-- Live /implement emission and gc_workflow_run_ingest observe the same phase attempt from two
-- different vantage points. Without a shared key the append-only table holds two rows for one
-- attempt and every per-phase count, first-pass-yield denominator, and rework figure is inflated.
-- Timestamp, provenance, and event type all differ between those vantage points, so the key is the
-- logical fact itself: phase, event type, and attempt ordinal, unique within the run.
--
-- Forward-only: V142 (the base table) and V203 (its Envers shadow) are not edited.

ALTER TABLE workflow_phase_event ADD COLUMN source_id VARCHAR(200);
ALTER TABLE workflow_phase_event_audit ADD COLUMN source_id VARCHAR(200);

-- Backfill the same expression the service derives, so pre-existing rows are addressable by the key
-- a later reconciliation of the same thread will compute.
UPDATE workflow_phase_event
   SET source_id = phase || ':' || event_type || ':' || COALESCE(cycle_index, 0);

-- Rows recorded before this migration were not deduplicated, so the derived key can collide within a
-- run. Keep the derived key on the earliest row of each group and suffix the rest: history is
-- preserved in full, and the unique index below can still be created.
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY run_id, phase, event_type, COALESCE(cycle_index, 0)
               ORDER BY occurred_at, id
           ) AS rn
      FROM workflow_phase_event
)
UPDATE workflow_phase_event e
   SET source_id = e.source_id || ':legacy-' || ranked.rn
  FROM ranked
 WHERE ranked.id = e.id
   AND ranked.rn > 1;

UPDATE workflow_phase_event_audit a
   SET source_id = e.source_id
  FROM workflow_phase_event e
 WHERE e.id = a.id;

-- Audit rows whose live row has since been deleted keep a revision-addressed fallback rather than a
-- NULL, so the shadow stays readable. The audit table carries no uniqueness constraint.
UPDATE workflow_phase_event_audit
   SET source_id = 'legacy-rev:' || rev
 WHERE source_id IS NULL;

ALTER TABLE workflow_phase_event ALTER COLUMN source_id SET NOT NULL;

CREATE UNIQUE INDEX idx_workflow_phase_event_source
    ON workflow_phase_event (run_id, source_id);

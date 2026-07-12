-- #1359: drop the Temporal operator-signal audit log.
--
-- V198 created this table to record operator-signal attempts (cancel / retryFrom / review-cap
-- disposition) against Temporal workflow executions. The Temporal orchestration lane is removed in
-- #1359, so the signals it audited no longer exist and nothing writes or reads this table. It is
-- dropped forward rather than by deleting V198, which is already applied.
--
-- No data-preservation step: the table only ever recorded signals against Temporal executions, and the
-- lane never executed a single activity in production (the worker registered workflow types but no
-- activity implementations), so there is nothing to migrate.
--
-- workflow_run (V142) is deliberately NOT touched. It is the ADR-061 run-economics projection fed by
-- the agent-driven /implement lane, and it is the foundation of the process-measurement work.
DROP INDEX IF EXISTS idx_operator_signal_audit_project_created;

DROP INDEX IF EXISTS idx_operator_signal_audit_workflow_created;

DROP TABLE IF EXISTS operator_signal_audit;

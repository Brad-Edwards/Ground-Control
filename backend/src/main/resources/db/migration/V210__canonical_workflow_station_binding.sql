-- Issue #1439 (ADR-090 amendment): bind safely resolvable ADR-061 phases to the canonical station
-- identity. V207 deliberately left every legacy verdict UNOBSERVED; this migration adds identity
-- only and never infers a result from phase, event type, outcome, order, or absence of failure.
--
-- This CASE is the historical v2-catalogue snapshot applied to rows that predate catalogue-driven
-- write-path resolution. Runtime resolution remains data-driven by the classpath catalogue.

UPDATE workflow_phase_event
SET station_id = CASE
    WHEN phase = 'preflight' THEN 'architecture_preflight'
    WHEN phase IN (
        'issue_branch_resolution',
        'architecture_preflight',
        'completion_gate',
        'spotbugs',
        'policy',
        'vale',
        'codex_review',
        'test_quality_review',
        'precommit',
        'git_publish',
        'ci',
        'sonarcloud'
    ) THEN phase
END
WHERE station_id IS NULL
  AND emitter = 'ADR061_WORKFLOW_TELEMETRY'
  AND phase IN (
      'preflight',
      'issue_branch_resolution',
      'architecture_preflight',
      'completion_gate',
      'spotbugs',
      'policy',
      'vale',
      'codex_review',
      'test_quality_review',
      'precommit',
      'git_publish',
      'ci',
      'sonarcloud'
  );

UPDATE workflow_phase_event_audit
SET station_id = CASE
    WHEN phase = 'preflight' THEN 'architecture_preflight'
    WHEN phase IN (
        'issue_branch_resolution',
        'architecture_preflight',
        'completion_gate',
        'spotbugs',
        'policy',
        'vale',
        'codex_review',
        'test_quality_review',
        'precommit',
        'git_publish',
        'ci',
        'sonarcloud'
    ) THEN phase
END
WHERE station_id IS NULL
  AND emitter = 'ADR061_WORKFLOW_TELEMETRY'
  AND phase IN (
      'preflight',
      'issue_branch_resolution',
      'architecture_preflight',
      'completion_gate',
      'spotbugs',
      'policy',
      'vale',
      'codex_review',
      'test_quality_review',
      'precommit',
      'git_publish',
      'ci',
      'sonarcloud'
  );

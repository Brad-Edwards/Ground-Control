# Workflow Payload Schemas

GC-O014 establishes this directory before the Temporal engine lands. New
workflow and activity input/output records must publish a versioned JSON Schema
here before implementation code consumes or emits the payload.

## Conventions

- One `gc.workflow.<name>.v<N>` schema file per activity (and one each for the
  workflow I/O and the operator signals). Each schema groups its record shapes
  under `$defs`; every record `$def` carries an `x-gc-record` tag naming the
  Java record class it governs.
- The `workflow-payload-contract` policy check
  (`tools/policy/checks.py::run_workflow_payload_contract_check`, ADR-082,
  owned by issue #1277) asserts a 1:1 mapping: every Java record under
  `infrastructure/temporal/implement/contract/` is named by exactly one
  `x-gc-record` tag here, and every `x-gc-record` tag names an existing record.
- Records carry IDs, enum values, bounded scalars, and redacted summaries only
  — never JPA entities, request/response DTOs, exceptions, secrets, prompts,
  completions, or raw issue/CI/review text (ADR-028 redaction rule).
- `WorkflowContractConformanceTest` serializes a representative instance of
  each record with Jackson and validates it against its `$def` (Draft 2020-12),
  the enforcing test named by each schema's `x-ground-control-invariants`.

The pre-existing `workflow-run-record.v1` schema is the ADR-061 telemetry
correlation/projection record, not an activity payload; the payload-contract
check excludes it.

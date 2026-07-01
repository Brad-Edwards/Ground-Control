# Research methodology requirements preflight

Requirement: `GC-RSCH-F005` - select review methodology from a catalog and
explicitly justify rejected alternatives.

Issue context: #1005, with related source-catalog/source-coverage requirements
`GC-RSCH-F006` and `GC-RSCH-F007`.

## Architectural read

No new runtime architecture is needed before implementation. The work must be a
bounded extension of the existing research run surfaces:

- ADR-055 owns the skill-side method-selection and source-reading discipline.
- ADR-064 owns `ResearchRun`, `METHODOLOGY_SELECTION`,
  `METHODOLOGY_REQUIREMENTS`, artifact manifests, stage legality, gates, and
  checkpoint/resume.
- ADR-065 owns run observability, including source/access summaries and
  actionable bounded errors.
- ADR-069 owns provenance nodes/edges, including methodology-source derivation
  tracing, but provenance is not the stage gate.
- ADR-071 owns provider-neutral source identity and keeps Zotero/provider/local
  files as adapters or references.
- ADR-073 owns research extension boundaries and says methods are versioned
  catalog/profile data, not executable plugin code.
- ADR-075 owns factuality and claim grounding, including the rule that provider
  metadata alone is not scientific support.
- ADR-076 owns scientific humility exposure for failed searches, access gaps,
  missing evidence, method limits, and non-claims.
- ADR-077 owns method profile/source-completeness versioning and regression-test
  expectations for issue #1005.

## Guardrails

Methodology requirements are run-local protocol obligations, not Ground Control
`Requirement` rows. Do not create UIDs, requirement statuses, requirement
traceability, or requirement relations for extracted methodology obligations.

The selected method key/profile version belongs on run-scoped methodology state
so the required-source set is historical. Rejected alternatives belong in the
existing rationale ledger as `METHODOLOGY_CHOICE` entries with bounded
source- or policy-backed rationale. Later catalog edits must not make an
existing run falsely complete or falsely incomplete.

The backend completion gate for `METHODOLOGY_REQUIREMENTS` is source coverage,
not artifact content parsing. A phase-1 artifact can be recorded complete only
when the selected method profile/catalog version has all required primary
methodology sources recorded as read/complete for that run. Optional sources may
be visible but must not block unless the selected method policy marks them
mandatory.

The distinction between phase 1 and phase 2 is semantic and must be preserved in
the data shape:

- phase 1 may record method key/version, source reference, source section or
  locator, requirement statement, limitation, and source-grounded rationale;
- phase 2 owns selected databases, query strings, domain definitions,
  inclusion/exclusion logic, charting fields, synthesis dimensions, source-set
  caps, and other paper/domain answers.

Provenance and source identity may reference the same methodology sources, but
they do not replace source coverage. Provenance answers "from what"; source
identity answers "which source"; source coverage answers "was this required
methodology source obtained and read for this run?"

## Cross-cutting concerns

REST writes stay under `/api/v1/research-runs/**`, resolve one project/run
through the existing services, and conceal cross-project misses as `404`.
Controllers bind DTOs and delegate; they do not decide source completeness.

Validation is layered: Bean Validation/Jackson at REST DTOs, Zod at MCP mirrors,
service-owned semantic checks for selected method version, source-reference
membership, required-source completeness, same-run references, idempotency, and
bounded text.

Errors use `GroundControlException` subclasses through
`GlobalExceptionHandler` and `ErrorResponse`. Source-completeness failures should
surface as stable, actionable run errors or validation/conflict errors without
raw provider payloads, PDF text, local paths, stack traces, bearer tokens, or
Zotero secrets.

Actor provenance comes from `ActorFilter` / `ActorHolder` and Envers metadata.
Do not accept request-body actors for source attempts, reads, artifact
completion, or gate decisions.

Logging uses SLF4J with low-cardinality fields: project, run id, method
key/version, source reference id, required/optional status, source state, stage,
artifact type, idempotency/source-action id, and stable error code. Do not log
source text, prompts, manuscripts, provider payloads, secrets, or private paths.

This slice should introduce no subprocess, shell-out, provider call, GitHub
write, citation call, or token-in-argv path inside controllers, domain services,
MCP handlers, or frontend code. Provider/citation/Zotero/local-file effects stay
at existing adapter boundaries and re-enter as structured research service
commands.

## Gotchas

Do not reuse the GRC `MethodologyProfile` aggregate for research methods; it is
risk-analysis vocabulary, not literature-review method policy.

Do not infer a source is read from Zotero membership, DOI resolution, a file
locator, a provenance node, a local `requirements.md`, or a skill transcript.

Do not store catalog prose summaries that let a future agent substitute catalog
text for primary-source reading.

Do not make MCP handlers, frontend conditionals, or controller branches a second
source-completeness validator. The research service owns the gate.

Do not broaden `gc_query` into a write tunnel or workspace-file reader. Curated
writes mirror REST; ad hoc reads stay GET-only and allowlisted.

## Non-goals

No new generic workflow engine, methodology engine, source store, document
store, extraction validator, approval engine, dynamic plugin execution, error
envelope, auth model, logging stack, or policy runner.

The "No backend catalog loader" non-goal stated for this first slice is
superseded by ADR-078: the methodology catalog became backend-owned,
validated-on-load reference data (the single source of truth the selection gate
derives the required-source set from), with the skill file kept in sync by a
policy drift check. This is bounded reference data, not the generic methodology
engine the rest of this non-goal still rules out.

No backend guarantee, in this slice, that a free-form local `requirements.md`
contains no domain answers. The enforceable backend gate is required-source
coverage and artifact/stage legality. If machine validation of the artifact body
becomes required, add an explicit structured artifact parser/validator boundary
instead of hiding that logic in controllers, MCP tools, frontend code, or skill
prose.

# ADR-068: Research Final-Output Accountability Disclosure

## Status

Accepted

## Date

2026-06-28

## Context

`GC-RSCH-N013` requires final research outputs to disclose AI-generated parts,
human approvals, and unresolved uncertainty. The repository already has the
necessary neighboring concepts:

- ADR-055 owns the skill-side research workflow and citation MCP.
- ADR-056 owns project-level research intake defaults.
- ADR-064 owns `ResearchRun`, stage gates, durable gate decisions, artifact
  manifests, checkpoint/resume, and the rule that `decisions.md` is not the
  authority.
- ADR-065 owns the bounded run observability snapshot.
- ADR-066 owns run-scoped gate decision logs and research review comments.
- ADR-067 owns the run-scoped explainability rationale ledger.

The remaining design risk is concept drift at the final-output boundary. An
implementation could satisfy the requirement only with manuscript prose,
workspace-local `decisions.md`, frontend copy, a GitHub issue comment, a prompt
appendix, or a model transcript. Those are useful renderings or evidence
sources, but none is the Ground Control authority for whether a final output
disclosed the required accountability facts.

Likely failure modes:

- treating autonomous gate acceptance as a human approval;
- letting a caller supply a "human approver" identity instead of using the
  authenticated server-side actor context;
- duplicating the run-gate, artifact, review-comment, or rationale-ledger schema
  in a separate approval/export workflow;
- making final-output completion depend on workspace file contents, skill
  transcripts, issue comments, or model-generated disclosure text;
- storing prompts, manuscript prose, source PDFs, charting rows, private
  workspace paths, or provider payloads in an accountability record;
- letting a final artifact be replaced without invalidating or superseding the
  disclosure attached to the replaced artifact.

## Decision

### 1. Accountability disclosure is run-scoped final-output metadata

Represent final-output accountability as a bounded, run-scoped disclosure record
under the research domain. It is product state, not a generic approval engine,
not a workflow telemetry event, not a requirements `QualityGate`, not a review
comment thread, not a rationale ledger, and not a manuscript section that the
backend treats as authoritative.

The disclosure belongs to one `ResearchRun` and one active final output artifact
attempt. The initial final output is the `MANUSCRIPT` artifact produced by
`PROSE_DRAFTING`; future output formats can reuse the same disclosure boundary
by pointing at a different final artifact type or output artifact identifier.

The disclosure record is metadata only. It may identify sections, artifact
locators, hashes, model/provider labels, recommendation identifiers, gate
decisions, review-comment statuses, rationale-ledger references, uncertainty
categories, bounded summaries, actors, and timestamps. It must not store raw
prompts, raw completions, manuscript prose, full search results, charting rows,
PDF text, bearer tokens, Zotero secrets, provider payloads, or private absolute
workspace paths.

### 2. Human approvals are derived from gate decisions

Human approvals in the final disclosure come from resolved `ResearchRunGate`
decision rows whose behavior and outcome represent a human decision. The
authenticated actor captured by `ActorFilter` / `ActorHolder` is the provenance
source. Clients do not submit approval actors.

Autonomous decisions remain visible, but they are labeled as autonomous or
agent/default decisions and must not be counted as human approvals. A disclosure
that collapses `APPROVED` and `AUTO_ACCEPTED` into the same "approved by human"
claim violates this ADR.

### 3. AI-generated parts and uncertainty are structured, bounded claims

AI-generated parts are disclosed as structured bounded entries tied to the final
artifact: for example section keys, artifact-relative locators, contribution
categories, model/provider labels when safely known, and a concise summary of
the generation role. The implementation is not required to infer these entries
from manuscript text or model transcripts; the authoritative record is the
accepted structured disclosure data.

Unresolved uncertainty is also a structured bounded entry, not an error log and
not a free-form dumping ground. It may reference access gaps, method limits,
conflicting evidence, unsupported claims that were removed or narrowed,
unresolved review comments, gate rationale, or rationale-ledger entries. It must
distinguish:

- scientific or evidentiary uncertainty about the paper's claims;
- access gaps and source-set limitations;
- workflow/runtime errors already represented as bounded run failure
  observations;
- review comments that are still unresolved.

Missing uncertainty is not the same as zero uncertainty. If the final output has
no unresolved uncertainty, the disclosure should carry an explicit empty/none
state with actor and timestamp rather than relying on an omitted field.

### 4. Finalization must depend on the disclosure authority

A research run must not be marked final or export a final accountable output
unless the active final artifact has a matching accountability disclosure. The
same service boundary that validates final artifact presence must validate that
the disclosure is present, belongs to the same project/run/artifact attempt, is
not stale relative to the active artifact, and satisfies the three required
families: AI-generated parts, human approvals, and unresolved uncertainty.

If the final artifact is superseded, the disclosure tied to the old artifact is
stale. Rework must either supersede the disclosure or require a fresh disclosure
before completion/export.

### 5. API, MCP, and renderers stay thin

REST surfaces live under `/api/v1/**`, route through controllers to the research
service, and return DTOs shaped from domain records. Controllers, MCP handlers,
and frontend components must not re-derive approval state, artifact freshness,
review-comment status, rationale coverage, or disclosure completeness.

If MCP writes are added, extend the existing `gc_research_run` passthrough style
with Zod-checked structured actions that call the REST API. Do not add MCP-local
approval logic, direct database reads, filesystem scans, privileged GitHub
writes, citation-provider calls, or subprocess execution to satisfy N013.

Manuscript/export rendering may include a disclosure section generated from the
authoritative record, but rendered prose is a view. Editing the prose alone does
not update the durable disclosure state.

### 6. Cross-cutting layers stay shared

- **Security and authorization:** routes stay inside the ADR-026 bearer/session
  chains and `ApiPathMatrix`. Project-scoped reads and writes resolve the
  project through `ProjectService`; cross-project references are concealed as
  `404`.
- **Validation:** request DTOs use Bean Validation and Jackson enum parsing.
  Services own same-project checks, run status/stage checks, final-artifact
  freshness, disclosure completeness, bounded-summary validation, duplicate
  section/locator handling, and stale-disclosure rejection.
- **Errors:** use `DomainValidationException`, `ConflictException`, and
  `NotFoundException` through `GlobalExceptionHandler` and `ErrorResponse`. Do
  not create a research-specific error envelope.
- **Audit and actor provenance:** mutation actor comes from `ActorFilter` /
  `ActorHolder`; Envers captures revisions for audited disclosure records.
  Clients do not supply audit actors.
- **Logging:** use SLF4J with low-cardinality fields: project, run ID, final
  artifact ID/type, disclosure ID, gate point, decision outcome, uncertainty
  category, and status. Never log prompts, manuscripts, raw source rows,
  provider payloads, bearer tokens, Zotero secrets, PDFs, or absolute private
  workspace paths.
- **Configuration and OS/runtime exposure:** this disclosure surface introduces
  no new secrets, subprocesses, shell-outs, external network calls, token-in-argv
  path, or provider-side effect. Future provider/model provenance import must
  use validated configuration and a separate adapter boundary.
- **Persistence and migrations:** new durable records follow the existing
  audited aggregate pattern: `BaseEntity`, Flyway migration plus audit shadow,
  project/run-scoped indexes, and migration smoke coverage.
- **Testing and policy:** controller surfaces need `@WebMvcTest` slices; service
  behavior needs focused tests for stale artifacts, human vs autonomous
  approvals, required empty/none uncertainty, and cross-project concealment;
  API-visible enum mirrors follow ADR-034; repo completion still runs
  `make policy`.

## Consequences

### Positive

- `GC-RSCH-N013` gets an authoritative product-state boundary rather than a
  prose-only or workflow-comment convention.
- Human approvals reuse `ResearchRunGate` and authenticated actor provenance
  instead of adding a parallel approval model.
- Unresolved review comments and rationale-ledger entries can inform disclosure
  without becoming hidden stage-transition gates.
- Final outputs can render a consistent disclosure from one durable source.

### Negative

- Completion/export now has one more prerequisite: a disclosure that is current
  for the active final artifact.
- The implementation must carry a small amount of structured metadata for AI
  contribution and uncertainty instead of relying on manuscript text alone.

### Risks

- If the disclosure stores raw research content, it becomes a leakage surface
  for unpublished manuscripts, private libraries, PDFs, or provider payloads.
- If the service treats omitted uncertainty as none, final outputs can falsely
  imply a clean evidence base.
- If artifact supersession does not stale the disclosure, a final output can
  publish accountability claims for an older manuscript attempt.
- If MCP or UI code re-derives approvals, autonomous default decisions can be
  mislabeled as human approvals.

## Non-Goals

- No implementation of controllers, DTOs, migrations, services, MCP tools,
  frontend views, or manuscript renderers in this ADR.
- No generic approval engine, generic AI provenance platform, workflow engine,
  or replacement of `ResearchRunGate`.
- No automatic classifier that detects which manuscript spans are AI-generated.
- No full-text, prompt, completion, PDF, charting-row, or source-store decision.
- No new authentication model, actor override mechanism, error envelope,
  logging stack, secret-handling path, or policy runner.

## Related Requirements

- `GC-RSCH-N013` - human accountability.
- `GC-RSCH-N012` - explainability.
- `GC-RSCH-F034` - human review comments and resolution tracking.
- `GC-RSCH-R003` - autonomous/copilot modes and human gates.
- `GC-RSCH-F036` - checkpoint/resume after material actions.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-033 - Authenticated Audit Actor Provenance.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-035 - MCP Tool Catalog Curation.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-056 - Research Project Type and Intake Metadata.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-065 - Research Run Observability Snapshot.
- ADR-066 - Research Gate Decision Log and Review Comments.
- ADR-067 - Research Explainability Rationale Ledger.

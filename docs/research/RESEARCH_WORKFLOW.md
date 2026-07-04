# Research workflow

Ground Control's research project type ships a five-phase literature-review pipeline implemented as Claude skills, a methodology catalog, and a deterministic citation MCP. The pipeline answers one question for a given research paper:

> What are the formal requirements a literature-review plan must meet to satisfy the appropriate methodology, and how do we execute that plan, build the paper's argument, and draft it without fabricating citations or asserting findings the evidence base does not carry?

See ADR-055 for the skill/citation architecture and asset disposition. Durable
run lifecycle state, checkpoint artifacts, and human-gate policy are governed by
ADR-064. The user-facing run status/observability snapshot is governed by
ADR-065. Gate decision logs and review comments are governed by ADR-066;
explainability is governed by ADR-067; final-output accountability is governed
by ADR-068. Research factuality and claim grounding are governed by ADR-075.
Scientific humility exposure for negative results, failed searches, access gaps,
missing evidence, method limits, and non-claims is governed by ADR-076.
Versioning and regression expectations for prompts, method profiles, schemas,
and workflow policies are governed by ADR-077. The structured phase-1
methodology requirements contract artifact is governed by ADR-080. The
structured phase-2 protocol-plan artifact and method-specific output shapes are
governed by ADR-083. High-risk generated code execution, browser activity,
lab/hardware actions, external writes, sandbox policy, egress policy, and
prompt-injection handling are governed by ADR-086.

## Phases

| Phase | Skill | Output | Chaining |
|---|---|---|---|
| 1 - methodology selection + requirements extraction | `lit-review` | `requirements.md` - chosen method, primary sources read, formal requirements extracted | auto-chains into phase 2 |
| 2 - domain-aware planning | `lit-review-plan` | `lit-review-plan.md` - phase-1 requirements filled with domain content for the specific paper | auto-chains into phase 3 |
| 3 - search execution | `lit-review-search` | Evidence base: `charting-data.csv`, `coding-scheme.md`, `evidence-matrix.md`, `synthesis.md`, `search-log.md` | does NOT auto-chain - evidence base is a user-review checkpoint |
| 4 - argument architecture | `lit-review-argument` | `paper-outline.md`, validated `argument-map.argdown` (premises grounded in evidence, objections modelled) | does NOT auto-chain - argument architecture is a user-review checkpoint |
| 5 - drafting | `lit-review-draft` | `manuscript.tex` (IEEE format) + `references.bib` (generated from Zotero collection) + `manuscript.md` rendering | final phase |

The user invokes `lit-review` once and phases 1 → 2 → 3 run end-to-end with gates surfaced mid-flow. After reviewing the evidence base, the user invokes `lit-review-argument`; after reviewing the argument architecture, the user invokes `lit-review-draft`.

## Disciplines

Each phase enforces specific disciplines against observed failure modes. The full failure-mode catalog lives in the individual SKILL.md files; the highest-leverage ones:

- **Citation hallucination → deterministic citation MCP only.** Every citation must come from `cite_resolve`, `cite_search`, `cite_forward`, or a Zotero record the agent opened - never from training memory. The citation MCP exists to make this enforceable.
- **Two-state source rule (phase 3).** A source is in exactly one of two states: (a) fully in the review - resolved, stored in the Zotero collection, full text read, charted; or (b) access gap - resolved, stored, full text not obtainable, not charted. There is no "charted from the abstract" or "charted from memory" state.
- **Procedural-invention guard (phase 1).** Phase 1 emits *requirements the plan must satisfy*, not the answers. If the methodology source does not specify a particular database / date range / coding category, neither does the phase-1 output. Domain content is phase 2's job.
- **Argument grounding (phase 4).** Every Argdown premise carries an `{evidence: ...}` tag pointing at a specific section of the evidence base - or its proposition is itself the conclusion of another reconstructed argument. `validate-argument-map.sh` mechanically flags ungrounded premises, unreconstructed support arguments, unanswered objections, and circular support. When `--logreco` is passed and every PCS member carries `{formalization: ...}` metadata, the wrapper additionally runs `argdown-feedback`'s LogReco family (NLTK FOL + Z3) for deductive-validity checking.
- **Manuscript-not-memo guard (phase 5).** The manuscript reads cold for a reviewer who has never heard of Ground Control. No phase vocabulary, no internal-artifact names, no "the evidence matrix shows" gestures. Every load-bearing empirical claim is demonstrated on the page - inline citation, numbered table, or quoted example - not asserted.
- **Voice contract (phase 5).** `skills/lit-review-draft/writing-style.md` is a voice profile plus a model-tell blocklist. The style pass runs the blocklist literally against the draft; zero hits is the bar.

## Artifacts on disk

Per-paper artifacts live in the user's chosen workspace, not in the Ground Control repo. The typical layout, after a full run:

```
workspace/
  program/
    <paper_id>.md            # the paper's stanza (primary claim, RQs, non-claims, venue posture)
  requirements.md            # phase 1
  lit-review-plan.md         # phase 2
  search-log.md              # phase 3
  charting-data.csv          # phase 3
  coding-scheme.md           # phase 3
  evidence-matrix.md         # phase 3
  synthesis.md               # phase 3
  paper-outline.md           # phase 4
  argument-map.argdown       # phase 4
  manuscript.tex             # phase 5
  references.bib             # phase 5 (generated from Zotero)
  manuscript.md              # phase 5 (markdown rendering)
  decisions.md               # local mirror/export; persisted gate state is authoritative
  self-review.md             # extended across all phases
```

## Components in this repo

| Surface | Path |
|---|---|
| Phase skills | `skills/lit-review/`, `skills/lit-review-plan/`, `skills/lit-review-search/`, `skills/lit-review-argument/`, `skills/lit-review-draft/` |
| Methodology catalog | `skills/lit-review/methodology/catalog.yaml` |
| Argdown validation tooling | `skills/lit-review-argument/{validate-argument-map.sh, run_verifier.py, handlers/, requirements.txt, tests/}` (Python; `argdown-feedback` pinned in `requirements.txt`) |
| Voice contract | `skills/lit-review-draft/writing-style.md` |
| Citation MCP | `mcp/citation/` (Python; see `mcp/citation/README.md` for bootstrap) |
| MCP registration | `.mcp.json` → `citation` server (provides `mcp__citation__*` tools) |
| OSS landscape assessment | `docs/knowledge/research-workflow/auto-research-requirements-and-oss-assessment.md` |
| Architectural decision | `architecture/adrs/055-research-workflow-skills-and-citation-mcp.md` |

## Methodology catalog

The catalog at `skills/lit-review/methodology/catalog.yaml` is a lookup, not a paraphrase: method key → primary methodology source Zotero keys + titles + PDF availability. The phase-1 skill reads the actual source PDFs to ground its method choice; the catalog only tells it *which* sources to read. ADR-077 makes the product gate explicit: every required source for the selected method profile must have accepted obtained-and-read coverage before the methodology-requirements artifact can complete.

Methods shipped: `scoping`, `systematic`, `mapping`, `critical`, `narrative_conceptual`, `targeted_related_work`, `taxonomy_development`.

Adding a method: append a new entry with the primary methodology source Zotero keys. Do not add prose summaries - the phase-1 skill reads the sources directly.

## F006 backend contract: methodology source coverage gate

The backend enforces the source-coverage invariant via `ResearchRunService`. Selecting a methodology (`POST /api/v1/research-runs/{id}/methodology/selection`) takes only a `methodKey`: the required-source set is **derived from the backend-owned methodology catalog** (`backend/src/main/resources/research/methodology-catalog.yaml`, the source of truth that the skill catalog mirrors under a `make policy` drift check - ADR-078), not supplied by the caller. The resolved method profile's required primary sources are snapshotted as immutable `required: true` rows at selection; an unknown `methodKey` is rejected with `research_run_methodology_unknown_method`. Before a `METHODOLOGY_REQUIREMENTS` artifact can be recorded, the run must have an active methodology selection and every source marked `required: true` must be in `READ` state (tracked via `POST /api/v1/research-runs/{id}/methodology/sources` and `PATCH /api/v1/research-runs/{id}/methodology/sources/{sourceId}`). A required source in `BLOCKED` state raises a `409 Conflict` with error code `research_run_methodology_source_blocked`; any required source not yet `READ` raises `422 Unprocessable Entity` with code `research_run_methodology_sources_incomplete`. Optional sources never block the gate. Once a `METHODOLOGY_REQUIREMENTS` artifact has been recorded, the methodology is locked: reselecting a different method (which would re-snapshot a fresh, unread required set and silently invalidate the accepted artifact's coverage) is rejected with `409 Conflict` code `research_run_methodology_locked_after_requirements`. The full catalog is readable at `GET /api/v1/research-runs/methodology/catalog`. The MCP surface exposes these operations through the `gc_research_run` tool actions `list_methodology_catalog`, `select_methodology`, `get_methodology_selection`, `record_methodology_source`, `update_methodology_source_state`, and `list_methodology_sources`.

## F007 artifact boundary: methodology requirements contract

ADR-080 defines the phase-1 artifact as a structured, run-scoped methodology requirements contract tied to the active methodology selection and the `METHODOLOGY_REQUIREMENTS` artifact manifest attempt. The contract records source-linked methodology obligations, method limits, non-claims, and open protocol-planning questions. It is not a Ground Control `Requirement`, not a raw markdown body stored on `ResearchRunArtifact`, and not a duplicate rationale/provenance schema.

The chosen method remains the active methodology selection from ADR-078. Rejected alternatives remain methodology-choice rationale entries. Every extracted requirement, limit, or non-claim must link back to methodology source coverage rows from the same active selection. Phase 1 has no first-class fields for protocol answers such as databases, query strings, date ranges, charting categories, synthesis dimensions, or source-set caps; phase 2 fills or defers those answers against the contract.

The contract is recorded once per `METHODOLOGY_REQUIREMENTS` artifact attempt (a rework records a new artifact attempt, then a new contract) via `POST /api/v1/research-runs/{id}/methodology/requirements-contract` and read via `GET /api/v1/research-runs/{id}/methodology/requirements-contract`. Each entry carries a closed `kind` (`REQUIREMENT`, `METHOD_LIMIT`, `NON_CLAIM`, `OPEN_PROTOCOL_QUESTION`) and a stable `entryKey`. `REQUIREMENT`, `METHOD_LIMIT`, and `NON_CLAIM` entries must link at least one methodology source of the active selection that is in `READ` state (a claim with no `READ` source link is rejected - no model memory as scientific evidence, GC-RSCH-R002); an `OPEN_PROTOCOL_QUESTION` may instead reference another entry by key. Recording requires an active `METHODOLOGY_REQUIREMENTS` artifact and complete required-source coverage; a second contract for the same artifact attempt is rejected with `409 Conflict` code `research_run_methodology_contract_exists`. The MCP surface exposes these through the `gc_research_run` tool actions `record_methodology_requirements_contract` and `get_methodology_requirements_contract`.

## F008/F009 artifact boundary: protocol plan and method-specific outputs

ADR-083 defines the phase-2 protocol plan as structured, run-scoped content behind the `PROTOCOL_PLAN` artifact manifest. It consumes the active ADR-080 methodology requirements contract by contract id and artifact attempt, then gives every `REQUIREMENT` and `OPEN_PROTOCOL_QUESTION` exactly one bounded coverage disposition: filled, resolved by durable user decision, explicitly deferred as non-blocking, not applicable with rationale, or blocking decision required. A protocol plan with missing coverage or any unresolved blocking decision cannot become the active `PROTOCOL_PLAN`; source search must recheck that active complete plan before executing.

The protocol plan carries phase-1 `METHOD_LIMIT` and `NON_CLAIM` entries forward
as scientific-humility constraints and records known missing evidence, access
gaps, deferrals, and user-dependent decisions as bounded product facts rather
than hiding them in prose.

Method-specific output shape is selected by method key/profile version plus a protocol schema version, not by adding one controller, table, or MCP action per method. Scoping, systematic, mapping, critical/integrative, targeted related-work, and taxonomy-development plans keep distinct section/source-role/output obligations. Taxonomy development keeps taxonomy-instance corpus, background/framing, methodology, and validation/evaluation roles separate so background sources do not silently support recurrence, coverage, exhaustiveness, or taxonomy-validity claims.

The plan is recorded once per `PROTOCOL_PLAN` artifact attempt via `POST /api/v1/research-runs/{id}/protocol-plan` and read via `GET /api/v1/research-runs/{id}/protocol-plan`; a second plan for the same artifact attempt is rejected with `409 Conflict` code `research_run_protocol_plan_exists`. Recording requires an ACTIVE `PROTOCOL_PLAN` artifact and an ACTIVE `METHODOLOGY_REQUIREMENTS` contract to answer (`research_run_protocol_plan_artifact_missing` / `research_run_protocol_plan_contract_missing` otherwise). Every `REQUIREMENT`/`OPEN_PROTOCOL_QUESTION` contract entry must receive exactly one coverage - missing entries reject with `research_run_protocol_plan_coverage_incomplete`, and unknown keys, `METHOD_LIMIT`/`NON_CLAIM` keys, or duplicate coverage are also rejected - and each disposition's own required fields must be present (`FILLED` needs `answerProvenance` + `answerSummary`; `DEFERRED_NON_BLOCKING` needs `deferredToStage` + `rationale`; `NOT_APPLICABLE_WITH_RATIONALE` and `BLOCKING_DECISION_REQUIRED` need `rationale`; `RESOLVED_BY_USER_DECISION` needs `decisionReference` or `rationale`). Sections must cover every kind the selected method profile requires, section keys must be unique, and `sourceRole` may only appear on a `SOURCE_ROLES` section of the `taxonomy_development` method. The `SOURCE_SEARCH` durable gate independently rechecks the active plan at stage-advance time (`research_run_protocol_plan_blocking` if no plan has been recorded or any coverage is still `BLOCKING_DECISION_REQUIRED`), so a caller cannot bypass the check by invoking a lower-level action directly. The MCP surface exposes these through the `gc_research_run` tool actions `record_protocol_plan` and `get_protocol_plan`.

## High-risk operation authorization, egress policy, and prompt injection

ADR-086 governs the security/privacy control plane for research runs. It is an
authorization + policy layer; executors and the sandbox runtime are out of scope
(a future executor must consult these records before acting).

**Run policy snapshot.** At run start the backend snapshots the run-driving
policy from `ResearchIntake` onto the `ResearchRun`: the `allowedTools` inventory
(which tools may be *requested*, not authorization to act), a structured
default-deny `egressPolicy`, and the free-text `privacyConstraints` (preserved as
operator context, display-only, never an enforcement input). Later intake edits
never re-authorize an active or completed run.

**Structured egress policy.** `egressPolicy` is a list of allowances, each binding
a data class (`PUBLIC`/`INTERNAL`/`CONFIDENTIAL`/`RESTRICTED`), a destination class
(`LOCAL`, `AI_PROVIDER`, `CITATION_PROVIDER`, `VERSION_CONTROL`, `REFERENCE_MANAGER`,
`BROWSER_TARGET`, `EXTERNAL_STORAGE`, `LAB_HARDWARE`, `OTHER_EXTERNAL`) and the
maximum data form allowed (`NONE` < `DERIVED_METADATA` < `SUMMARY` < `RAW_CONTENT`).
Keeping material `LOCAL` or moving only `NONE` data is always permitted; every other
`(dataClass, destinationClass, form)` is permitted only when a matching allowance
covers it at least at the requested form. Absence of an allow rule is deny: private
manuscripts, PDFs, reviewer notes, and credentials stay local unless the policy
explicitly permits their disclosure (GC-RSCH-N006). Research artifacts carry an
optional `dataClass` that feeds this check.

**Authorization records.** Generated-code execution, browser activity,
lab/hardware actions, and external writes each need a durable, run-scoped
`ResearchRunOperationAuthorization`. A request must bind a concrete effect
(ADR-086 §1): the adapter/tool id, sandbox profile, bounded action summary, and a
retry-safe `sourceActionId` are required so an executor consuming an approval can
prove which adapter/action/sandbox was authorized (and a tool-less request can
never sidestep the allowed-tool inventory check). A request lands `PROPOSED`; an
admin/operator decision moves it to `APPROVED` only when the run's egress policy
permits the operation's `(dataClass, destinationClass, requestedForm)` tuple
(default-deny otherwise) and an authenticated approver is present. An
`AUTONOMOUS` run may *propose* but never *approve* its own operations
(GC-RSCH-R005). One-time-use approvals are spent to `CONSUMED`; expired approvals
are rejected. The proposing/deciding actor is server-populated, never a request
field. REST lives under `/api/v1/research-runs/{runId}/operation-authorizations/**`
(the decision and consume routes are admin-gated) and the
`gc_research_operation_authorization` MCP tool mirrors it.

**Prompt injection is a data-flow rule (GC-RSCH-N014).** Retrieved PDFs, web
pages, metadata, and provider payloads are untrusted input. Every policy and
authorization field is a closed enum or a bounded, service-validated value built
only from structured accepted records, never free text lifted from retrieved
content. Untrusted content therefore cannot set allowed tools, egress policy,
sandbox profile, or approval state; prompt-injection handling is enforced by the
typed control plane, not by skill prose alone.

## Citation MCP

See `mcp/citation/README.md` for the bootstrap (`python -m venv`, `pip install -e`, optional `zotero/translation-server` Docker), the tool inventory, and the environment variables.

The MCP is registered in `.mcp.json` as `citation`. Tools surface to the skills as `mcp__citation__cite_resolve`, `mcp__citation__cite_search`, etc.

## How this earned its place

Every discipline in the skills addresses a failure mode that has been observed in real paper drafting - citation hallucination (88 partially hallucinated references in one round), domain leakage into methodology outputs, invented procedural detail, imported framing without provenance, synthesis claims unsupported by the charted corpus, manuscripts that read as workflow memos. The OSS landscape assessment under `docs/knowledge/research-workflow/` records the build-vs-adopt analysis that established no single existing tool provides the combined discipline these skills enforce.

If a future failure mode appears that genuinely cannot be addressed by skill instructions and source reading, the smallest possible support for that specific failure mode goes in - and the observed failure it addresses is written down so it can be defended later. Speculative additions against theoretical failure modes get cut.

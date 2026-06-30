# ADR-074: Research Factuality and Claim Grounding Boundary

## Status

Accepted

## Date

2026-06-29

## Context

`GC-RSCH-N001` requires every scientific claim to be traceable to a source,
charted cell, computation, or explicit inference. The linked research issues
span the whole evidence path: methodology source coverage (#1005), source
identity and bibliographic resolution (#1010), full-text/access gaps (#1014),
charting and evidence spans (#1016), evidence matrix and numerical synthesis
(#1018), method-limit and overclaim checks (#1020), argument claim ledger
(#1022), evidence-constrained drafting (#1023), and citation/prose grounding
validation (#1024).

Ground Control already has the durable research surfaces this NFR must compose:

- ADR-055 owns the skill-side workflow, deterministic citation MCP, methodology
  catalog, two-state source rule, charting/evidence matrix artifacts, Argdown
  validation, and drafting discipline.
- ADR-064 owns `ResearchRun`, lifecycle stages, artifact manifests, gates,
  checkpoint/resume, and the rule that persisted run state is authoritative over
  workspace files.
- ADR-067 owns rationale entries for methodology choices, exclusions, charted
  values, synthesis claims, and writing claims.
- ADR-068 owns final-output accountability disclosures and stale-final-artifact
  handling.
- ADR-069 owns the run-scoped provenance ledger from user goal to final prose.
- ADR-071 owns provider-neutral source identity, Zotero/provider boundaries,
  local/offline source references, and adapter normalization.
- ADR-072 owns the REST/MCP surface and rejects adapter-side lifecycle logic.

Without a focused factuality boundary, likely failure modes are:

- creating a second "claim database" that duplicates provenance, rationale,
  source records, artifact manifests, or final disclosures;
- treating citations, provenance edges, or rationale summaries as interchangeable
  proof of factual support;
- accepting abstract-only or unresolved-provider source metadata as support for
  a full-text claim;
- storing raw source excerpts, charting rows, manuscript prose, prompts,
  completions, provider payloads, private paths, or secrets in a broad claim API;
- letting a frontend, MCP handler, drafting adapter, or skill prompt decide
  whether a claim is grounded;
- using `TraceabilityLink` or GRC `EvidenceArtifact` for run-internal scholarly
  claim grounding;
- conflating scientific/evidentiary uncertainty with runtime errors, gate
  approval, or final accountability disclosure.

## Decision

### 1. Factuality is a service-owned grounding invariant

Research factuality is an invariant over accepted research state, not a separate
content store and not a prose-only convention. Backend research services remain
the authority for whether a synthesis, argument, or prose artifact is
claim-grounded enough to be accepted as a research output, marked complete, or
exported as an accountable final output.

A claim-grounding check composes existing persisted state:

- `ResearchRunArtifact` identifies the active artifact attempt and stage.
- `ResearchProvenanceNode` identifies bounded claim/source/cell/prose referents.
- `ResearchProvenanceEdge` records upstream-to-downstream derivation/support or
  citation paths.
- `ResearchRunRationaleEntry` records explicit inference, limits, and "why this
  claim" rationale where the claim is not directly source- or cell-backed.
- Source identity/disposition records from ADR-071, when implemented, normalize
  external source identifiers and full-text/access-gap state.
- `ResearchRunDisclosure` records final-output uncertainty/accountability; it
  does not itself prove claim support.

The invariant is evaluated from those records. It must not read workspace files,
parse manuscripts, scrape Zotero, call providers, shell out, consult workflow
telemetry, or infer truth from skill transcripts at read/validation time.

### 2. Scientific claims are bounded referents, not raw prose blobs

A scientific claim is an asserted empirical, methodological, numerical,
comparative, causal, taxonomic, or literature-state statement that appears in a
synthesis claim, argument move, or final prose locator. Operational metadata
such as run status, source counts, gate status, cost, and adapter error classes
are not scientific claims.

Persisted claim identity should use stable, artifact-attempt-scoped references:
claim key, argument id, section/paragraph key, citation id, charting field,
evidence-matrix cell, locator, hash, and bounded summary. It must not persist the
full claim prose, full source text, charting-row payload, manuscript paragraph,
prompt, completion, or provider response unless a separate document/source-store
ADR creates a narrower storage contract.

The existing provenance node kinds `SYNTHESIS_CLAIM`, `ARGUMENT_MOVE`, and
`FINAL_PROSE` are the initial claim/prose referents. If issue #1022 needs a more
explicit claim ledger, it should first try to extend the provenance/rationale
vocabularies and validation rules. A new aggregate is justified only by an
independent lifecycle, indexing, retention, or access-control need.

### 3. Grounding has four closed categories

Every scientific claim must have at least one accepted grounding category:

| Category | Required shape |
|---|---|
| Source | A provenance path to a normalized source/full-text referent. Full-text claims require full-text access/read state; unresolved metadata or abstracts alone do not ground claims that depend on the full text. |
| Charted cell | A provenance path to `CHARTING_CELL` or `EVIDENCE_MATRIX_CELL`, and from there back to an included source/full-text referent. |
| Computation | A bounded computation referent that names inputs, method/formula/version, and result locator/hash, with provenance edges back to source or charted-cell inputs. Code execution and provider calls remain adapter responsibilities. |
| Explicit inference | A rationale entry tied to the claim/prose referent that states the inference basis and limitation, with provenance edges to its premises. Explicit inference must be labelled as inference or limitation-aware reasoning; it cannot masquerade as directly sourced empirical evidence. |

Implementation must not overload `RationaleEvidenceBasis.EXPLICIT_LIMITATION` to
mean explicit inference. If explicit inference or computation becomes
API-visible, add closed enum values/DTO fields under ADR-034 mirror and drift
rules.

### 4. Citations, provenance, rationale, and disclosure remain distinct

A citation is a bibliographic/prose marker. It is necessary for many claims but
not sufficient by itself. Citation/source mismatch and citation to a source that
was not included, read, or charted are factuality failures.

Provenance answers "from what did this claim derive?" Rationale answers "why is
this choice or inference acceptable?" Disclosure answers "what AI contribution,
human approval, or unresolved uncertainty must be exposed in the final output?"
The implementation must not let any one of these surfaces replace the others.

`TraceabilityLink` remains requirement-to-artifact traceability. It can link
`GC-RSCH-N001` to this ADR, issues, tests, API artifacts, or PRs, but it is not
the run-local source-to-cell-to-claim grounding graph.

`EvidenceArtifact` remains the GRC summarized-evidence aggregate. Research
claims may later be promoted or linked into GRC evidence by a separate lifecycle,
but factuality for a research run does not turn every source, charted cell, or
claim into `EvidenceArtifact`.

### 5. Validation and completion fail closed

Claim-grounding validation belongs in research domain services, not controllers,
MCP handlers, frontend code, workflow telemetry, or skill prompt text.

Validation must fail closed when:

- a scientific claim has no accepted grounding category;
- the grounding path crosses projects, runs, artifact attempts, or superseded
  records without an explicit historical query mode;
- a final-prose claim cites a source that is missing from the included/read source
  set;
- a charted-cell grounding lacks source/full-text provenance;
- a computation grounding lacks input/result/method identity;
- an explicit inference lacks a rationale entry, premise path, or limitation
  label;
- a stale artifact attempt is used to satisfy a current claim;
- the selected methodology cannot support the strength of the claim without a
  limitation or non-claim marker.

Failures are product validation failures, not raw parser/provider exceptions.
They use existing `GroundControlException` subclasses and the standard
`ErrorResponse` envelope with stable codes and bounded field names.

### 6. Cross-cutting layers stay shared

- **Security and authorization:** REST paths stay under ADR-026 bearer/browser
  chains and `ApiPathMatrix`. Every read/write resolves a single project through
  `ProjectService`; cross-project run/source/claim misses are concealed as
  `404`.
- **Input validation:** REST DTOs use Bean Validation and Jackson enum binding;
  MCP inputs use flat Zod schemas and existing request helpers. Services own
  semantic checks for same-run references, active artifact attempts,
  source-disposition compatibility, grounding category completeness,
  idempotency, and content bounds.
- **Actor provenance and audit:** mutation actors come from `ActorFilter` /
  `ActorHolder` and Envers metadata. Reviewer, adapter, provider, model, or tool
  labels are descriptive provenance and cannot override the authenticated actor.
- **Logging:** use SLF4J with low-cardinality fields: project, run id, stage,
  artifact type, attempt, claim key, node kind, grounding category, source id,
  and counts. Do not log claim prose, full text, charting rows, manuscripts,
  prompts, completions, provider payloads, tokens, Zotero secrets, Git
  credentials, or absolute private paths.
- **Configuration and OS/runtime exposure:** factuality validation introduces no
  new secrets, subprocesses, shell-outs, provider calls, GitHub writes, citation
  calls, arbitrary filesystem scans, or token-in-argv path. Provider, citation,
  Git, filesystem, reviewer, computation, or drafting effects remain at the
  ADR-028/ADR-055/ADR-071/ADR-073 adapter boundaries and re-enter through
  structured service commands.
- **MCP and drift gates:** curated MCP writes mirror REST. `gc_query` remains
  GET-only and allowlisted. Any new public enum, DTO mirror, action discriminator,
  body allowlist, or query allowlist follows ADR-034/OpenAPI/MCP drift checks.
- **Testing and policy:** controller additions need `@WebMvcTest` slices; service
  tests cover project/run isolation, unsupported claims, citation/source
  mismatch, charting/source lineage, computation identity, explicit-inference
  limits, stale artifact attempts, supersession, content-leak guards, and
  idempotency where writes are retried. Repo work still completes through
  `make policy`.

### 7. Extensibility seam

The extension seam is the grounding vocabulary and claim-reference shape:

- claim kind/reference: synthesis claim, argument move, final prose locator, and
  future peer-review or revision-response claim types;
- grounding category: source, charted cell, computation, explicit inference;
- source/source-state vocabulary from ADR-071;
- provenance node kind and edge relation additions under ADR-034;
- method-specific overclaim rules and claim-strength limits selected by
  methodology profile/catalog entry;
- adapter provenance for source acquisition, extraction, computation, review, or
  rendering.

Adding a source provider, extraction schema, computation adapter, writing
template, or output format should extend those vocabularies and validators. It
should not create a parallel factuality engine, a provider-specific claim schema,
or a generic `Map<String,Object>` validation escape hatch.

## Consequences

### Positive

- `GC-RSCH-N001` becomes an enforceable backend invariant over existing research
  ledgers rather than a manuscript-writing guideline.
- The design reuses research run artifacts, provenance, rationale, source
  identity, final disclosure, REST/MCP, audit, validation, logging, and error
  handling contracts already in the repo.
- Future claim-ledger and grounding-validation work has a clear seam without
  forcing a new content store or graph subsystem.

### Negative

- A claim-grounding validator must compose several ledgers and will need careful
  service tests; a single citation check is not enough.
- Computation and explicit inference need explicit vocabulary before they become
  API-visible; implementations cannot hide them in generic summaries.
- The backend will not prove the semantic truth of a claim. It can enforce that
  accepted claims are traceable to accepted evidence, computation, or labelled
  inference and can flag overclaim risks.

### Risks

- If claim keys are unstable across artifact attempts, grounding records will be
  hard to reconcile after rework.
- If raw claim/source content is copied into broad DTOs, errors, logs, graph
  properties, or MCP responses, unpublished research and provider data can leak.
- If explicit inference is treated as a loophole, unsupported claims can pass by
  being renamed rather than being grounded or limited.
- If methodology-specific claim-strength limits are skipped, source-grounded
  prose can still overstate what the selected review method supports.
- If MCP/UI/drafting adapters reimplement validation, they can disagree with
  service-owned grounding and complete or export invalid artifacts.

## Non-Goals

- No implementation of entities, migrations, controllers, DTOs, services, MCP
  tools, frontend views, parsers, validators, graph contributors, or adapters in
  this ADR.
- No manuscript, PDF, full-text, prompt/completion, raw provider-payload,
  charting-row, or generic claim-content store.
- No automatic natural-language classifier that extracts every claim from prose.
  Such a classifier can be an adapter input later, but the accepted product fact
  must still be structured and service-validated.
- No replacement of ADR-055 skills/citation MCP, ADR-064 lifecycle/artifacts,
  ADR-067 rationale, ADR-068 disclosure, ADR-069 provenance, ADR-071 source
  identity, or ADR-072 REST/MCP surface.
- No new authentication model, actor override mechanism, error envelope, logging
  stack, enum mirror system, workflow engine, direct AGE write path, GitHub
  side-effect path, or token-in-argv path.

## Related Requirements

- `GC-RSCH-N001` - Factuality.
- `GC-RSCH-R004` - full provenance chain.
- `GC-RSCH-R006` - reproducible research artifacts.
- `GC-RSCH-N002` - provenance.
- `GC-RSCH-N012` - explainability.
- `GC-RSCH-N013` - human accountability.
- `GC-RSCH-N016` - scientific humility.

## Related Issues

- #1005 - Research methodology catalog and primary-source tracking.
- #1010 - Research source records and deterministic bibliographic resolution.
- #1014 - Research full-text acquisition and access-gap enforcement.
- #1016 - Research charting schema, pilot coding, and evidence spans.
- #1018 - Research evidence matrix and numerical synthesis.
- #1020 - Research method-limit and overclaim checks.
- #1022 - Research argument claim ledger.
- #1023 - Research evidence-constrained drafting.
- #1024 - Research citation and prose grounding validation.

## Related ADRs

- ADR-026 - REST API Access Control.
- ADR-028 - Temporal Workflow Orchestration Boundary.
- ADR-033 - Authenticated Audit Actor Provenance.
- ADR-034 - API Enum Contract Single Source of Truth.
- ADR-035 - MCP Tool Catalog Curation.
- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-067 - Research Explainability Rationale Ledger.
- ADR-068 - Research Final-Output Accountability Disclosure.
- ADR-069 - Research Artifact Provenance Ledger.
- ADR-071 - Research Interoperability and Source Identity Boundary.
- ADR-072 - Research REST and MCP Tool Surface.
- ADR-073 - Research Extensibility and Adapter Boundary.

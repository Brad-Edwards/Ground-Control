# ADR-055: Research workflow skills and citation MCP

## Status

accepted

## Date

2026-05-26

## Context

Ground Control's research project type needs working artifact infrastructure: a methodology contract, an executable literature-review plan, a citation-grounded search and charting workflow, an argument-architecture phase, and a drafting phase whose prose can only cite sources present in the evidence base. The 14 GC-RSCH-F* requirements in scope for issue #1032 (F005–F009, F012, F014, F017, F019, F020, F024, F028, F030, F038) name what that infrastructure must do; F038 specifically calls for explicit rationale when a working asset is replaced rather than reused.

A separate single-purpose repository (now retired) developed and field-tested most of these artifacts against real failure modes—citation hallucination (88 partially hallucinated references in one drafting run), domain leakage into methodology outputs, invented procedural detail, imported framing without provenance, and synthesis claims unsupported by the charted corpus. The two most valuable disciplines it ended on are:

- methodology requirements extracted from primary methodology sources, never paraphrased from memory; and
- the two-state source rule: a source is either fully in the review with full text read and charted, or recorded as an access gap and not charted.

The implementation cost of rebuilding these from scratch is high; the value of every individual discipline was earned against an observed failure that would recur in any independent rebuild.

ADR-029 establishes the GitHub issue thread as the durable workflow record; ADR-031 separates structured findings from the privileged GitHub writes the MCP server performs; ADR-036 mandates deterministic renderers and opt-in routing/telemetry. This ADR adds research workflow surfaces under those existing constraints rather than introducing parallel ones.

## Decision

Ground Control ships five Claude skills, one methodology catalog, one deterministic citation MCP, and supporting documentation as the research project type's first-class artifact infrastructure. Content is sourced from the retired research repository; layout, naming, and conventions follow Ground Control's own.

**Skills (under `skills/`).**

| Skill | Purpose |
|---|---|
| `lit-review` | Phase 1—pick the methodology; read the catalog's primary sources; extract the requirements specification a downstream plan must satisfy. |
| `lit-review-plan` | Phase 2—fill the phase-1 requirements with domain content; produce an executable lit-review plan. |
| `lit-review-search` | Phase 3—run the plan: search, screen, chart fully in sources, record access gaps, synthesise. Output is the evidence base, not paper prose. |
| `lit-review-argument` | Phase 4—build the paper's argument architecture: section outline plus validated Argdown argument map with grounded premises and modelled objections. |
| `lit-review-draft` | Phase 5—draft the manuscript from the phase-4 architecture and the phase-3 evidence base; cite only included-set sources; apply the voice contract; pass the model-tell blocklist. |

Skill chaining: `lit-review` auto-chains into `lit-review-plan` into `lit-review-search` (one user invocation, three phases). `lit-review-argument` is user-invoked after evidence review; `lit-review-draft` is user-invoked after argument review. Gates are surfaced with recommendation + reasoning as they arise, never punted to an end-of-document backlog.

**Methodology catalog (`skills/lit-review/methodology/catalog.yaml`).**

Co-located with the phase-1 skill that loads it. Method key → primary methodology source Zotero keys + titles + PDF availability. Lookup-only—no prose summaries. Ships with seven entries: `scoping`, `systematic`, `mapping`, `critical`, `narrative_conceptual`, `targeted_related_work`, `taxonomy_development`.

**Citation MCP (`mcp/citation/`).**

A Python FastMCP server (`citation-mcp` package) exposing seven deterministic bibliographic tools: `cite_resolve`, `cite_search`, `cite_forward`, `zotero_add`, `oa_locate`, `zotero_attach_pdf`, `zotero_search`. Backs onto Crossref, OpenAlex, Unpaywall, the Zotero translation-server, and the Zotero Web API. The agent's role is identifier-shaped (DOI / arXiv / PMID / search string); citation metadata is produced by authoritative services, never from model memory. OA-only PDF attachment is enforced via a 15-minute cache of `oa_locate` results.

Registered in `.mcp.json` under server name `citation`; skills reference its tools as `mcp__citation__*`. Renaming the server breaks every internal skill cross-reference and is therefore out of scope.

**Documentation.**

- `docs/research/RESEARCH_WORKFLOW.md`—Ground Control-native overview of the five-phase pipeline, the citation MCP, and the bootstrap steps.
- `docs/knowledge/research-workflow/auto-research-requirements-and-oss-assessment.md`—historical FR/NFR + OSS landscape assessment retained as a knowledge-base reference for future build-vs-adopt calls.

## Asset disposition

Sourced from the retired research repository. Disposition reflects what each asset earned its place against.

| Source asset | Disposition | Rationale |
|---|---|---|
| 5 phase skills | KEPT (renamed: drop source-repo prefix) | Each asset earned its place against an observed failure mode; rebuilding would recreate the failures the discipline addresses. |
| `methodology/catalog.yaml` | KEPT verbatim, relocated | Catalog is lookup-only; co-locating with the skill that loads it removes a top-level directory the host repo does not need. |
| Argdown validation tooling (`validate-argument-map.sh`, `check-argument-structure.mjs`, tests) | KEPT verbatim | Structural argument checking is the only mechanical guard against unreconstructed-support, ungrounded-premise, and unanswered-objection failures. |
| Voice contract (`writing-style.md`) | KEPT verbatim | Model-tell blocklist is the only mechanical guard against language-model writing tells. |
| Citation MCP Python package | KEPT verbatim (renamed) | Determinism in bibliographic resolution is not replaced by stronger model reasoning—see `auto-research-requirements-and-oss-assessment.md`. |
| Project handoff (`AGENTS.md`) | DROPPED in favor of `docs/research/RESEARCH_WORKFLOW.md` | Host-repo-specific overview deserves a Ground Control-native rewrite, not a port of source-repo bootstrap text. |
| Source repo's `pyproject.toml` for citation MCP | ADAPTED | Package name dropped to `citation-mcp` (no source-repo prefix). Dependencies unchanged. |
| Source repo's `.ground-control.yaml`, `.gc/plan-rules.md` | DROPPED | Ground Control already has its own; duplicating would conflict. |
| OSS landscape assessment note | KEPT verbatim, relocated to `docs/knowledge/research-workflow/` | Historical record of the build-vs-adopt analysis; lasting reference value for future re-evaluation. |

## Naming and conventions

- Skill names drop the source-repo prefix. They are first-class Ground Control skills now, not "ported" assets.
- Python package: `citation-mcp` under `mcp/citation/` (sibling of the existing JS `mcp/ground-control/`).
- MCP server name in `.mcp.json`: `citation` (generic; the tool prefix `mcp__citation__*` referenced inside the skills depends on this name).
- Methodology catalog: `skills/lit-review/methodology/catalog.yaml` (co-located with the loader).
- Skill cross-references inside SKILL.md files use the new (un-prefixed) skill names.

## Consequences

- The 14 GC-RSCH-F* requirements in scope for issue #1032 are satisfiable by the skills + MCP + documentation shipped here. The clause-mapping step in `/implement` (Step 4.5) creates the IMPLEMENTS / DOCUMENTS traceability links.
- The citation MCP requires a Python ≥ 3.11 environment and a running Zotero translation-server (`docker run -d --rm -p 1969:1969 zotero/translation-server`) for `zotero_add`. Bootstrap is documented in `mcp/citation/README.md`.
- `.mcp.json` carries a new `citation` entry whose `command` assumes a venv at `mcp/citation/.venv/`. Operators bootstrap the venv with `python -m venv mcp/citation/.venv && mcp/citation/.venv/bin/pip install -e mcp/citation/`.
- Skills run in workspaces the user chooses; per-paper artifacts (`requirements.md`, `lit-review-plan.md`, `synthesis.md`, etc.) live in the workspace, not in this repository.
- ADR-029 still applies: workflow decisions during a research run belong on the issue thread, not in workspace-local files.
- The drafting phase emits IEEE-format LaTeX manuscripts; this introduces no new repository-level requirement on Ground Control, since manuscripts live in workspaces.
- Future work: a project-type registration on the Java domain side (so a Ground Control project can be marked `RESEARCH`) is out of scope for this ADR—existing project plumbing already accepts user-applied type labels. If a typed enum is later wanted, that is a separate ADR.

## Alternatives considered

**Rebuild from scratch in TypeScript alongside `mcp/ground-control/`.** Rejected: the deterministic bibliographic resolution depends on Python libraries (`pyzotero`, FastMCP) where the equivalent JS path is significantly thinner; cost without offsetting benefit; would lose the field-tested resilience the existing implementation has accumulated.

**Keep the source-repo prefix in skill names for provenance.** Rejected: provenance belongs in this ADR, not in every cross-reference for the next five years. Treating these as Ground Control's skills going forward is correct.

**Place the methodology catalog at the repository root.** Rejected: the catalog is only loaded by one skill, and the host repository has no other top-level data directories. Co-location with the loader keeps the skill self-contained.

**Adopt PaperQA / LatteReview / AgentSLR / STORM as the core rather than the source-repo design.** Rejected per `auto-research-requirements-and-oss-assessment.md`: no single existing OSS project provides method-aware requirements contract + strict source-state discipline + Zotero/citation grounding + systematic charting + synthesis traceability as a coherent tool. Those projects are useful as bounded adapters (full-text Q&A, screening, extraction, drafting) downstream of an evidence base—not as governance.

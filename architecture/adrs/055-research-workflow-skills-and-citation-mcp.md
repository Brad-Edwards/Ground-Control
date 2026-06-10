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
| Argdown validation tooling (`validate-argument-map.sh`, structural handlers, tests) | MIGRATED in issue #1045 | Node `@argdown/cli` + custom `check-argument-structure.mjs` replaced by `argdown-feedback` (Python; DebateLab @ KIT). Reasons in the "Argdown validator migration (#1045)" section below. |
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

## Argdown validator migration (#1045)

The phase-4 validator originally shipped with this ADR as a Node script (`@argdown/cli` 2.0.0 plus `check-argument-structure.mjs`) that ran four project-specific structural checks: ungrounded premises (A), unreconstructed support arguments (B), unanswered objections (C), and circular support (D). Issue #1045 migrated the implementation to `argdown-feedback`, which is maintained by DebateLab @ KIT (the same group that authors `pyargdown`). The wrapper script (`validate-argument-map.sh`) keeps its existing entrypoint, default map path, and exit-code contract (0 OK; 1 map failure; 2 bad input; 3 environment). Behind it, the implementation is now Python:

- `pyargdown` (bundled by `argdown-feedback`) parses the .argdown file.
- `skills/lit-review-argument/handlers/` runs A, B, C, D as plain Python checks against the parsed graph. These are project-specific to our argument-map style and not covered by any upstream handler. The InfReco family that `argdown-feedback` ships expects PCS-only argdown without explicit dialectical relations, and its `OnlyGroundedDialecticalRelationsHandler` actively rejects the explicit `<+`/`<-` style our maps use, so the migration does not pretend upstream owns these rules.
- New capability: `--logreco` invokes `argdown_feedback.verifiers.core.logreco_handler.LogRecoCompositeHandler`, which formalizes each PCS in first-order logic (NLTK) and checks deductive validity with Z3 (`z3-solver`). The flag is opt-in and requires `{formalization: "…", declarations: {…}}` metadata on PCS members. Without it the validator's coverage matches what shipped originally, so default usage stays the same.

**Dependency pinning and bootstrap.** `argdown-feedback` cuts no PyPI releases. `requirements.txt` carries the human-readable top-level pin (commit SHA of `argdown-feedback`); `requirements-lock.txt` is the install source of truth, a `pip-compile --generate-hashes` lockfile covering the whole transitive tree. The wrapper splits the lockfile at install time and runs two `pip install --no-deps` calls: one for VCS entries (`argdown-feedback`, `pyargdown`, whose commit SHA is itself a content hash), and one for PyPI entries under `--require-hashes`. A `.venv/.lock-sha256` sentinel records the lockfile hash that was last installed; when `requirements-lock.txt` changes the wrapper reinstalls, which closes the "old venv silently runs against a new SHA" path that an "import succeeds, done" check would leave open. Bumping the pin is one operation: edit `requirements.txt`, run `pip-compile --generate-hashes --output-file requirements-lock.txt requirements.txt` inside the venv, commit both files. Same outer pattern as the citation MCP bootstrap.

**AGPL-3.0 posture.** `argdown-feedback` is licensed under AGPL-3.0. Our wrapper imports modules from the package rather than running it as a pure subprocess, so the conservative legal reading is that AGPL terms apply to the importing code, not only to redistributed copies. Practical effect on Ground Control is bounded because the validator is a skill-time tool that runs on workspace files outside the repository's distribution surface, but this is risk rather than legal certainty. Two operational guards: (a) the import surface is contained inside `skills/lit-review-argument/`, and `mcp/ground-control/` does not import it; (b) any future extension that pulls `argdown-feedback` into a redistributable artifact requires re-reading the AGPL terms before shipping.

## Alternatives considered

**Rebuild from scratch in TypeScript alongside `mcp/ground-control/`.** Rejected: the deterministic bibliographic resolution depends on Python libraries (`pyzotero`, FastMCP) where the equivalent JS path is significantly thinner; cost without offsetting benefit; would lose the field-tested resilience the existing implementation has accumulated.

**Keep the source-repo prefix in skill names for provenance.** Rejected: provenance belongs in this ADR, not in every cross-reference for the next five years. Treating these as Ground Control's skills going forward is correct.

**Place the methodology catalog at the repository root.** Rejected: the catalog is only loaded by one skill, and the host repository has no other top-level data directories. Co-location with the loader keeps the skill self-contained.

**Adopt PaperQA / LatteReview / AgentSLR / STORM as the core rather than the source-repo design.** Rejected per `auto-research-requirements-and-oss-assessment.md`: no single existing OSS project provides method-aware requirements contract + strict source-state discipline + Zotero/citation grounding + systematic charting + synthesis traceability as a coherent tool. Those projects are useful as bounded adapters (full-text Q&A, screening, extraction, drafting) downstream of an evidence base—not as governance.

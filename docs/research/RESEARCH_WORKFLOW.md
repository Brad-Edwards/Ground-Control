# Research workflow

Ground Control's research project type ships a five-phase literature-review pipeline implemented as Claude skills, a methodology catalog, and a deterministic citation MCP. The pipeline answers one question for a given research paper:

> What are the formal requirements a literature-review plan must meet to satisfy the appropriate methodology, and how do we execute that plan, build the paper's argument, and draft it without fabricating citations or asserting findings the evidence base does not carry?

See ADR-055 for the skill/citation architecture and asset disposition. Durable
run lifecycle state, checkpoint artifacts, and human-gate policy are governed by
ADR-064. The user-facing run status/observability snapshot is governed by
ADR-065. Gate decision logs and review comments are governed by ADR-066;
explainability is governed by ADR-067; final-output accountability is governed
by ADR-068.

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

The catalog at `skills/lit-review/methodology/catalog.yaml` is a lookup, not a paraphrase: method key → primary methodology source Zotero keys + titles + PDF availability. The phase-1 skill reads the actual source PDFs to ground its method choice; the catalog only tells it *which* sources to read.

Methods shipped: `scoping`, `systematic`, `mapping`, `critical`, `narrative_conceptual`, `targeted_related_work`, `taxonomy_development`.

Adding a method: append a new entry with the primary methodology source Zotero keys. Do not add prose summaries - the phase-1 skill reads the sources directly.

## Citation MCP

See `mcp/citation/README.md` for the bootstrap (`python -m venv`, `pip install -e`, optional `zotero/translation-server` Docker), the tool inventory, and the environment variables.

The MCP is registered in `.mcp.json` as `citation`. Tools surface to the skills as `mcp__citation__cite_resolve`, `mcp__citation__cite_search`, etc.

## How this earned its place

Every discipline in the skills addresses a failure mode that has been observed in real paper drafting - citation hallucination (88 partially hallucinated references in one round), domain leakage into methodology outputs, invented procedural detail, imported framing without provenance, synthesis claims unsupported by the charted corpus, manuscripts that read as workflow memos. The OSS landscape assessment under `docs/knowledge/research-workflow/` records the build-vs-adopt analysis that established no single existing tool provides the combined discipline these skills enforce.

If a future failure mode appears that genuinely cannot be addressed by skill instructions and source reading, the smallest possible support for that specific failure mode goes in - and the observed failure it addresses is written down so it can be defended later. Speculative additions against theoretical failure modes get cut.

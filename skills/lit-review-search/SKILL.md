---
name: lit-review-search
description: Phase 3 of the literature-review workflow. Executes the phase-2 lit-review plan - runs the search, screens candidates, charts included sources, synthesises. Normally chained from `lit-review-plan` automatically. Can be invoked standalone when a `lit-review-plan.md` already exists in the workspace. The output is the review's evidence base (source set, charting data, coding scheme, evidence matrix, synthesis), not paper prose.
---

# # Lit-Review Search (Phase 3)

This skill executes the protocol in `lit-review-plan.md`: it runs the search, screens candidates, charts the included sources, and synthesises. Output is the review's evidence base - not the paper's prose.

Phase 1 chose the method and listed the requirements. Phase 2 filled them into an executable plan. This skill *runs* that plan.

## What this skill counters

Phase 3 is where hallucination does the most damage: this is the phase that pulls sources into the review. The whole skill is built around one danger - **the agent pulling things that are not real** - and the citation MCP is the counter to every form of it.

- **Source does not exist.** Hallucinated DOI, fabricated title or authors. Counter: every source enters the review *only* via `mcp__citation__cite_search`, `mcp__citation__cite_forward`, or `mcp__citation__cite_resolve` (real OpenAlex / Crossref records), or `mcp__citation__zotero_search` (the user's library). A source the agent typed from memory does not enter. Ever.
- **Source exists but its content is misremembered.** Charting what the paper "probably says." Counter: a source is charted *only after its full text has been read* - a Zotero attachment, or an OA PDF obtained via `mcp__citation__oa_locate` + `mcp__citation__zotero_attach_pdf`. No charting from an abstract. No charting from training-memory of what the paper contains.
- **Backward-snowball reference is wrong.** Citing a reference a paper did not actually cite. Counter: backward snowballing uses the `reference` array returned by `cite_resolve` (Crossref's actually-deposited reference list) - never the agent's recollection of what a paper cites.
- **Forward-citation fabrication.** Inventing "cited by" papers. Counter: forward snowballing uses `mcp__citation__cite_forward` (OpenAlex `cites:` query) - never a hand-search reconstructed from memory.
- **Synthesis asserts patterns not in the data.** "The literature shows X" where X is not in what was charted. Counter: the numerical summary is literally counts of charted cells; every thematic claim traces to specific charted, included sources. The synthesis cannot reference a source not in the charting form.

## The discipline - the two-state rule

Every source is in exactly one of two states. There is no third state.

- **(a) Fully in the review.** Resolved via the citation MCP → added to the paper's Zotero collection → full text obtained → full text *read* → charted.
- **(b) Access gap.** Resolved via the citation MCP → added to the paper's Zotero collection → full text *not* obtainable through any legitimate route → recorded as an access gap → **not charted**.

A source is never "charted from its abstract", never "charted from what the agent recalls of it", never "included in the synthesis without being charted". If the full text cannot be read, the source is an access gap and the synthesis treats it as one. The aggregate access-gap count is reported (it bounds the review's coverage claims).

The protocol in `lit-review-plan.md` is the contract. Do not relitigate it. If executing it surfaces a genuine flaw in the plan, stop and surface that to the user - do not silently deviate.

For taxonomy-development plans, the same two-state rule applies inside each source role. A source may be fully in or an access gap for the taxonomy-instance corpus, the background/framing set, the methodology set, or the validation/evaluation set. Do not move a source between roles just because it is convenient. In particular, background/framing sources do not become evidence for recurrence, prevalence, coverage, or exhaustiveness unless the plan explicitly assigned them that evidentiary role.

## Workflow

1. **Read the plan.** Load `lit-review-plan.md` (and `requirements.md`, `decisions.md`) from the workspace. If `lit-review-plan.md` does not exist, phase 2 has not run - stop and run `lit-review-plan` first.

2. **Confirm the paper's Zotero collection.** The citation MCP does not create Zotero collections - that is a user action, deliberately kept outside the agent's privileged surface. Resolve the `paper_collection_key` for this run via a gate (use `AskUserQuestion`): either reuse an existing collection the user names (the user can verify the key via Zotero's UI, or the agent can list candidates with `mcp__citation__zotero_search` filtered by a tag the user assigns to research collections), or pause while the user creates a new collection in Zotero and supplies the key. Cache the resolved `paper_collection_key`. Every source that enters the review - fully-in or access-gap - is added to it via `zotero_add` with that collection key.

3. **Search.** Run the plan's search strategy:
   - Query-based search via `cite_search` against the databases/facets the plan names.
   - Backward snowballing from seed papers via the `reference` arrays of `cite_resolve`.
   - Forward snowballing via `cite_forward`.
   Every candidate is a real record. Record the queries run and the date, for PRISMA-ScR item 7/8 reporting.

   For taxonomy-development plans, record candidates by role: taxonomy-instance corpus, background/framing, methodology, and validation/evaluation. The plan may use different search procedures for each role; execute the role-specific procedure rather than collapsing everything into one review corpus.

4. **Screen.** Apply the plan's eligibility criteria, three-pass per the plan (title → abstract → full-text). Title/abstract from `cite_resolve` metadata. Full-text screening needs the PDF (Zotero attachment, or `oa_locate` + `zotero_attach_pdf`). Log a reason for every exclusion (the plan's single-screener discipline requires this). Selection is iterative - refine and re-screen per the plan.

5. **Chart.** For every included source, apply the two-state rule. Fully-in sources are charted from their read full text into the plan's charting form; every load-bearing charted value is traceable to a section of the source. Access-gap sources are recorded as gaps, not charted. Develop emergent sub-codes per the plan's coding scheme; run the plan's form-calibration step.

   For taxonomy-development plans, chart taxonomy-instance sources into the taxonomy construction form, not into a generic scoping-review table unless the plan says so. Record iteration triggers and category changes exactly as the plan requires. Background/framing sources may motivate the problem or context, but they are not charted as evidence for category recurrence or coverage unless the plan explicitly licenses that use.

6. **Synthesise.** Numerical summary = counts over the charted cells. Thematic analysis = the plan's QCA approach over the charted data. Assemble the evidence matrix. Every synthesis claim traces to charted cells; nothing is asserted that the charted data does not support.

   For taxonomy-development plans, synthesis includes the final taxonomy, the iteration/change rationale, evaluation results, source-role-specific limitations, and non-claims. A taxonomy can claim construct/evaluation support only from the roles that the plan assigned to those claims.

7. **Self-review.** Apply the angles below in `search-self-review.md`.

8. **Record decisions.** Extend `decisions.md` with every search-phase decision - search-strategy refinements, post-hoc criteria changes, access-gap escalations, calibration-threshold setting, sub-code additions. Same format as earlier phases.

**Gates** are raised the same way as in phase 2: when a decision is genuinely the user's (for example, a load-bearing access gap the user might obtain through institutional channels; an eligibility-criterion ambiguity that materially changes the corpus), bring it up immediately with a recommendation and reasoning, resolve, log, continue. No end-of-document backlog.

## Output structure

In the workspace:

- **`charting-data.csv`** (or `.md` table) - one row per included source, the plan's charting-form fields. Access gaps listed separately with their gap reason.
- **`coding-scheme.md`** - the finalised coding scheme: starter codes plus emergent sub-codes, with definitions and source-grounded examples.
- **`evidence-matrix.md`** - sources × instrument-problem-codes (or the plan's equivalent), cells filled from the charting data.
- **`synthesis.md`** - numerical summary + thematic analysis + the plan's "applying meaning" section. Every claim traceable.
- **`search-log.md`** - queries run, dates, candidate counts, PRISMA-ScR flow numbers (screened / excluded with reasons / included), access-gap list.
- **`search-self-review.md`** - the self-review.
- **`decisions.md`** - extended.

For taxonomy-development plans, also include taxonomy-specific evidence artifacts when the plan requires them:

- **`taxonomy.md`** - final dimensions, characteristics, definitions, and unit-of-analysis notes.
- **`taxonomy-iterations.md`** - iteration-by-iteration changes and rationale.
- **`taxonomy-evaluation.md`** - evaluation criteria, evidence, results, and limitations.

The paper's prose is *not* an output of this skill.

## Self-review angles

### Grounding angles (the not-real danger)

- **Every-source-grounded pass.** Is every source in the review - fully-in and access-gap alike - traceable to a `cite_search` / `cite_forward` / `cite_resolve` / `zotero_search` result? Any source whose provenance is "the agent knew of it" must be re-grounded through the MCP or removed.
- **Two-state pass.** Is every source either fully-in (resolved + added + full text read + charted) or access-gap (resolved + added + not charted)? Any source charted without its full text read is a violation - fix it or demote it to access-gap.
- **Snowball-completeness pass.** Did backward snowballing (`cite_resolve` reference arrays) and forward snowballing (`cite_forward`) actually run per the plan, or was a direction silently skipped?
- **Synthesis-traceability pass.** Does every claim in `synthesis.md` trace to charted cells? Any "the literature shows" / "most studies" sentence that does not resolve to specific charted, included sources is fabrication - remove or ground it.
- **Exclusion-log pass.** Does every excluded source have a logged reason? PRISMA-ScR item 17 requires it; the plan's single-screener discipline requires it.
- **Taxonomy-role pass.** For taxonomy development, did each source stay in its assigned role, and does every taxonomy construction, evaluation, recurrence, coverage, or exhaustiveness claim trace only to sources whose role supports that claim?

### Sharpness angles (carried from phase 2 - a disciplined search can still be a loose one)

- **Claim-term pass.** Does the synthesis honour the operational definition of the paper's key claim terms set in phase 2 (for example, what "recurring" requires)? A synthesis that claims more than the operational definition licenses is over-reach.
- **Coding-separability pass.** Did the emergent sub-codes and any new top-level codes stay separable, or did charting produce ambiguous multi-coded cells that signal an overlapping scheme? Report it.
- **Method-limits pass.** Does the synthesis respect the method limits (no quality-weighted claims for a scoping review, no prevalence claims, no effect estimates)?

## What this skill is NOT

- It is not phase 1 or phase 2. If `lit-review-plan.md` does not exist, stop and run the earlier phases.
- It is not paper writing. The output is the evidence base; the prose is built downstream by phases 4 and 5 (`lit-review-argument`, then `lit-review-draft`), which the user invokes after reviewing the evidence base. This skill does not auto-chain into them - the evidence base is a checkpoint.
- It is not a place to pull sources from memory. Every source routes through the citation MCP.
- It is not a place to relitigate the plan. The plan is the contract; a genuine flaw is surfaced to the user, not silently worked around.

---
name: lit-review-draft
description: Phase 5 of the literature-review workflow. Takes the phase-4 argument architecture and the phase-3 evidence base and drafts the paper as a submission-shaped IEEE-format manuscript - prose plus a visible evidence surface (inline citations from a Zotero-generated reference list, a PRISMA-ScR flow, a coding-scheme table, source examples). User-invoked after the argument architecture has been reviewed. Output is the manuscript in LaTeX with a markdown rendering. Final phase of the workflow.
---

# # Lit-Review Draft (Phase 5)

This skill drafts the paper from the phase-4 argument architecture, applying `writing-style.md`. It is the last phase of the literature-review workflow.

Phase 4 fixed what the paper argues, in what order, on what support. This skill writes it - as a manuscript a journal or conference reviewer reads cold, knowing nothing of Ground Control, the workflow, or its internal files. That is the test the output must pass.

The skill is built around three dangers. Two are about the argument: prose that drifts from the validated argument, and prose that papers over an inference gap. The third is the one this phase most reliably produces if unchecked - **a manuscript that reads as an internal synthesis memo**: it carries meta headers, names workflow artifacts the reader cannot see, and asserts findings by gesturing at charting and coding work it never shows. A scoping review's credibility is its visible evidence surface. A memo promoted into paper prose has none.

## What this skill counters

- **Prose that drifts from the argument.** The draft improvises claims not in the phase-4 argument map, or drops arguments that are. Counter: each section realizes specific `paper-outline.md` nodes and `argument-map.argdown` arguments; a paragraph that makes a load-bearing claim absent from the argument map is a violation - either the map was wrong (stop, return to phase 4) or the paragraph is.
- **Prose that papers over an inference gap.** Fluent connective prose - "therefore", "it follows that" - can smooth a step the premises do not license, hiding a non-sequitur or an unstated premise behind good writing. Counter: writing each load-bearing paragraph *is* the entailment test - the bridge from premises to conclusion must be written out, not gestured at; if it cannot be written without an unstated assumption, the phase-4 PCS has a hidden premise or a non-sequitur, fixed in phase 4 rather than smoothed over in prose.
- **A manuscript that reads as a workflow memo.** The draft carries text that belongs to the workflow, not the paper: a header noting the phase or the draft status; references to internal artifacts by their internal names - `paper-outline.md`, `argument-map.argdown`, "the evidence matrix", "charted cells", "the phase-2 cumulative-deviation assessment", "a dedicated report". A reader outside Ground Control can see none of these; naming them turns the manuscript into a memo about a process. Counter: the manuscript body carries no meta header, no phase or workflow vocabulary, and no reference to any artifact the reader cannot see. An internal artifact either becomes a visible element of the paper - a numbered table, a figure, an appendix - and is referenced as that, or it is not mentioned. Draft provenance and run-status go in `draft-self-review.md`, never in the manuscript.
- **Claims asserted, not demonstrated.** The draft makes a load-bearing empirical claim and backs it only by gesturing at unseen work - "an independent verification pass found", "the evidence matrix shows", "the coding scheme groups". The reader is asked to *trust* charting they never see. Counter: every load-bearing empirical claim is *demonstrated* on the page - by an inline citation to a specific source, by a numbered table the reader can inspect, or by a concrete example quoted or closely paraphrased from a source. A paper that asks for trust instead of showing the work fails review. Each procedure named in the methods is attributed to who performed it, and LLM assistance is disclosed where it occurred.
- **Citation hallucination, and a hand-built reference list.** A cited source does not exist, was never in the review, or its reference-list entry is typed from memory and wrong. Counter: `references.bib` is *generated* from the paper's Zotero collection via the citation MCP - `mcp__citation__zotero_search` enumerates the collection, `mcp__citation__cite_resolve` supplies canonical metadata - never authored by hand. Inline citations key into that generated file; the draft cites only sources in the phase-3 included set.
- **Inventing findings.** The draft states an empirical result the evidence base does not contain, or restates a charted count as a stronger claim than a count. Counter: every empirical claim traces to `synthesis.md`; frequencies are charted counts, worded as charted counts.
- **Over-claiming past the method.** Severity, prevalence, causation - the method's declared limits, at risk in prose. Counter: the method-limits language from `synthesis.md` is carried, in substance, into the limitations section; no sentence claims more than the method licenses.
- **Language-model writing tells.** Generic model prose: hedging cadence, the reflexive tricolon, decorative vocabulary, throat-clearing openers, metadiscourse that narrates the paper's own speech acts. Counter: `writing-style.md` - a voice profile plus an explicit blocklist; the style pass checks the draft against both.

## The discipline (what the output IS)

The output is a submission-shaped manuscript: a paper in IEEE format (LaTeX, the `IEEEtran` document class) with a Zotero-generated BibTeX reference list and a markdown rendering for review. It must be critiqueable by a reviewer who has never heard of # that is the bar.

Allowed: prose for every outline node; the validated arguments rendered as paragraphs; inline citations into the Zotero-generated `references.bib`; numbered tables and figures the reader can inspect (at minimum a PRISMA-ScR flow and a coding-scheme summary table; others the evidence warrants); concrete source examples; the method's limits stated plainly; procedures attributed to who performed them.

Not allowed: a meta header or any phase/workflow vocabulary in the manuscript body; a reference to an internal artifact the reader cannot see; a load-bearing empirical claim with no visible citation, table, or example behind it; a load-bearing claim not in the argument map; a finding not in `synthesis.md`; a citation or reference entry not generated from the Zotero collection; the tells in `writing-style.md`'s blocklist; a new argument invented at drafting time.

## Workflow

1. **Read the inputs.** From the workspace: `paper-outline.md`, `argument-map.argdown`, `synthesis.md`, `evidence-matrix.md`, `charting-data.md`, `search-log.md`, `decisions.md`. From this skill's base directory: `writing-style.md`. And the paper's stanza in `program/`. If `paper-outline.md` or `argument-map.argdown` is absent, phase 4 has not run - stop and run `lit-review-argument` first.

2. **Internalize the style.** Read `writing-style.md` in full before drafting. It is voice, tone, and vocabulary - not paper structure, not a licence to alter findings.

3. **Generate the reference list.** Enumerate the paper's Zotero collection (the phase-3 collection / tag) via `mcp__citation__zotero_search`; obtain canonical metadata via `mcp__citation__cite_resolve` where needed; emit `references.bib`. Every source the manuscript will cite gets a real, generated entry - not one reference is hand-typed. Doing this before drafting means every inline citation keys into a real entry from the start.

4. **Draft the manuscript** in IEEE format (LaTeX). Follow `paper-outline.md`; each section realizes its outline nodes, each load-bearing discussion paragraph a specific `argument-map.argdown` argument - the PCS is the paragraph's logical spine. Three disciplines hold throughout:
   - **Entailment.** Writing the paragraph is the entailment test - write the inference out, premises to conclusion. If the bridge needs an unstated assumption, the PCS has a gap; stop (see Gates).
   - **Manuscript hygiene.** The manuscript reads as a paper, not a memo: no meta header, no phase or workflow vocabulary, no internal-artifact names. Every procedure in the methods is attributed to who performed it; LLM assistance is disclosed where true.
   - **Demonstrate, don't assert.** Every load-bearing empirical claim is carried by something the reader can see. Build the evidence-surface elements the paper needs: a PRISMA-ScR flow (numbers screened / excluded-with-reasons / included); a summary table of the coding scheme (each code with its counts, strata, and recurrence status); a concrete example - quoted or closely paraphrased, with citation - for each major recurring code. Cite only included-set sources, keyed into `references.bib`.

5. **Style pass.** Check the prose against `writing-style.md` - voice-profile conformance, zero blocklist hits - section by section.

6. **Self-review.** Apply the angles in `draft-self-review.md`.

7. **Record decisions.** Extend `decisions.md` with drafting decisions: any phase-4 argument gap surfaced (and whether phase 4 was revisited), any claim scoped down in wording.

**Gates:** if drafting reveals a genuine flaw in the phase-4 argument - a missing premise, an unmodelled objection, a conclusion the evidence base will not carry - stop and surface it; fix it in phase 4, then resume. Do not paper over an argument flaw with prose.

This is the final phase. No auto-chain onward.

## Output structure

In the workspace:

- **`manuscript.tex`** - the paper in IEEE format (`IEEEtran`). Conference or journal variant follows the target venue.
- **`references.bib`** - the reference list, generated from the paper's Zotero collection.
- **`manuscript.md`** - a markdown rendering of the manuscript, for review.
- **`draft-self-review.md`** - the self-review. Draft provenance, the warm/cold-run note, and any workflow commentary live here - never in the manuscript.
- **`decisions.md`** - extended.

## Self-review angles

### Grounding angles

- **Argument-fidelity pass.** Does every load-bearing paragraph trace to an argument in `argument-map.argdown`? Any paragraph making a claim with no argument behind it is either an argument-map gap (return to phase 4) or an over-reach (cut it).
- **Entailment pass.** For each load-bearing paragraph, does the prose articulate the inference - premises to conclusion - or set the conclusion beside the premises and rely on adjacency? If the inference cannot be stated in a clean bridge sentence, the PCS it renders has a gap: a phase-4 fix. This is still the drafter checking its own inference; a robust independent re-derivation is a future enhancement of this skill.
- **Manuscript-not-memo pass.** Read the manuscript as a reviewer who has never heard of Ground Control. Is there a meta header, a phase number, or any workflow vocabulary in the body? Does any sentence name an artifact the reader cannot see - `paper-outline.md`, the argument map, "the evidence matrix", "charted cells", a "phase-N assessment", a "dedicated report"? Every hit is a violation: cut it, or turn the artifact into a visible table or appendix and reference that.
- **Demonstrated-not-asserted pass.** For each load-bearing empirical claim, is there something on the page the reader can check - an inline citation, a numbered table, a quoted example - or only a gesture at unseen work? A claim backed only by "a verification pass found" or "the evidence matrix shows" is asserted, not demonstrated. Add the citation, table, or example, or cut the claim.
- **Citation pass.** Is every inline citation keyed to a real entry in `references.bib`, and was `references.bib` generated from the Zotero collection rather than hand-authored? Is every cited source in the phase-3 included set? Any entry not traceable to the Zotero collection is removed.
- **Findings pass.** Does every empirical claim trace to `synthesis.md`? Are frequencies worded as charted counts, not as prevalence?
- **Method-limits pass.** Does any sentence claim severity, prevalence, causation, or exhaustiveness the method cannot support?

### Style angles

- **Voice pass.** Does the draft match the `writing-style.md` voice profile - sentence rhythm, contrast-driven argument, concreteness, active voice, claims stated flat?
- **Blocklist pass.** Zero hits on the model-tell blocklist, including the metadiscourse entry? Run the list literally against the text.
- **Hedge pass.** Is uncertainty *located* - named as a specific open question - rather than *hedged* - smeared with "may", "could", "arguably"?

## What this skill is NOT

- It is not phase 4. If the argument architecture is absent, stop and run `lit-review-argument`.
- It is not a place to invent argument. The argument is phase 4's; this skill renders it.
- It is not a place to add sources. The corpus was frozen at phase 3.
- It is not a place to relax the method's limits because prose wants a stronger sentence.
- It is not an internal memo. The manuscript stands alone for a reader who knows nothing of the workflow that produced it - meta commentary, phase vocabulary, and internal-artifact names belong to `draft-self-review.md`, not the paper.

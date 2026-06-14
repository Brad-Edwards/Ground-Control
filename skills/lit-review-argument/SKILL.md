---
name: lit-review-argument
description: Phase 4 of the literature-review workflow. Takes the phase-3 evidence base and builds the paper's argument architecture - a section outline plus the discussion's load-bearing arguments reconstructed in Argdown and validated with the Argdown CLI. User-invoked after the phase-3 evidence base has been reviewed; it does not auto-chain. The output is the paper's argument structure, not finished prose.
---

# # Lit-Review Argument Architecture (Phase 4)

This skill turns the phase-3 evidence base into the paper's argument architecture: a section outline, and the discussion's load-bearing arguments reconstructed in Argdown. It does not write prose - phase 5 (`lit-review-draft`) does that.

Phase 3 produced the evidence base - what the review found. This skill decides *what the paper argues, in what order, and on what support* - and makes every argumentative step explicit and checkable before a word of prose is committed.

## What this skill counters

Each item earns its place by what goes wrong when a paper is drafted straight from an evidence base with no argument layer in between.

- **Asserting what the evidence base does not carry.** Phase 3's hallucination danger returns in argument form: the outline, or an Argdown premise, states a finding the charted corpus does not support. Counter: every outline node and every Argdown statement traces to a specific `synthesis.md` section, `evidence-matrix.md` cell, or `decisions.md` entry, recorded as an `{evidence: "..."}` tag. An ungrounded statement fails the grounding pass.
- **Argumentative leaps.** The discussion's conclusion does not follow from its stated premises. Counter: each load-bearing argument is reconstructed as an explicit premise–conclusion structure (PCS); a step that does not hold is visible as a gap between the numbered premises and the conclusion, or as a hidden premise that has to be written down.
- **Importing a generic prior.** The agent builds the argument from "what a paper like this usually argues" instead of from this review's findings. Counter: the argument spine is the paper's own primary claim and research questions answered against `synthesis.md`; nothing enters the map that does not trace to this review's evidence base.
- **Over-claiming past the method's limits.** The method has limits the synthesis already declared (for a scoping review: no severity ranking, no prevalence claim, no causal claim). Counter: a method-limits pass - any Argdown conclusion asserting more than the method licenses is a violation (`synthesis.md` method-limits section; `requirements.md` §7).
- **Taxonomy-validity objections left implicit.** A taxonomy paper invites objections about category separability, unit-of-analysis stability, source-role bias, ending-condition satisfaction, and evaluation utility. Counter: for taxonomy-development evidence bases, model those as explicit objections or limitations before drafting.
- **Drifting from the paper's declared claim.** The outline argues something other than the stanza's primary claim and RQs, or quietly argues a declared non-claim. Counter: the outline's spine is the primary claim and the RQs; each declared non-claim gets an explicit "not argued here" placement.
- **Unexamined objections.** The discussion ignores objections the protocol already identified - unresolved gate tensions, declared validity threats, the cumulative-deviation question. Counter: known objections enter the Argdown map as explicit attacks; the paper's response is the support attached to each. A discussion with no modelled objections is incomplete, not clean.
- **Structurally loose argument maps.** The Argdown does not parse; a premise is asserted from nowhere; a support argument is wired in but never reconstructed; an objection is raised and left hanging; a statement supports itself. Counter: `validate-argument-map.sh` - it parses the map with `pyargdown` (bundled by `argdown-feedback`) and runs four project-specific structural handlers that flag ungrounded premises (A), unreconstructed support arguments (B), unanswered objections (C), and circular support (D). When an argument carries `{formalization: ...}` metadata on every PCS member, `--logreco` adds a Z3-backed first-order-logic validity check from `argdown_feedback.verifiers.core.logreco_handler` - the material-validity step that used to be agent-only. Premise truth against the evidence base - whether each `{evidence: ...}` pointer genuinely says what the premise claims - remains the agent's pass; mechanical checks do not establish it.

## The discipline (what the output IS)

An argument architecture: a section outline plus a validated Argdown argument map, critiqueable by a reviewer who has read the evidence base and the paper's stanza.

Allowed in the output: outline nodes and arguments traceable to the evidence base; known objections modelled as attacks; the method limits stated as explicit boundary statements; the paper's declared non-claims placed as exclusions.

Not allowed: claims not traceable to the charted corpus; prose paragraphs; findings the synthesis does not contain; severity / prevalence / causal conclusions a descriptive review cannot support; citations to sources outside the phase-3 included set; relitigation of the synthesis.

## Argdown conventions for this skill

- Reconstruct each load-bearing argument as a **premise–conclusion structure**: numbered premises, an inference line, then the conclusion.
- Every premise must be grounded one of two ways: it carries evidence provenance as Argdown metadata - `{evidence: "synthesis.md §3.3"}` - *or* its proposition is itself the conclusion of another argument in the map (grounded by derivation). A premise that is neither is ungrounded, and `validate-argument-map.sh` flags it (rule A).
- A premise that is deliberately the paper's own analytic frame rather than an evidence-base finding is tagged `{evidence: "paper-contribution: ..."}`. The checker lists these separately - they are not failures, but they must be defended in the paper's prose, not cited.
- Connect nodes with Argdown relations: `<+` / `<-` are incoming support / attack; `+>` / `->` are outgoing.
- Model an objection as a statement that **attacks** a load-bearing claim (or, if the objector's reasoning is itself worth reconstructing, a full argument). Model the paper's reply as a **counter-attack on the objection**. Every modelled objection must have a reply - a rebuttal, or an explicit concession - or `validate-argument-map.sh` flags it unanswered (rule C).
- One file: `argument-map.argdown` in the workspace.

## Workflow

1. **Read the inputs.** From the workspace: `synthesis.md`, `evidence-matrix.md`, `charting-data.md`, `coding-scheme.md`, `decisions.md`, `requirements.md`, `lit-review-plan.md`. And the paper's stanza in `program/` (primary claim, RQs, non-claims, venue posture, evidence-needed). If `synthesis.md` does not exist, phase 3 has not run - stop and run `lit-review-search` first.

2. **Fix the spine.** The primary claim (verbatim from the stanza / `requirements.md` §2) and the research questions are the argument's spine. List the declared non-claims - these are explicit exclusions the argument must not drift into.

3. **Build the outline.** Write `paper-outline.md`: the section structure, tracing the method's reporting standard (for a scoping review, PRISMA-ScR-shaped - background/rationale, objectives, methods, results, discussion, limitations, conclusions; for another method, trace the contract's equivalent). Each node names what it argues or reports and cites the evidence-base source(s) it draws on. Mark the **load-bearing discussion nodes** - the ones that make an argumentative claim, not just report a count - for Argdown reconstruction.

   For taxonomy-development papers, the outline must foreground the taxonomy artifact and its method-bounded contribution: construction rationale, final dimensions/characteristics, evaluation, utility, limits, and non-claims. Do not present a taxonomy paper as a flat scoping-review results catalogue unless the phase-1 contract chose a review method for that role.

4. **Reconstruct the arguments in Argdown.** In `argument-map.argdown`, reconstruct each load-bearing discussion node as a PCS. At minimum: a central argument for the primary claim; one argument per research question; one per implication the synthesis draws; and a method-adequacy argument that confronts the cumulative-deviation question. Every premise carries its `{evidence: ...}` tag. Connect the arguments with support relations. Model every objection the protocol already identified - declared validity threats and unresolved gate tensions in `decisions.md` - as an explicit attack, with the paper's response attached.

5. **Validate.** Run `validate-argument-map.sh` (in this skill's base directory). It parses the map with `pyargdown` and runs the four structural checks (A grounding, B unreconstructed support, C unanswered objection, D circular support) from `skills/lit-review-argument/handlers/`. On first invocation it bootstraps a script-local venv with the pinned `argdown-feedback` (citation-MCP pattern); subsequent runs reuse it offline. Fix every failure it reports.

   When a PCS carries `{formalization: "<NLTK FOL>", declarations: {…}}` metadata on every member, pass `--logreco` and the wrapper additionally runs `argdown_feedback.verifiers.core.logreco_handler.LogRecoCompositeHandler` - Z3 certifies (or refutes) global and local deductive validity, premise relevance, and premise consistency. Without `--logreco`, or in the absence of formalization metadata, this material-validity step remains the agent's pass: read each PCS and confirm the premises actually entail the conclusion. Also check that each `{evidence: ...}` pointer genuinely says what the premise claims, and that no conclusion exceeds the method's declared limits. The script's "OK" means structurally (and, with `--logreco`, formally) sound - not that the premises are true.

6. **Self-review.** Apply the angles in `argument-self-review.md`.

7. **Record decisions.** Extend `decisions.md` with argument-phase decisions: what was promoted to a load-bearing argument, which objections were modelled and how each was answered, any claim deliberately scoped down to stay inside the method's limits.

**Gates** are raised as in earlier phases: a decision that is genuinely the user's (a claim the evidence base will not carry; an objection with no good answer) is surfaced immediately with a recommendation and reasoning, resolved, logged, and the work continues. No end-of-document backlog.

This skill **does not auto-chain into phase 5.** The argument architecture is a checkpoint: the user reviews `paper-outline.md` and `argument-map.argdown`, then invokes `lit-review-draft`.

## Output structure

In the workspace:

- **`paper-outline.md`** - the section structure; each node cites the evidence-base source it draws on; load-bearing discussion nodes marked.
- **`argument-map.argdown`** - the discussion's load-bearing arguments as validated Argdown PCS, with grounded premises and modelled objections.
- **`argument-self-review.md`** - the self-review.
- **`decisions.md`** - extended.

The paper's prose is *not* an output of this skill.

## Self-review angles

### Grounding angles

- **Every-node-grounded pass.** Does every outline node and every Argdown statement trace to a specific evidence-base source? Any premise with no `{evidence: ...}` tag is ungrounded - ground it or cut it.
- **Argument-completeness pass.** Does every load-bearing discussion node have a reconstructed PCS? Any conclusion resting on a premise that was never stated?
- **Objection pass.** Is every protocol-identified objection - each declared validity threat, each unresolved gate tension - modelled as an attack with a response or an explicit concession? An unmodelled objection is a discussion gap.
- **Spine-fidelity pass.** Does the outline argue the stanza's primary claim and RQs, and only those? Has a declared non-claim crept in as an argument?

### Sharpness angles

- **Inference pass.** For each PCS, do the premises actually entail the conclusion, or is there a hidden premise doing silent work? Write the hidden premise down - then it is either defensible or the leap is exposed.
- **Method-limits pass.** Does any conclusion assert severity, prevalence, causation, or anything else the method's declared limits forbid?
- **Taxonomy-validity pass.** For taxonomy-development papers, are the strongest objections modelled: category overlap, unstable unit of analysis, unsupported meta-characteristic, weak ending-condition evidence, source-selection bias, and overclaiming recurrence or coverage from background/framing sources?
- **Claim-term pass.** Does the argument honour the operational definitions set in phase 2 (for example what "recurring" was defined to require)? An argument that claims more than the definition licenses is over-reach.
- **Load-bearing-objection pass.** Is the *strongest* objection to the primary claim actually modelled - or only the weak ones that are easy to rebut?
- **Contribution-shape pass.** Does the architecture foreground the paper's strongest, most original claim - or does it lay out every research question and finding co-equally, as a catalogue or a flat list? A review still has a *point*. The most original finding should be the spine the outline and the argument map are built around, not item N of a parallel series. If the sharpest claim is buried - the last of three co-equal results sections, one of six co-equal implications - the architecture is competent but not pointed. Re-spine it: make the lead the lead, and re-role the rest as the machinery that earns it. But re-spining can change what the paper claims relative to its stanza and its place on the program's claim ladder - and if it does, that is a stanza / claim-ladder reconciliation, not a phase-4 call: raise it as a gate and settle it with the user *before* restructuring, never as a side-effect inside the restructure. *(Observed: a fourteen-code instrument-problem catalogue presented as the contribution, with the one genuine insight - that pre-backend addressability is a conditional, (problem, mitigation)-pair structure - buried as the third research question and one of six co-equal implications.)*

## What this skill is NOT

- It is not phase 3. If `synthesis.md` does not exist, stop and run `lit-review-search`.
- It is not phase 5. It builds the argument architecture; it does not write prose.
- It is not a place to introduce findings. Every claim traces to the phase-3 evidence base; a claim the evidence base will not carry is surfaced to the user, not argued anyway.
- It is not a place to relitigate the synthesis. If the synthesis is genuinely wrong, stop and surface that - do not quietly argue around it.

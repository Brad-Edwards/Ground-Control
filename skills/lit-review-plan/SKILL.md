---
name: lit-review-plan
description: Phase 2 of the literature-review workflow. Fills the phase-1 methodology+requirements contract with domain content to produce an executable literature-review plan. Normally chained from `lit-review` automatically - the user invokes the workflow once and this skill takes over at the end of phase 1. Can also be invoked standalone when phase 1 was completed in a previous session (a `requirements.md` already exists in the workspace). The output is a lit-review plan an executor can run; it does not run the search.
---

# # Lit-Review Plan (Phase 2)

This skill's job is one thing: take the phase-1 methodology+requirements contract (`requirements.md`) and fill every requirement with domain content for the specific paper, producing an executable literature-review plan.

The plan is the protocol the search phase (phase 3) will run. It does not run the search itself.

The phase-1 skill (`lit-review`) decided the method and listed the requirements. This skill decides *how this paper meets each requirement*.

## What this skill counters

Each item earns its place by what we have actually seen go wrong, in phase 1 or in earlier walkthroughs.

- **Re-deriving phase 1.** You re-argue the method choice, re-list the requirements, re-justify the rejections. Counter: phase 1's `requirements.md` is the contract. Read it. Reference it. Do not relitigate it. If a phase-1 decision turns out to be wrong, stop and surface that to the user.
- **Procedural invention.** You fill the search-decision plan (databases, terms, time span, language) with content the agent made up. Counter: every concrete procedure - specific databases, specific date ranges, specific search strings - must come from (a) the paper context (RQs, claim, venue posture, dependencies, current basis named in the program stanza), (b) the user via a gate, or (c) what the methodology source explicitly authorises. Where none of those supply it, surface as a user gate. Do not invent.
- **Coding-scheme / charting hallucination.** For scoping or critical or narrative reviews, the data-charting fields and any coding scheme can be domain-flavoured. You invent codes from training memory. Counter: the charting form's fields can be drafted, but any *coded categories* should be declared as either (i) taken from a citable source the agent has read via `mcp__citation__cite_resolve`, (ii) supplied by the user, or (iii) **emergent** - pilot reading on a small sample (typically 5–10 included sources) refines the codes, per Levac §Stage 4 recommendation 1c. Mark which.
- **Source-finding hallucination.** When the plan needs to name seed papers, exemplars, or citable evidence (for example, for the rationale in PRISMA-ScR item 3, or to justify a date range), you cite from memory. Counter: every cited paper must come from `mcp__citation__cite_resolve` or a Zotero record you have opened. Use `mcp__citation__cite_search` (OpenAlex / Crossref) or `mcp__citation__zotero_search` (the user's library) when you need to find a candidate. Never type a citation from memory.
- **Search-result-shaped sentences.** "The literature shows", "most papers", "we identified N studies" - phase 2 is the protocol, not the search. Counter: the plan describes *what will be done*; it does not summarise what the search will find. If a section asserts what the literature contains, it has drifted into phase 3.
- **Drifting from the requirements contract.** Phase 1 named requirements; you skip some or merge them into vagueness. Counter: the plan's organisation traces the contract one-to-one. Every requirement is answered or its deviation is declared with rationale.
- **Forgetting non-claims and method limits.** The paper context names non-claims; the methodology source names method limits. The plan must respect both. Counter: dedicated sections in the plan for the paper's declared non-claims (so the protocol does not over-collect for them) and the method's declared limits.
- **Taxonomy source-role collapse.** A taxonomy paper uses different source roles: construction/stress-test corpus, background/framing literature, methodology literature, and validation/evaluation material. You flatten them into one "literature" bucket, then background sources become fake evidence for recurrence or coverage. Counter: for taxonomy-development contracts, assign every source role explicitly and state which claims each role can and cannot support.
- **Punting user gates as "declared defaults" at the end of the plan.** *Observed failure.* You draft the whole plan with defaults you picked silently, then list 9 "open user gates" in a §11 backlog at the end of the document. That makes the user read the whole plan to find what they are being asked, and provides no audit trail of what was decided. Counter: bring each gate up *as it arises during drafting*, one at a time, with a recommendation and reasoning. Discuss. Resolve. Log to `decisions.md`. Then continue. By the end of drafting there should be no open-gates backlog - only `deferred` gates that the user explicitly chose to set later (for example, "pilot will set", "decide at write-up"), each named in the plan with that label.

## The discipline (what the output IS)

The output is a literature-review plan that fills the phase-1 contract for a specific paper. Critiqueable against the contract by a reviewer who has read both `requirements.md` and the paper's stanza.

Allowed in the output:

- Concrete domain content where the paper context supplies it (RQs verbatim, declared non-claims, evidence-needed deliverables, venue posture, dependencies, current basis).
- Concrete decisions where the methodology source authorises them and the agent can justify from sources read (for example, reporting standard = PRISMA-ScR, since the contract names it).
- User-resolved decisions where genuine user judgement is required.
- Cited papers and seed sources only when obtained via the citation MCP or read from Zotero.

Not allowed in the output:

- Renegotiation of the methodology or the requirements contract. If the contract is wrong, stop and surface it.
- Specific databases, dates, depths, or caps that the agent invented with no rationale.
- Coded category lists derived from agent training memory.
- Characterisations of specific literature ("X paper does Y", "the literature shows Z") - search-phase content.
- Citations not grounded in the citation MCP or Zotero.

## Workflow

1. **Read the contract.** Locate the workspace's `requirements.md` (from phase 1). If it does not exist, the user has not run phase 1 yet - stop and run that first, or ask the user to.

2. **Read the paper context.** Re-read the paper's stanza in `program/`. Note the working title, primary claim, RQs, current basis, evidence-needed deliverables, non-claims, venue posture, dependencies. These are inputs the plan must reflect.

3. **For each requirement in the contract, draft the answer.** Work through the contract in order, requirement by requirement. For each, decide where the answer comes from:

   - *Direct from the paper context* (for example, RQs, declared non-claims, evidence-needed) - transcribe.
   - *Direct from the methodology source* (for example, PRISMA-ScR is the reporting standard for scoping) - restate with citation.
   - *Defensible domain decision* (for example, a search-database choice with a clear justification from the paper's domain) - make it, justify it, cite supporting evidence via the citation MCP if any is named.
   - *User judgement* - surface as a user gate; do not invent.
   - *Pilot/emergent* - declare it explicitly (for example, "coding categories emerge from pilot reading of the first 5–10 included sources per Levac §Stage 4 recommendation 1c").

4. **Resolve user gates one at a time, as they arise during drafting, with recommendation + reasoning.** When you hit a section that depends on a user-decision (reviewer count, consultation feasibility, critical-appraisal, database list, date range, workshop target, scope feasibility, taxonomy meta-characteristic, unit of analysis, ending-condition strictness, evaluation audience, anything similar), stop drafting that section, bring the gate to the user *with your recommended option and the reasoning behind it*, discuss if they push back, settle the decision, write the resolution to `decisions.md` immediately with rationale, then continue drafting. Use `AskUserQuestion` for structured choices; use a direct chat question when the gate benefits from open discussion. The final plan should contain no end-of-document "open user gates" backlog - only `deferred` decisions the user explicitly chose to set later (for example, "pilot will set", "decide at write-up"), labelled inline in the relevant section.

5. **Cite via MCP.** Any specific paper, dataset, standard, or source cited in the plan - for a date-range justification, a venue-posture rationale, an "existing basis" reference, or anything else - must come from `mcp__citation__cite_resolve` (canonical metadata via DOI / arXiv / PMID), `mcp__citation__cite_search` (when you have title or keywords but no identifier), or a Zotero record you have opened. Never from training memory. Tags or labels referring to such sources without prior MCP grounding fail the citation-grounding self-review pass.

6. **Self-review.** Apply the angles below in `self-review.md`.

7. **`decisions.md` is required, not optional.** Every non-obvious choice made during this phase - every user-gate resolution, every default declared with confirmation, every deferral - is logged with: the gate or question, the agent's recommendation and reasoning, what was discussed, what the user decided, the requirement it answers. This is the audit trail. If no non-obvious choices were made, the file may be brief or skipped - but if any gate was raised, `decisions.md` must exist.

8. **Chain into phase 3.** Once `lit-review-plan.md` and `self-review.md` are written, the self-review passes (discipline and sharpness) applied, and `decisions.md` recorded, **immediately invoke the `lit-review-search` skill via the Skill tool** with the same paper_id (or workspace path). The user does not re-invoke; the lit-review workflow runs end-to-end through the search. Phase 2's plan is the input to phase 3; phase 3 raises any search-phase gates with recommendation + reasoning as they arise.

## Output structure

Write `lit-review-plan.md` in the workspace. The structure traces the phase-1 contract so an executor can verify coverage by section.

Required top-matter:
- **Paper:** working title from the program stanza.
- **Methodology** (link to phase 1): name of method, link/reference to `requirements.md`.
- **Status:** which requirements are filled, which are open user gates, which are deferred to pilot.

Body sections, one per requirement group in the contract. For a scoping protocol this typically means:

1. **Research question and scope** (R-Stage1) - RQ verbatim, scope of inquiry with PCC-or-equivalent framing, scoping-study purpose(s), envisioned outcome, rationale.
2. **Sources and search plan** (R-Stage2 / PRISMA-ScR items 6, 7, 8) - eligibility criteria with rationale, named information sources, search strategy for at least one database with limits.
3. **Study selection** (R-Stage3 / PRISMA-ScR item 9) - screening procedure, reviewer arrangement, disagreement handling, iterative process.
4. **Charting** (R-Stage4 / PRISMA-ScR items 10, 11) - data-charting form (field list with rationale per field), pilot plan, codes' provenance (citable / user-supplied / emergent).
5. **Synthesis and reporting** (R-Stage5 / PRISMA-ScR items 14, 17–21, 24) - numerical summary plan, thematic-analysis plan, envisioned-outcome plan, implications-for-practice plan.
6. **Consultation** (R-Stage6) - included with stakeholders + integration plan, OR declared absent with limitation.
7. **Critical appraisal decision** (PRISMA-ScR items 12, 19) - yes/no + rationale; scoping default is no.
8. **Funding and protocol registration** (PRISMA-ScR items 5, 27) - declaration where applicable.
9. **Declared non-claims** (from paper context) - what the lit review will *not* be used to support.
10. **Method-choice limitations** (from contract §7, transcribed) - inherent limits acknowledged.
11. **Deferred decisions** - only decisions the user explicitly chose to defer (for example, "pilot will set", "set at write-up"). Each named in its relevant body section with the `deferred` label. If no deferrals exist, this section is omitted. **There should be no "open user gates" backlog here** - gates must be resolved as they arise during drafting (see Workflow step 4).

Drop irrelevant sections for non-scoping methods (for example, a critical review wouldn't have R-Stage6 consultation). The skeleton above is for scoping; for other methods, trace the contract's actual sections.

For a `taxonomy_development` contract, the plan normally needs sections for:

1. **Taxonomy contribution and limits** - the taxonomy's purpose, intended contribution, declared non-claims, and the claims the method can and cannot support.
2. **Meta-characteristic and unit of analysis** - the taxonomy's organizing principle and what counts as one classified object.
3. **Source roles** - separate procedures for taxonomy-instance corpus, background/framing literature, methodology literature, and validation/evaluation material. Background/framing sources are not evidence for recurrence, prevalence, coverage, or exhaustiveness unless the plan explicitly assigns that role and explains the supporting design.
4. **Starting concepts** - any initial dimensions, categories, or seed concepts, labelled `citable`, `user-supplied`, `paper-context`, `empirical`, or `author-constructed`.
5. **Construction procedure** - conceptual-to-empirical, empirical-to-conceptual, or iterative steps, with how candidate dimensions and characteristics are generated and checked.
6. **Iteration log protocol** - what changes are recorded per iteration, including split/merge/add/drop rationale and source or evaluation trigger.
7. **Ending conditions** - objective and subjective ending conditions from the methodology contract, filled only as far as the paper context and user decisions support.
8. **Evaluation plan** - criteria such as concision, robustness, comprehensiveness, extensibility, explanatory value, and utility when the contract names them, plus who or what supplies evaluation evidence.
9. **Validity threats** - source-selection bias, author-imposed categories, coding subjectivity, category overlap, unstable unit of analysis, and overclaiming coverage or recurrence.
10. **Hybrid integration** - if a review method is also used, which outputs feed taxonomy construction/evaluation and which claims remain out of scope.

`decisions.md` is required (see Workflow step 7) - log every user-gate resolution with recommendation, reasoning, discussion, and the settled rationale.

## Self-review angles

Two kinds. **Discipline angles** check that the plan stays inside its lane (no invention, no leakage, contract fidelity). **Sharpness angles** check that the plan is a *good* protocol, not merely a disciplined one - a disciplined plan can still be analytically loose, and that looseness has been observed repeatedly (the "competent but not tight" pattern). Apply both sets.

### Discipline angles

- **Requirement-coverage pass.** For every requirement in phase 1's `requirements.md`, has the plan answered it (filled / pending user gate / explicit deviation with rationale)? Or has a requirement been skipped, paraphrased, or merged into vagueness?
- **Procedural-invention pass.** For each concrete procedure named (specific database, date range, search string, coding category, reviewer count, etc.), can you trace where it came from - paper context, source, user, pilot, or invention? Anything sourced as "invention" must move to a user gate or be dropped.
- **Coding-scheme-provenance pass.** Any coded category, charting-form field, or inclusion criterion: is it labelled `citable` (with source), `user-supplied`, `paper-context` (from stanza), or `emergent` (pilot-driven, per Levac)? Unlabelled categories are unsupported assumptions.
- **Domain-overreach pass.** Did any sentence in the plan summarise or characterise the literature, name specific competitor papers, or say "we identified" / "the literature shows" / "most studies"? Those are search-phase output. Remove.
- **Method-limits-respected pass.** Does the plan respect the limits the methodology source named (transcribed in contract §7)? E.g., no quality-weighted gap claims in a scoping plan, no exhaustive-coverage claims, no meta-analytic synthesis.
- **Citation-grounding pass.** For every specific paper, dataset, or standard cited in the plan, was the citation obtained from `mcp__citation__cite_resolve`, `mcp__citation__cite_search`, or a Zotero record you opened - never typed from training memory? Any unverified citation must be removed or grounded.
- **Contract-fidelity pass.** Compared to phase 1's `requirements.md`: has the plan stayed within the contract, or has it added requirements the contract did not impose, or skipped requirements the contract did impose?

### Sharpness angles

Each earns its place against an observed failure: across multiple walkthroughs the skill reliably produced a *disciplined* plan and reliably did *not* produce a *tight* one. These angles target that.

- **Claim-term operationalisation pass.** For each load-bearing term in the paper's primary claim and research questions (the words the paper's contribution actually turns on), does the plan either give an operational definition / evidence threshold, or surface it as a user gate / explicit `deferred` decision? A claim term the plan neither operationalises nor flags is a silent gap - the most damaging kind, because the search and synthesis proceed without knowing what would count as evidence for the paper's own claim. *(Observed: a paper claiming problems "recur" with no definition of what recurrence requires - appears in ≥N sources? across ≥M streams?)*
- **Classification-separability pass** (when the plan builds a coding scheme, classification facets, or a charting taxonomy). Are the top-level categories mutually separable, or do some plausibly overlap? Overlapping top-level categories produce ambiguous charting and uninterpretable cells. If overlap is plausible, the plan must either justify the overlap, commit to a pilot check that the categories are separable, or flag it as a threat to validity. Do not silently pass a coding scheme whose top-level structure has not been examined for separability. *(Observed: an 8-dimension starter scheme with several plausibly-overlapping dimensions, never examined.)*
- **Taxonomy-source-role pass** (for taxonomy development). Are taxonomy-instance corpus sources, background/framing sources, methodology sources, and validation/evaluation material separated? Does the plan explicitly prevent background/framing sources from supporting recurrence, prevalence, coverage, or exhaustiveness claims unless they were assigned an evidence role?
- **RQ-dependency pass.** For each research question, trace what the protocol's answer to it depends on - which charting field, which synthesis step, which judgement call. If an RQ's answer hinges on a single judgement-call field or one fragile step, the plan must address *that field's or step's* reliability specifically - a general calibration step is not enough. The most analytically ambitious RQ deserves the most scrutiny here. *(Observed: the hardest of three RQs resting entirely on one uncalibrated high/medium/low single-screener field.)*
- **Cumulative-deviation pass.** Count the plan's declared deviations and omissions relative to the methodology source's ideal (single- vs dual-screener, consultation absent, quality appraisal skipped, search strategy deferred, registration skipped, etc.). Each may be individually defensible. If several stack up, the plan must confront the cumulative question directly - *with all of these declared away, is the method still recognisably itself?* - and either state plainly why the cumulative deviation is acceptable, or reconsider. Do not let defensible-individually accumulate into indefensible-together without comment. *(Observed: four declared deviations from the scoping-review ideal, never addressed as a set.)*

When a sharpness pass surfaces a gap, the fix is the same as for any gate: bring it to the user with a recommendation and reasoning, resolve, log in `decisions.md`. A sharpness gap caught in self-review is a gate that should have been raised during drafting; raise it now rather than shipping the plan with the gap.

## What this skill is NOT

- It is not phase 1 (methodology + requirements). If `requirements.md` does not exist, stop and ask the user to run `lit-review` first.
- It is not the search. After this skill produces `lit-review-plan.md`, the next phase is paper-specific Zotero / online source search executing the plan.
- It is not paper writing. The plan is the protocol; the review prose is later.
- It is not a place to relitigate the method choice. The contract decides the method.
- It is not a place to fabricate procedural detail. Where the answer is genuinely unknown, surface a user gate.

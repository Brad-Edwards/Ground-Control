---
name: lit-review
description: Run the literature-review workflow for a research paper. This skill is the user-facing entry point; internally it executes phase 1 (methodology selection + requirements extraction), chains into phase 2 (`lit-review-plan`, fills the requirements into an executable plan), which chains into phase 3 (`lit-review-search`, runs the search and produces the evidence base). The user invokes this once; the workflow runs end-to-end with gates raised mid-flow as needed. Paper prose is downstream and out of scope.
---

# # Methodology Selection and Requirements Extraction

This skill's job is two things and only these two:

1. **Pick** the methodology that fits this paper's review or taxonomy-construction contribution.
2. **Extract** the formal requirements that methodology imposes on the downstream plan, from the methodology's own primary sources.

The output is a methodology selection plus a requirements specification — the contract the next phase has to satisfy. The next phase, domain-aware literature-review planning, fills the requirements with content. The phase after that is the search.

Without this skill, agents skip ahead. They invent a procedure ("query ACM, IEEE, arXiv"), they paraphrase methodology sources from memory, they hallucinate citations (88 partially hallucinated references in one recent round of drafting), and they produce something that looks like a plan but is not grounded in any methodology's formal requirements. The skill is a forcing function: select method, read the canonical sources, list the requirements, then stop.

## What this skill counters

Each item earns its place by what we have actually seen go wrong.

- **Citation hallucination.** Real and expensive — 88 partially hallucinated references thrown out. Counter: cite only methodology sources you have actually read. Every citation of a methodology source must come from a deterministic tool — `mcp__citation__cite_resolve` for canonical CSL-JSON, or a Zotero record you have opened — never from training memory. The citation MCP exists to make this enforceable; use it.
- **Invented procedural detail.** Real — earlier walkthroughs filled outputs with database lists, date ranges, snowballing depths, and source-set caps the agent invented to fill fields. None of it was grounded. Counter: this skill produces *requirements the next phase must meet*, not the answers. If the methodology source does not name a specific database, neither does this skill's output.
- **Domain content in a methodology output.** Real — earlier walkthroughs included concept definitions, scope narrowings, claim categories, and named systems that belong to the domain-aware planning phase. Counter: this skill's output is the methodology choice and the requirements that the methodology imposes. Domain content goes in the next phase.
- **Imported framing without provenance.** Real — a four-tier taxonomy used as the spine of a critique with no source given. Counter: every user- or paper-supplied stance, taxonomy, or framework that affects method choice is labeled `citable` (with source) or `paper-contribution` (the paper itself must defend it). Concept definitions the skill does not introduce — concept definitions are the next phase's work.
- **Proceeding without source grounding.** Real — earlier walkthroughs proceeded with partial source coverage when sources were unavailable. Counter: if any primary source the catalog lists for the chosen method cannot be obtained, fail out. Do not produce a requirements spec grounded in partial sources.

## The discipline (what the output IS)

The output is a methodology selection and a requirements specification. Critiqueable on its own methodological merits by a reviewer who does not know the paper's field.

Allowed in the output:
- The paper's research questions and claims, transcribed verbatim from user / paper context.
- The chosen methodology and justification against the rejected alternatives.
- The primary sources for the chosen method, with citations grounded in actual reading.
- The formal requirements the methodology imposes, extracted from those sources, in the source's own terms.
- User gates that materially affect method choice.
- For taxonomy-development papers, the source-role distinctions the chosen method requires: taxonomy-instance corpus, background/framing literature, methodology literature, and validation/evaluation material. These are role requirements, not domain answers.

Not allowed in the output:
- Concept definitions the source does not supply. Those are next-phase.
- Search procedures (databases, terms, date ranges, depths, caps). Those are next-phase.
- Inclusion / exclusion criteria as logic. Next-phase.
- Domain narrowings or sub-area weightings. Next-phase.
- Characterisations of specific literature. Next-phase, then search-phase.
- Anticipated synthesis dimensions, named claim categories, charting-form fields. Next-phase.

## Workflow

1. **Get paper context.** If a paper ID is supplied, read the relevant stream document in the workspace's `program/` directory. If only a question or topic is supplied, restate it back to the user and confirm before proceeding. Names of literature anchors in the program's framing are context for you, not protocol content for the output.

2. **Pick the method.** Read `methodology/catalog.yaml` to see which methods exist and which primary sources back them.

   When the paper's primary contribution is a taxonomy, classification scheme, typology, framework, design-space analysis, or category system, explicitly consider `taxonomy_development` before defaulting to scoping or mapping. Taxonomy development is the right fit when the plan must construct, iterate, evaluate, and bound a taxonomy. Scoping or mapping is the right fit only when the primary contribution is a literature-review evidence map rather than the taxonomy artifact itself.

   Candidate outcomes include taxonomy development, scoping review, systematic mapping review, systematic review, critical/integrative review, targeted related-work review, or a hybrid. A hybrid is allowed only when the roles of each component are separated: what the taxonomy method constructs/evaluates, what any review method searches/charts/synthesises, and which claims each component can support. Do not let a scoping corpus silently carry taxonomy-construction or taxonomy-validity claims.

3. **Read every primary source for the chosen method.** Open every Zotero item the catalog lists for the chosen method — all of them, not the first one, not your preference. The catalog defines the canonical-source set. Partial coverage is not method-formal grounding.

   For each listed Zotero key, in order:

   a. **Read the Zotero attachment if present.** Call `mcp__citation__cite_resolve` with `doi:<the source's DOI>` to confirm the canonical metadata, then fetch the attached PDF from Zotero (env vars `PERSONAL_ZOTERO_ID` / `PERSONAL_ZOTERO_KEY` are set). Read it.

   b. **If no PDF is attached but the DOI is reachable:** call `mcp__citation__oa_locate` on the DOI. If an OA location exists, download to a local working file (a publisher-direct URL is preferred) and read it. **Use `mcp__citation__zotero_attach_pdf` to attach the OA PDF to the existing Zotero item** — this is the OA-policy-gated path; arbitrary URLs are rejected. If the user has a preference about whether to attach, ask; default is attach so future runs do not have to refetch.

   c. **If the catalog Zotero key is missing from the library entirely (stale catalog):** the source still has to be reached. Call `mcp__citation__cite_resolve` to obtain the canonical citation, `mcp__citation__oa_locate` to identify any OA copy, and download from a publisher-direct or institutional-repository OA URL to a local working file. Alert the user that the catalog points to a key not in the library; name the source you obtained, where it came from, and ask whether to (a) add the source to Zotero via `mcp__citation__zotero_add`, (b) update the catalog with a different Zotero key, or (c) keep the local copy only. **Do not call `mcp__citation__zotero_add` without the user's permission** — Zotero state is theirs to manage.

   **If any required source for the chosen method cannot be obtained through these deterministic paths, fail out.** Do not produce a requirements spec. Do not paraphrase the missing source from memory. Stop the workflow and report: which source was needed, which retrieval paths were tried (Zotero attachment, `oa_locate`, OA download), what failed, and what is required from the user to unblock — typically (a) provide a local PDF, (b) add the source to Zotero, or (c) point to a substitute canonical source that is reachable.

4. **Extract the formal requirements.** For each formal requirement the methodology sources impose on a literature-review plan, list it. Use the sources' own terms. Cite the source and the section where the requirement is named. Where the sources disagree or where one source extends another (e.g., Levac extending Arksey & O'Malley), record that explicitly.

   A requirement names *what the lit-review plan must specify* — not the answer. *"The plan must define the key concepts and the target population"* is a requirement (extracted from a scoping source). *"Define calibration as X"* is not — that is the next-phase decision.

   For taxonomy development, extract requirements for the taxonomy-development plan in the same way: purpose and meta-characteristic, unit of analysis, starting concepts and their provenance, conceptual-to-empirical / empirical-to-conceptual / iterative construction steps, iteration documentation, ending conditions, evaluation criteria, source-role separation, validity threats, and explicit limits on recurrence, prevalence, coverage, or exhaustiveness claims. Do not fill any of those with domain content in phase 1.

5. **Self-review.** Apply the angles below in `self-review.md`.

6. **Record decisions.** Write `decisions.md` for every non-obvious choice made during this phase — at minimum the method choice and any user-gate resolutions. For each entry: the gate / decision, the agent's recommendation with reasoning, what the user decided (if asked), the rationale that was settled on. This is the audit trail; it is not optional.

7. **Chain into phase 2.** Once `requirements.md`, `self-review.md`, and `decisions.md` are written and phase 1's self-review passes have been applied, **immediately invoke the `lit-review-plan` skill via the Skill tool** with the same paper_id (or workspace path). The user does not re-invoke; the lit-review workflow runs end-to-end. Phase 1's output is the input to phase 2; phase 2 raises any user-decision gates with recommendation + reasoning as they arise.

**When a gate must be resolved by the user, bring it up immediately, with a recommendation and reasoning.** Do not collect gates into an end-of-document backlog. Do not punt as "declared defaults". Use `AskUserQuestion` for structured choices, or a direct chat question for ones that benefit from discussion. State your recommended option, state why, name the trade-offs, ask the user to confirm or override. When resolved, log the decision in `decisions.md`, then continue. The phase-1 output's §6 "user gates affecting method choice" lists forward-looking decisions for phase 2 to surface; it is *not* a list of phase-1 gates the agent left open.

## Output structure

Write `requirements.md` in the workspace. Sections:

1. **Research questions** — verbatim from user / paper context.
2. **Candidate claims** — verbatim from user / paper context, if supplied. Otherwise note as a next-phase input.
3. **Chosen methodology** — named method + justification against rejected alternatives + explicit method limits as the sources name them. For hybrids, name each component's role and supported claim types separately.
4. **Primary sources read** — every source the catalog lists for the method, with how it was obtained (Zotero attachment, OA retrieval) and any provenance notes.
5. **Requirements specification** — every formal requirement the sources impose, with citation to the source(s) and section(s). Grouped by methodology-source stage / phase where the source organizes its requirements that way.
6. **User gates affecting method choice** — decisions the user must make that determine which method fits or how the requirements get interpreted at the boundary. Not next-phase domain decisions.
7. **Method-choice limitations declared up front** — what the chosen method, even when executed perfectly, cannot establish.

Optional `decisions.md` for non-obvious choices.

## Self-review angles

Methodology-level. The reviewer in your head does not know the paper's field.

- **Source-completeness pass.** Did I read every primary source the catalog lists for the chosen method, or did I shortcut on one? If any required source was not obtained, I should have failed out, not proceeded.
- **Requirements-coverage pass.** For each formal requirement the methodology sources name, has the output captured it with citation? Or has a requirement been skipped, paraphrased, or merged with another in a way that loses what the source actually said?
- **Procedural-invention pass.** Does the output name any requirement the sources do not actually impose? Invented requirements are as harmful as missing ones — they push fake commitments into the next phase.
- **Domain-leakage pass.** Did any concept definition, scope narrowing, named claim category, named system, characterisation of specific literature, or "what the literature contains" sentence appear in the output? Those belong to the next phase. Remove them.
- **Method-fit pass.** Is the chosen method the right shape for the paper's category, RQs, and (where supplied) claims? Are the rejected alternatives rejected for real reasons, not aesthetic ones? Are the method's limits (what it cannot support) stated honestly?
- **Taxonomy-fit pass.** If the paper proposes a taxonomy, classification scheme, typology, framework, or design-space analysis, did I consider `taxonomy_development` and reject/accept scoping, mapping, or systematic review for source-grounded reasons? If I chose a hybrid, are component roles separated rather than blended?
- **Imported-framing pass.** For each user- or paper-supplied stance, taxonomy, or framework that affects method choice: is it labeled `citable` (with source) or `paper-contribution` (defence plan named)? Unlabeled framings are unsupported assumptions even at the method-selection phase.

## What this skill is NOT

- It is not the lit-review plan. The output is a requirements spec the next phase fills with content.
- It is not the search. Two phases away.
- It is not a place for concept definitions. Those are next-phase decisions about what the methodology's requirements mean for this paper.
- It is not a place for invented procedural details. If the methodology source does not say it, this skill does not say it either.
- It is not paper writing.

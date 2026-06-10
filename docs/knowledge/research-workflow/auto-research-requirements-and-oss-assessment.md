# Auto-Research Requirements and OSS Assessment

Date: 2026-05-26

## Purpose

This note captures requirements for extending Reactor beyond its current methodology-selection, literature-review planning, and source-grounded search workflow into broader literature-based and writing automation.

The question answered here is practical:

> Is there already open-source software that can satisfy all or part of these requirements, or is this a fresh build?

Short answer: this is not a fresh-from-zero build, but it is a fresh integration/orchestration build. Existing OSS covers useful parts of retrieval, RAG, screening, extraction, survey generation, and report writing. I did not find a single OSS system that already provides Reactor's method-aware requirements contract, strict source-state discipline, Zotero/citation grounding, systematic charting, synthesis traceability, and downstream paper-writing workflow as one coherent tool.

## Reactor Baseline

Current Reactor already has a strong, unusual core:

- `reactor-lit-review`: selects an appropriate review/taxonomy methodology and extracts formal requirements from primary methodology sources.
- `reactor-lit-review-plan`: fills those requirements into a domain-aware executable literature-review protocol.
- `reactor-lit-review-search`: executes the protocol through citation-grounded search, screening, full-text reading, charting, and synthesis.
- `citation_mcp`: deterministic citation MCP backed by Crossref, OpenAlex, Unpaywall, Zotero translation-server, and Zotero Web API. It exposes `cite_resolve`, `cite_search`, `cite_forward`, `zotero_search`, `zotero_add`, `oa_locate`, and `zotero_attach_pdf`.
- `methodology/catalog.yaml`: a lookup from methodology key to primary methodology sources. It deliberately contains no prose summaries.

Reactor's most important discipline is not automation volume; it is preventing scientific-looking hallucination. Its two most valuable constraints are:

- Methodology requirements must be extracted from primary methodology sources, not guessed.
- Review sources are either fully in the review, with full text read and charted, or recorded as access gaps. There is no abstract-only charting state.

## Source Base Reviewed

Local Reactor files:

- `.claude/skills/reactor-lit-review/SKILL.md`
- `.claude/skills/reactor-lit-review-plan/SKILL.md`
- `.claude/skills/reactor-lit-review-search/SKILL.md`
- `citation_mcp/server.py`
- `citation_mcp/search.py`
- `citation_mcp/resolve.py`
- `methodology/catalog.yaml`

Representative OSS repos inspected:

- `SakanaAI/AI-Scientist` at `1de1dbc`
- `SamuelSchmidgall/AgentLaboratory` at `d9017d9`
- `Future-House/paper-qa` at `d2c3c69`
- `AutoResearch/autora` at `fc5cc3e`
- `stanford-oval/storm` at `fb951af`
- `snap-stanford/MLAgentBench` at `5d71205`
- `codelion/openevolve` at `80945ed`
- `karpathy/autoresearch` at `228791f`
- `langchain-ai/open_deep_research` at `4b61120`
- `assafelovic/gpt-researcher` at `92bfc03`
- `AutoSurveys/AutoSurvey` at `5e8f389`
- `OpenBMB/AgentCPM` at `4a43561`
- `PouriaRouzrokh/LatteReview` at `ffee2fc`
- `OxRML/AgentSLR` at `3111fcf`
- `JimAchterbergLUMC/OpenExtract` at `db33814`
- `GPT-Laboratory/SLR-automation` at `f0d9ee4`

Primary / project sources checked:

- AI Scientist: https://arxiv.org/abs/2408.06292
- Agent Laboratory: https://arxiv.org/abs/2501.04227
- PaperQA2: https://arxiv.org/abs/2409.13740
- AutoRA JOSS: https://doi.org/10.21105/joss.06839
- STORM: https://doi.org/10.18653/v1/2024.naacl-long.347
- Co-STORM: https://doi.org/10.18653/v1/2024.emnlp-main.554
- MLAgentBench: https://arxiv.org/abs/2310.03302
- Coscientist: https://doi.org/10.1038/s41586-023-06792-0
- Robot Scientist Adam: https://doi.org/10.1126/science.1165620
- Google AI co-scientist: https://research.google/blog/accelerating-scientific-breakthroughs-with-an-ai-co-scientist/
- AlphaEvolve: https://deepmind.google/blog/alphaevolve-a-gemini-powered-coding-agent-for-designing-advanced-algorithms/
- PRISMA-S: https://doi.org/10.1186/s13643-020-01542-z
- PRISMA 2020: https://doi.org/10.1136/bmj.n71
- FAIR principles: https://doi.org/10.1038/sdata.2016.18
- W3C PROV-DM: https://www.w3.org/TR/prov-dm/
- Ten Simple Rules for Reproducible Computational Research: https://doi.org/10.1371/journal.pcbi.1003285

## Top-Level Requirements

R-1. The system shall distinguish methodology selection, protocol planning, source search, screening, charting, synthesis, argument construction, and prose drafting as separate lifecycle stages.

R-2. The system shall never treat model memory as scientific evidence. Claims require source evidence, experiment artifacts, or explicit unsupported/inferred labeling.

R-3. The system shall support autonomous and copilot modes, with configurable human gates at method, protocol, search, synthesis, and writing decisions.

R-4. The system shall maintain a full provenance chain from user goal to methodology source, query, candidate source, full text, charting cell, synthesis claim, argument move, and final prose.

R-5. The system shall treat generated code, browser activity, lab/hardware actions, and external writes as high-risk operations requiring sandboxing and explicit authorization.

R-6. The system shall produce reproducible research artifacts: protocol, search log, source set, exclusions, access gaps, charting data, synthesis, decisions, and final draft inputs.

R-7. The system shall distinguish literature-based work from experiment-running auto-research. Reactor's immediate extension target is literature and writing automation, not wet-lab or compute-discovery automation.

## Functional Requirements

### Intake and Workflow Control

FR-1. Capture research goal, paper context, target contribution type, intended output, autonomy level, allowed tools, privacy constraints, and cost/compute budget.

FR-2. Classify the task as methodology extraction, domain protocol planning, systematic/scoping/mapping search, taxonomy development, related-work generation, evidence synthesis, or paper prose drafting.

FR-3. Maintain an explicit state machine for phases and prevent downstream phases from running when required upstream artifacts are missing.

FR-4. Provide human gates with recommendation and rationale. Gates shall be logged immediately in `decisions.md` or equivalent state.

### Methodology and Protocol

FR-5. Select review methodology from a catalog and explicitly justify rejected alternatives.

FR-6. Read every primary methodology source required by the catalog before producing methodology requirements.

FR-7. Extract formal requirements from methodology sources without filling domain answers into phase-1 output.

FR-8. Produce an executable protocol that traces every requirement to a filled answer, user gate, or explicit deferral.

FR-9. Support method-specific outputs for scoping reviews, systematic reviews, systematic maps, critical/integrative reviews, targeted related work, and taxonomy development.

### Scholarly Search and Source Management

FR-10. Generate, execute, and log search strategies across scholarly APIs and user libraries.

FR-11. Preserve exact queries, dates, databases, filters, limits, and candidate counts in PRISMA-S-compatible form.

FR-12. Resolve every candidate source through deterministic bibliographic services such as DOI, arXiv, PMID, Crossref, OpenAlex, or Zotero.

FR-13. Add every candidate source that enters screening to a durable source store, preferably Zotero collection plus local artifacts.

FR-14. Support backward snowballing from actual reference arrays and forward snowballing from OpenAlex/citation indexes.

FR-15. Record every excluded source with exclusion stage and reason.

### Full Text, Reading, and Charting

FR-16. Locate full text through Zotero attachments, legitimate OA sources, or user-provided PDFs.

FR-17. Enforce the two-state rule: fully-in sources are resolved, stored, full-text read, and charted; access-gap sources are resolved and stored but not charted.

FR-18. Convert PDFs to text/markdown with source location preservation where feasible.

FR-19. Apply charting forms with field-level provenance: source section/page/span, quote or paraphrase, uncertainty, and reviewer/agent identity.

FR-20. Support pilot charting and emergent coding where the methodology allows or requires it.

FR-21. Support multi-reviewer or multi-agent screening and abstraction with disagreement handling.

### Evidence Synthesis

FR-22. Generate numerical summaries only from charted cells.

FR-23. Generate thematic or conceptual synthesis only from charted evidence and declared coding schemes.

FR-24. Produce evidence matrices linking source IDs to charted fields, codes, and synthesis claims.

FR-25. Preserve conflicts and uncertainty rather than flattening contradictory evidence into a single answer.

FR-26. Emit method-limit warnings when a requested claim exceeds what the chosen method can establish.

### Writing Automation

FR-27. Build paper sections only after evidence artifacts exist. Writing from raw search results is prohibited for scholarly claims.

FR-28. Support argument planning: claim, warrant, backing evidence, limitations, counter-evidence, and citation targets.

FR-29. Draft related-work, methodology, review-results, taxonomy, limitations, and appendix prose from the evidence base.

FR-30. Ensure generated prose cites only source IDs present in the evidence database.

FR-31. Detect unsupported citations, uncited claims, citation/source mismatch, and prose claims that overstate charted evidence.

FR-32. Export drafts and source data to Markdown, BibTeX/RIS/CSL-JSON, CSV/JSONL, and optionally Quarto/Pandoc-compatible manuscript formats.

### Review, Evaluation, and Iteration

FR-33. Run automated review against factual grounding, method compliance, synthesis traceability, citation integrity, and paper-argument coherence.

FR-34. Support human review comments and resolution tracking.

FR-35. Provide benchmark/evaluation harnesses for retrieval recall, screening agreement, extraction accuracy, citation validity, and writing groundedness.

FR-36. Support checkpoint/resume after every material action.

FR-37. Track cost, token use, wall-clock time, provider/model versions, and failure modes.

## Non-Functional Requirements

NFR-1. Factuality: every scientific claim shall be traceable to a source, charted cell, computation, or explicit inference.

NFR-2. Provenance: provenance should be representable in W3C PROV-like terms: entities, activities, agents, timestamps, and derivation edges.

NFR-3. Reproducibility: outputs shall record code commit, prompts, model/provider versions, parameters, seeds, environment, source identifiers, and command/tool calls.

NFR-4. Auditability: prompts, tool calls, model outputs, source retrievals, edits, approvals, exclusions, and charting decisions shall be retained unless explicitly redacted.

NFR-5. Security: generated code and browser activity shall run with least privilege, sandboxing, scoped credentials, and network/filesystem policy.

NFR-6. Privacy: unpublished papers, private libraries, credentials, reviewer notes, and proprietary PDFs shall not be sent to external services unless explicitly allowed.

NFR-7. Reliability: the system shall use retries, timeouts, checkpointing, idempotent operations, and partial-failure recovery.

NFR-8. Cost control: the system shall have budgets and hard stops for tokens, API calls, PDF/OCR operations, embedding, and wall-clock time.

NFR-9. Interoperability: the system should integrate with Zotero, Crossref, OpenAlex, Unpaywall, arXiv, PubMed, DOI, BibTeX, RIS, CSL-JSON, Git, and local markdown.

NFR-10. Extensibility: methods, search providers, reviewers, extraction schemas, writing templates, and output formats should be plugin-like.

NFR-11. Observability: users shall be able to see current phase, pending gates, source counts, errors, access gaps, cost, and artifact readiness.

NFR-12. Explainability: methodology choice, search decisions, exclusions, charted values, synthesis claims, and writing claims shall expose their rationale.

NFR-13. Human accountability: final outputs shall disclose AI-generated parts, human approvals, and unresolved uncertainty.

NFR-14. Robustness to prompt injection: retrieved papers, web pages, PDFs, and metadata shall be treated as untrusted input.

NFR-15. Maintainability: prompts, schemas, requirements, and workflow policies shall be versioned and regression tested because small changes can alter scientific behavior.

NFR-16. Scientific humility: outputs shall expose negative results, failed searches, access gaps, missing evidence, method limits, and non-claims.

## OSS Coverage Assessment

### Strong Candidates for Reuse

#### PaperQA

Use for: scholarly RAG over PDFs/local text, metadata fetching, citation-grounded Q&A, answer generation from retrieved contexts, local indexing.

Fit: high for source-level Q&A and evidence retrieval. PaperQA already encodes a useful principle: answer from context and cite only retrieved evidence.

Gap: it does not provide Reactor's methodology-selection contract, PRISMA-style protocol execution, two-state full-text discipline, charting workflow, or writing-stage argument provenance.

Recommendation: adopt or wrap for local full-text retrieval and evidence-question answering after Reactor has already admitted sources into the evidence base.

#### LatteReview

Use for: title/abstract screening, multi-agent review workflows, structured outputs, certainty scores, RIS input, batch review, cost tracking.

Fit: high for screening and some abstraction tasks.

Gap: it is a flexible reviewer package, not an end-to-end literature-review operating system. It does not solve source acquisition, methodology grounding, full-text provenance, or synthesis traceability on its own.

Recommendation: seriously consider as a dependency or reference implementation for screening/abstraction agents.

#### AgentSLR

Use for: pipeline architecture: metadata harvest, PDF download, OCR, abstract screening, full-text screening, structured extraction, report generation, evaluation against ground truth.

Fit: high as a reference design for systematic-review execution and evaluation.

Gap: current harness is strongly domain-shaped around epidemiological pathogen reviews and the PERG dataset. It is not a general Reactor replacement.

Recommendation: mine for workflow structure, data-workspace layout, OCR/PDF handling, and evaluation patterns. Do not adopt wholesale unless Reactor targets epidemiology-specific SLRs.

#### OpenExtract

Use for: structured extraction from PDFs using RAG over relevant chunks and question schemas.

Fit: medium-high for charting-field extraction.

Gap: currently oriented around multiple-choice extraction and health-review examples. It lacks method selection, screening, synthesis, and writing governance.

Recommendation: useful as an extraction module pattern, especially for charting-form fields that can be expressed as explicit questions.

#### STORM / Co-STORM

Use for: pre-writing research, outline generation, perspective-guided question asking, citation-aware long-form article generation, human-AI collaborative knowledge curation.

Fit: medium for writing and knowledge curation.

Gap: STORM writes Wikipedia-like articles from web research. It is not a systematic/scoping review engine and does not enforce Reactor's source states or methodology constraints.

Recommendation: use as a reference for collaborative outline/prose generation after Reactor's evidence base exists, not as a source-selection authority.

#### Open Deep Research

Use for: configurable LangGraph report agent, section planning, search, compression, final report writing, MCP compatibility, evaluation on Deep Research Bench.

Fit: medium for general research report generation and agent orchestration.

Gap: report benchmark quality is not the same as scholarly review validity. It does not enforce methodology catalogs, Zotero source states, charting forms, or PRISMA-style evidence records.

Recommendation: useful as a reference for LangGraph orchestration, configurable models/search tools, and report-writing loops.

#### GPT Researcher

Use for: web/local research reports, planner/executor/publisher architecture, source aggregation, markdown/PDF/Word exports, MCP retriever support.

Fit: medium for general report writing and export.

Gap: web research agent, not a rigorous literature-review protocol executor. Citation behavior is prompt-enforced rather than evidence-database-enforced.

Recommendation: mine for report export, MCP retriever integration, UI/API packaging, and planner/executor/publisher decomposition.

### Useful but Narrower

#### AutoSurvey

Use for: automated literature survey generation over a large arXiv abstract database, outline generation, subsection drafting, citation post-processing, survey evaluation.

Fit: medium for survey-writing ideas in CS/AI.

Gap: requires a prepared database, primarily uses abstracts unless full-content database is obtained, and does not enforce full-text charting or methodology protocols.

Recommendation: use as a reference for outline/subsection decomposition and citation checking, not as Reactor's source-of-truth pipeline.

#### AgentCPM-Report

Use for: local/offline deep-research report generation over private knowledge bases.

Fit: medium for privacy-sensitive long-form report writing.

Gap: model/report system rather than method-aware scholarly workflow. Needs Reactor evidence and governance around it.

Recommendation: optional writing backend candidate if local/offline deployment becomes a hard requirement.

#### AI Scientist / Agent Laboratory / AutoRA / OpenEvolve / karpathy/autoresearch

Use for: closed-loop experiment automation, code execution, experiment logging, novelty review, paper drafting from experiments.

Fit: low-to-medium for Reactor's immediate literature/writing scope, high for future auto-research beyond literature.

Gap: these systems focus on experiment loops or full scientific-agent demos. Reactor needs literature-method compliance and source-grounded synthesis first.

Recommendation: borrow lifecycle ideas: novelty checks, review loops, sandboxing, experiment/run ledgers, and reproducibility discipline. Do not use as core literature-review software.

#### SLR-automation

Use for: proof-of-concept UI/backend for SLR content generation.

Fit: low.

Gap: appears immature relative to Reactor's evidence-grounding requirements.

Recommendation: do not adopt, except as a reminder that UI alone is not the hard part.

## Requirement Coverage by Existing OSS

| Requirement Area | Existing OSS Coverage | Best Sources | Assessment |
|---|---:|---|---|
| Methodology selection from primary sources | Low | Reactor only | Fresh Reactor-owned build |
| Requirements contract before protocol | Low | Reactor only | Fresh Reactor-owned build |
| Scholarly metadata resolution | High | Reactor citation MCP, PaperQA | Already usable |
| Zotero library integration | Medium-high | Reactor citation MCP | Already usable; extend |
| Search logging / PRISMA-S reporting | Medium | Reactor, AgentSLR | Build around Reactor |
| Screening | Medium-high | LatteReview, AgentSLR | Reuse/adapt |
| Full-text PDF/OCR pipeline | Medium | AgentSLR, OpenExtract, PaperQA | Reuse/adapt |
| Structured charting / extraction | Medium | OpenExtract, LatteReview, AgentSLR | Reuse ideas; Reactor schema needed |
| Evidence matrices | Low-medium | Reactor conceptually, AgentSLR outputs | Fresh Reactor-owned artifact model |
| Synthesis traceability | Low-medium | Reactor discipline, PaperQA contexts | Fresh integration needed |
| Related-work prose drafting | Medium | STORM, AutoSurvey, GPT Researcher, Open Deep Research | Reuse as writing backends only |
| Citation validation in prose | Medium | PaperQA, AutoSurvey, Reactor rules | Fresh enforcement layer needed |
| Paper argument planning | Low | no strong OSS match found | Fresh build |
| Human gates / decision logs | Low-medium | Reactor, Open Deep Research legacy | Reactor-owned |
| Provenance / audit model | Low-medium | Reactor logs, OpenEvolve traces, MLAgentBench logs | Fresh build with borrowed patterns |
| Evaluation harness | Medium | AgentSLR, MLAgentBench, Open Deep Research | Reuse patterns |
| Privacy/local mode | Medium | AgentCPM, PaperQA local modes | Optional backend work |

## Build-vs-Adopt Conclusion

No single existing OSS project should replace Reactor.

The closest candidates cover adjacent slices:

- PaperQA: source-grounded scholarly RAG.
- LatteReview: multi-agent screening and abstraction.
- AgentSLR: end-to-end systematic-review harness, but domain-specific.
- OpenExtract: structured extraction from full-text documents.
- STORM / Open Deep Research / GPT Researcher / AgentCPM-Report: report and prose generation.
- AutoSurvey: survey-writing workflow over prepared arXiv corpora.

The missing core is Reactor's core: method-aware contract generation, phase separation, primary-source methodology grounding, strict source-state handling, charting-to-synthesis traceability, and paper-writing claims constrained by the evidence base.

Therefore the right path is:

1. Keep Reactor as the orchestration and governance layer.
2. Extend Reactor's artifact model from methodology/plan/search into argument and prose phases.
3. Reuse or wrap existing OSS for bounded modules:
   - PaperQA for full-text retrieval and evidence Q&A.
   - LatteReview for screening/abstraction review workflows.
   - AgentSLR/OpenExtract for PDF/OCR/extraction patterns.
   - STORM/Open Deep Research/GPT Researcher for prose generation patterns after evidence is locked.
4. Avoid adopting a general "deep research" agent as the core. Those systems optimize report generation; Reactor optimizes defensible scholarly process.

## Model-Era Assessment

As of 2026 frontier models, the main value of these OSS systems is not "make a weak model act smart." OpenAI's current model docs describe GPT-5.2/GPT-5.1 as coding and agentic-task models, with built-in support for tool use, structured outputs, file search, web search, MCP, and long context. Anthropic's Claude 4.x line likewise targets long-horizon reasoning/coding and agentic workflows. That changes the value calculation.

The current bottleneck is less often raw language-model competence. The bottleneck is state, evidence discipline, provenance, permissions, source acquisition, workflow boundaries, and validation.

This means the OSS projects fall into three categories:

### Still Valuable with Frontier Models

These are valuable because they encode workflow, data model, integration, or evaluation discipline that a frontier model still needs.

- PaperQA: useful because it provides a scholarly-document RAG substrate, metadata handling, local indexing, and answer-from-context behavior.
- LatteReview: useful because it models reviewer roles, rounds, structured outputs, certainty, batch processing, cost, and disagreement workflows.
- AgentSLR: useful because it shows an end-to-end SLR harness with harvest, PDF retrieval, OCR, screening, extraction, report outputs, and ground-truth evaluation.
- OpenExtract: useful because it makes extraction a schema/question-driven process over full text.
- MLAgentBench/OpenEvolve/karpathy-autoresearch: useful as references for run ledgers, experiment isolation, keep/discard loops, failure classification, and traceability.
- Reactor citation MCP: especially valuable because deterministic bibliographic plumbing is not replaced by better model reasoning.

### Less Valuable Than They Used To Be

These are less valuable if their main contribution is prompt scaffolding around weaker models.

- elaborate planner/executor prompt chains for generic report writing;
- multi-agent debate used mainly to compensate for weak single-agent reasoning;
- hand-built reflection loops with no artifact-level validation;
- survey generators that operate mostly over abstracts;
- rigid SLM-oriented stacks where the architecture exists to make a small model barely perform a task that frontier models can now do directly.

These may still contain useful prompts or decomposition ideas, but they should not drive Reactor's architecture.

### Useful Only as Backends, Not Governance

These can accelerate prose or broad research exploration, but they should sit downstream of Reactor's evidence base.

- STORM / Co-STORM
- GPT Researcher
- Open Deep Research
- AgentCPM-Report
- AutoSurvey

With current frontier models, using these as the core would be the wrong abstraction. They are optimized to produce plausible, comprehensive reports. Reactor needs defensible scholarly artifacts.

## Updated Usefulness Ranking

For Reactor's literature and writing automation, the expected payoff is:

1. High: keep and extend Reactor's citation/provenance/source-state core.
2. High: evaluate PaperQA as a full-text evidence Q&A layer.
3. High: evaluate LatteReview for screening and abstraction workflows.
4. Medium-high: mine AgentSLR for workspace layout, PDF/OCR handling, and evaluation.
5. Medium: mine OpenExtract for schema-driven extraction.
6. Medium: use STORM/Open Deep Research/GPT Researcher/AgentCPM-Report as optional drafting/report backends after evidence is locked.
7. Low for core Reactor: adopt whole auto-scientist/autonomous-experiment frameworks. Borrow lessons only.
8. Low: adopt older generic SLR/report apps that mostly wrap prompts and UI.

The practical conclusion is neither "today's models make these obsolete" nor "these solve today's model problems." The answer is: frontier models make many prompt-compensation layers obsolete, but make robust external workflow layers more valuable. The better the model, the more damage it can do if it writes fluent prose without a hard evidence ledger.

## Recommended Architecture

### Reactor Core

Own these components in Reactor:

- Methodology catalog and source-reading discipline.
- Phase state machine.
- Requirements/protocol/evidence/argument/draft artifact schemas.
- User-gate and decision-log mechanism.
- Source-state rules and access-gap handling.
- Charting and synthesis traceability rules.
- Citation/prose validation.

### Reusable Engines

Integrate as swappable adapters:

- `citation_mcp`: keep and extend for source discovery and Zotero operations.
- PaperQA adapter: query admitted full-text corpus.
- LatteReview adapter: title/abstract/full-text screening and abstraction rounds.
- Extraction adapter: OpenExtract-like schema-driven full-text extraction.
- Writing adapter: STORM/Open Deep Research/GPT Researcher-style outline/section drafting against Reactor evidence only.
- Export adapter: Markdown/CSV/JSONL/BibTeX/RIS/CSL-JSON/Quarto.

### Required New Artifacts

Add these beyond current Reactor outputs:

- `source-registry.jsonl`: every source candidate, ID, provenance, state, Zotero key, full-text status, role.
- `screening-log.csv`: candidate, stage, decision, reason, reviewer/agent, timestamp.
- `charting-data.jsonl`: source, field, value, evidence span, confidence, reviewer/agent.
- `claim-ledger.jsonl`: claim, supporting charted cells, limitations, prose locations.
- `argument-plan.md`: paper-section claim structure before drafting.
- `draft.md`: generated prose.
- `draft-grounding-report.md`: unsupported claims, missing citations, overclaims, source mismatches.

## Practical Recommendation

This should be treated as a fresh Reactor product layer, not a generic auto-research adoption.

The implementation should start narrow:

1. Automate the existing phase outputs into a durable state/artifact registry.
2. Add an argument/prose phase that can only cite from the evidence matrix.
3. Add a validation pass that fails unsupported prose.
4. Only then plug in external engines for screening, extraction, and prose acceleration.

Do not start by wiring in a general deep-research agent. That would recreate the failure modes Reactor was built to prevent: plausible prose before defensible evidence.

---

I added this assessment to the note: [notes/auto-research-requirements-and-oss-assessment.md](/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md:383).

My take: it’s neither “today’s models make these obsolete” nor “these solve today’s model problems.”

Current frontier models reduce the value of old prompt-compensation architecture: role-play committees, brittle planner/executor chains, reflection loops with no artifact validation, and SLM-first stacks whose main purpose is making a weak model limp through the task. OpenAI’s current model docs emphasize agentic/coding/tool/MCP capabilities in GPT-5.x models, and Anthropic’s current Claude 4.x material is in the same long-horizon agentic direction. Sources: [OpenAI models](https://platform.openai.com/docs/models), [Anthropic models](https://docs.anthropic.com/en/docs/about-claude/models/overview).

But stronger models make the external discipline more important, not less. For Reactor, the hard problems are still source truth, provenance, full-text state, charting discipline, exclusion logs, claim ledgers, permissions, and validation. A smarter model can write a more convincing unsupported literature review. That is worse unless the system constrains it.

Ranking for value:

- **High value:** PaperQA, LatteReview, Reactor citation MCP. These solve evidence retrieval, structured review, metadata, and source-grounding problems that frontier models do not magically solve.
- **Medium-high:** AgentSLR and OpenExtract. Good pipeline/extraction/OCR/evaluation patterns, but not drop-in Reactor replacements.
- **Medium:** STORM, Open Deep Research, GPT Researcher, AgentCPM-Report, AutoSurvey. Useful as drafting or exploration backends after Reactor locks the evidence base. Bad as governance.
- **Low for Reactor core:** AI Scientist, Agent Laboratory, AutoRA, OpenEvolve, karpathy/autoresearch. Relevant to future experiment automation, not the literature/writing core.
- **Low:** generic older SLR/report apps that are mostly prompts plus UI.

So the useful build posture is: fewer “agent brain” frameworks, more artifact infrastructure. Keep the frontier model as the smart worker; make Reactor the evidence contract, state machine, provenance ledger, and validator.

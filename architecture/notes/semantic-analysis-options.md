# Semantic Analysis for Requirements Quality: Options Analysis

## The Problem

Ground Control's analysis suite has 7 analyses - all structural/graph-based:

| Analysis | What it detects | Signal type |
|----------|----------------|-------------|
| Cycle detection | Circular dependencies | Graph topology |
| Orphan finding | Disconnected requirements | Graph connectivity |
| Coverage gaps | Missing traceability links | Link presence/absence |
| Impact analysis | Transitive blast radius | Graph reachability |
| Cross-wave validation | Backward wave dependencies | Metadata + edges |
| Consistency violations | ACTIVE conflicts/supersedes | Status + relation type |
| Completeness | Missing fields, status distribution | Metadata completeness |

None of these examine what the requirements actually say. Two requirements can be perfectly structured - correct graph position, all traceability links present, no cycles - while saying the same thing in different words, or subtly contradicting each other. Structural analysis tells you about the shape of the system. Semantic analysis tells you about the meaning of the content. Most tooling does one or the other. Ground Control currently does only the former.

### Where the existing consistency analysis falls short

GC-C007 (Consistency Violation Detection) detects ACTIVE-ACTIVE conflicts - but only when someone has already created an explicit `CONFLICTS_WITH` relation between two requirements. It detects the *declared* conflict. It cannot detect *implicit* conflicts buried in the natural language of two requirements that no one has thought to relate to each other. The same is true for `SUPERSEDES` - the system flags active requirements linked by supersedes, but only if the supersedes relation was manually created. The gap is between what's declared and what's actually true in the content.

## What Ground Control's Requirements Look Like

Each requirement carries three text fields:
- **title**: concise, max 255 chars (for example, "Consistency Violation Detection")
- **statement**: full prose, unbounded TEXT (for example, a paragraph-length "the system shall..." description)
- **rationale**: supporting justification, unbounded TEXT (for example, why this requirement matters, what problem it solves)

The Ground Control project currently has 170+ requirements. Most have substantive statement and rationale fields - these are paragraph-length prose, not terse labels. Concatenating title + statement + rationale gives a semantically rich text block per node, well-suited for embedding.

## What Semantic Analysis Could Add

Three tiers of capability, each building on the previous:

### Tier 1: Embedding-Based Similarity Detection

**What it does.** Embed each requirement's text content as a vector. Compute pairwise cosine similarity. Flag pairs above a configurable threshold (for example, 0.85) as overlap candidates.

**What it catches.**
- Near-duplicate requirements stated differently ("User Authentication" vs "Login Functionality")
- Requirements with substantial textual overlap that may represent unintentional redundancy
- Clusters of requirements addressing the same concern from different angles

**What it misses.**
- Logical contradictions where the language is dissimilar ("sessions timeout after 15 minutes" vs "persistent sessions" - low textual similarity, high semantic conflict)
- Complementary overlap - two requirements that *should* be similar because one refines the other. Without structural context, the system can't distinguish intentional refinement from accidental duplication.
- Domain-specific relationships invisible to general-purpose embeddings (for example, that "MoSCoW prioritization" and "MUST/SHOULD/COULD/WONT" are the same concept)

**Scale.** 170 requirements produce ~14K pairs - trivial (milliseconds). 1,000 requirements produce ~500K pairs - still trivial (less than 1 second on float arrays). Requirements management systems rarely exceed this. The n-squared concern from the original commentary is real in general but not at this scale.

**Cost.** Embeddings are computed once per requirement, recomputed only when text changes. OpenAI text-embedding-3-small: ~$0.02 per 1M tokens - 170 requirements costs effectively nothing. Local models (sentence-transformers via sidecar container) have zero marginal cost but lower embedding quality. Storage is ~6KB per requirement for a 1536-dimensional vector.

**Confidence.** High. This is well-understood technology, cheap to run, and catches the "saying the same thing differently" class of problems that humans miss in large requirement sets. The false positive rate is manageable with threshold tuning. This is the approach that is "dumb in the right way" - it doesn't understand the domain, but it catches surface-level semantic overlap that scales better than human review.

### Tier 2: LLM-as-Judge Coherence Classification

**What it does.** For candidate pairs identified by Tier 1 (or other heuristics), send the pair to an LLM: "Do these requirements contradict, duplicate, complement, or merely resemble each other?" Return a structured classification with reasoning.

**What it adds beyond Tier 1.**
- True contradictions where language is superficially different - the LLM can reason about the implications of "sessions timeout after 15 minutes" vs a requirement implying persistent sessions
- Distinction between harmful redundancy vs. intentional refinement - a judgment call that cosine similarity flattens to a single number
- Natural-language reasoning that helps the human reviewer understand *why* the pair was flagged, not just *that* it was flagged

**What it misses.**
- LLM classifications are probabilistic - expect ~85-95% accuracy depending on requirement complexity and prompt quality
- Context-dependent relationships that require understanding the full requirement graph, not just the pair in isolation
- Organizational conventions and domain knowledge not present in the requirement text itself

**Scale.** Must be gated behind Tier 1. Without narrowing, 170 requirements would require ~14K LLM calls - expensive and slow. With Tier 1 filtering (only pairs above similarity threshold), expect 50-100 candidate pairs per project. Each classification is one LLM call (~500-1000 tokens in, ~200 tokens out), 1-3 seconds latency, parallelizable.

**Cost.** GPT-4o-mini or Claude Haiku: ~$0.01-0.02 per analysis run of 100 pairs. Cost scales with candidate pair count (the output of Tier 1 filtering), not with total requirement count. Significantly more expensive per-call than embeddings, but the candidate narrowing makes it manageable.

**Confidence.** Medium-high, with caveats. LLM classification is powerful but adds complexity: prompt engineering, response parsing, cost management, and the question of whether an LLM's opinion about requirement coherence is trustworthy enough to act on. Most useful as a *suggestion* for human review, not as an automated quality gate. The value is highest when the LLM catches contradictions that embedding similarity alone would miss - the cases where two requirements are semantically distant but logically incompatible.

### Tier 3: Structural-Semantic Cross-Reference

**What it does.** Combines similarity scores from Tier 1 with structural graph distance from the existing DAG. Two requirements that are:
- Semantically similar AND structurally close (siblings, parent-child) → likely intentional overlap, lower priority finding
- Semantically similar AND structurally distant (different subgraphs, no shared ancestors) → likely unintentional, higher priority finding

**What it adds beyond Tiers 1+2.**
- Prioritizes findings by confidence - structural distance is a strong signal amplifier
- Reduces false positives from Tier 1 dramatically. Sibling requirements *should* have textual overlap (they're about related concerns). Requirements in unrelated subgraphs that happen to say similar things are a much stronger signal of a problem.
- Identifies cross-cutting concerns that accidentally duplicated across requirement hierarchies

**Architecture fit.** This is the most "Ground Control-native" of the three tiers. It requires no new infrastructure - it combines existing `AnalysisService` graph traversal (ancestors, descendants, path finding) with Tier 1 similarity results. Pure domain logic. No external API calls. The DAG that Ground Control already manages is the structural signal.

**Confidence.** High, given Tier 1 exists. This is the cheapest tier to implement and directly addresses the false positive problem. It's also the analysis that best leverages what Ground Control already does well (graph analysis) to improve what it doesn't do yet (semantic analysis).

## The Do-Nothing Case

The 7 structural analyses may be sufficient for current project scale. Semantic analysis adds external API dependencies (embedding provider) and operational complexity (API keys, cost tracking, staleness management). The pre-alpha philosophy says "ship features, not ceremony."

**When doing nothing is right:**
- Requirement sets are small enough that humans catch content overlaps during review
- Priority is elsewhere (GRC capabilities, formal verification, web UI)
- External API dependencies are undesirable for the deployment model

**When doing nothing becomes wrong:**
- Projects exceed ~50-100 requirements and human review starts missing duplicates
- Multiple contributors create requirements independently (the most common source of unintentional duplication)
- Agents create requirements autonomously (GC-O004 already envisions this) - agents don't naturally check whether a similar requirement already exists before creating a new one. Similarity detection could be the mechanism that makes "find related requirements" actionable, preventing agent-created redundancy at the source.

## Comparison

| Dimension | Tier 1 (Embeddings) | Tier 2 (LLM Judge) | Tier 3 (Cross-ref) | Do Nothing |
|-----------|---------------------|---------------------|---------------------|------------|
| What it adds | Catches near-duplicates | Classifies relationship type | Prioritizes by confidence | Nothing |
| External dependency | Embedding API | LLM API | None (existing graph) | None |
| Cost per analysis run | ~$0.001 | ~$0.02 | Zero | Zero |
| Implementation complexity | Medium (new infra layer) | Medium (prompt eng + parsing) | Low (domain logic only) | Zero |
| Prerequisite | None | Tier 1 | Tier 1 | N/A |
| False positive rate | Moderate (threshold-tunable) | Low (LLM reasons about context) | Low (structural filtering) | N/A |

## Sequencing (if proceeding)

Tier 1 first - it's independently valuable and the prerequisite for everything else. Tier 3 second - cheap to add, no new dependencies, dramatically improves signal-to-noise. Tier 2 third - adds the most sophistication but also the most complexity and cost.

Tiers 1+3 together likely deliver 80% of the total value. Tier 2 is the remaining 20% for when you need to distinguish contradictions from redundancies, not just detect overlap.

## Broader Considerations

**Interaction with agent workflows.** GC-O004 requires agents to query for related requirements before implementation. Today there's no semantic mechanism to power that query - agents can search by text string but not by meaning. Similarity detection is the natural backend for a "find related requirements" tool that agents would use before creating new requirements. This reframes semantic analysis from "quality check" to "prerequisite for effective agent workflows."

**Cross-project potential.** With multi-project support (GC-A013), detecting semantic overlap *between* projects could surface shared requirements. Two projects independently requiring the same authentication behavior is either intentional (shared infrastructure) or a signal that the requirement should be factored into a shared project.

**Quality gates integration.** GC-C010 envisions configurable quality gates with pass/fail thresholds for CI/CD. A similarity threshold ("no two ACTIVE requirements shall exceed 0.9 cosine similarity without an explicit RELATED relation") would be a natural quality gate - catching unintentional duplication before it enters the specification.

**What exists in the tooling landscape.** True formal semantic analysis of natural language requirements - the kind that gives provable disjointness or coherence - mostly doesn't exist outside formal ontology tools (LogMap, AML) that require OWL representations, not natural language. The embedding + LLM pipeline described here is the most practical bridge available today. It's not formal, but it's useful.

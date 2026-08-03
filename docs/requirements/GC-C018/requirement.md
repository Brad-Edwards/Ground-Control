---
id: GC-C018
title: "Semantic Coherence Classification"
status: DRAFT
type: FUNCTIONAL
priority: COULD
wave: 3
created_at: 2026-03-18T07:26:37.305924Z
updated_at: 2026-03-18T07:26:37.305924Z
---

# GC-C018 — Semantic Coherence Classification

## Statement

The system shall support LLM-based classification of semantically similar requirement pairs (identified by GC-C016) as redundant, contradictory, complementary, or unrelated. Classification results shall include the relationship type, natural-language reasoning, and the original similarity score. The LLM provider shall be configurable via a pluggable interface, and the system shall degrade gracefully when no provider is configured. The analysis shall be exposed via both REST API and MCP tools.

## Rationale

Cosine similarity detects that two requirements say similar things but cannot distinguish harmful redundancy from intentional refinement, or surface contradictions where the language is superficially dissimilar. LLM classification bridges "these are similar" to "here is the specific problem": it catches cases like one requirement specifying session timeouts while another implies persistent sessions — semantically distant text with logically incompatible implications. Gating LLM calls behind the cheaper embedding similarity pass (GC-C016) keeps cost proportional to the number of suspicious pairs, not the total requirement count.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#700` (GC-C018: Semantic Coherence Classification)

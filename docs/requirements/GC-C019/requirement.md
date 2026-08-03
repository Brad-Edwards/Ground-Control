---
id: GC-C019
title: "Structural-Semantic Cross-Reference"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-18T07:26:45.663259Z
updated_at: 2026-03-18T07:26:45.663259Z
---

# GC-C019 — Structural-Semantic Cross-Reference

## Statement

The system shall provide an analysis that cross-references semantic similarity results (GC-C016) with structural graph distance, weighting findings by the combination of semantic similarity and structural separation. Requirement pairs that are semantically similar but structurally distant (different subgraphs, no shared ancestors) shall be prioritized as higher-confidence findings than structurally adjacent pairs with equivalent similarity scores. The analysis shall be exposed via both REST API and MCP tools.

## Rationale

Sibling requirements naturally overlap in content — two children of the same parent often address related concerns. Flagging these as duplicates produces false positives. Requirements in unrelated subgraphs that happen to say similar things are a much stronger signal of unintentional duplication or contradiction. Combining the structural signal Ground Control already computes (ancestors, descendants, path finding) with the semantic signal from embeddings reduces noise and surfaces the findings most likely to represent real problems. This analysis requires no additional external API calls — it is pure domain logic over existing graph traversal and similarity data.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#703` (GC-C019: Structural-Semantic Cross-Reference)

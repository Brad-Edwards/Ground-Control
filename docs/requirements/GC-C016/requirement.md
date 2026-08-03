---
id: GC-C016
title: "Semantic Similarity Detection"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-18T07:26:13.506263Z
updated_at: 2026-03-22T04:16:04.516905Z
---

# GC-C016 — Semantic Similarity Detection

## Statement

The system shall provide a semantic similarity analysis that computes pairwise cosine similarity across all requirement embeddings in a project and returns pairs exceeding a configurable threshold as overlap candidates. Results shall include both requirement UIDs, titles, and the similarity score. The analysis shall be exposed via both REST API and MCP tools. The default threshold shall be configurable and the API shall accept an optional threshold parameter per invocation.

## Rationale

Structural analysis detects graph-level problems (cycles, orphans, missing links) but cannot detect content-level problems. Two requirements can be perfectly structured while saying the same thing in different words, wasting implementation effort and creating maintenance divergence. Near-duplicate detection is the highest-value semantic analysis: it is cheap to compute, has a manageable false positive rate via threshold tuning, and catches the class of redundancy problems that humans reliably miss in requirement sets exceeding 50-100 items. At Ground Control's scale (hundreds to low thousands of requirements per project), pairwise comparison is computationally trivial.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/SimilarityService.java` (SimilarityService — cosine similarity analysis)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/SimilarityServiceTest.java` (SimilarityServiceTest — cosine similarity math and service logic)
- DOCUMENTS → GITHUB_ISSUE `375` (GC-C016: Semantic Similarity Detection)

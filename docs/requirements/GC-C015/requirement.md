---
id: GC-C015
title: "Requirement Text Embedding"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-18T07:26:05.322495Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-C015 — Requirement Text Embedding

## Statement

The system shall support generating and storing vector embeddings of requirement text content (title, statement, rationale) via a pluggable embedding provider. Embeddings shall be persisted with content hashes for staleness detection, recomputed only when requirement text changes. The embedding provider shall be configurable (external API or local model) and the system shall degrade gracefully when no provider is configured. Batch embedding of all requirements in a project shall be supported for initial population and model migration.

## Rationale

Semantic analysis capabilities (similarity detection, coherence classification, semantic search) all depend on vector representations of requirement text. Embedding generation and storage is shared infrastructure that must exist before any semantic analysis can operate. Separating it as its own requirement makes the dependency explicit and allows the infrastructure to be implemented and validated independently of the analyses that consume it. The pluggable provider design avoids coupling to a specific embedding API and supports environments where external API access is unavailable.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- DOCUMENTS → GITHUB_ISSUE `373` (GC-C015: Requirement Text Embedding)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/EmbeddingService.java` (EmbeddingService — embedding orchestration)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/EmbeddingProvider.java` (EmbeddingProvider — pluggable provider interface)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/RequirementEmbedding.java` (RequirementEmbedding — entity with content hash and vector storage)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/EmbeddingServiceTest.java` (EmbeddingServiceTest — staleness detection, batch, graceful degradation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementEmbeddingTest.java` (RequirementEmbeddingTest — content hash and byte conversion)

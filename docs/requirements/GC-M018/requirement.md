---
id: GC-M018
title: "Partial Knowledge and Unknown Dependency Support"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-30T02:53:19.459921Z
updated_at: 2026-05-18T01:03:30.771076Z
---

# GC-M018 — Partial Knowledge and Unknown Dependency Support

## Statement

The system shall support incomplete asset knowledge, including manually asserted assets, unknown or tentative dependencies, unclassified assets, and confidence-marked topology gaps, without forcing false precision. Risk, threat, and control workflows shall be able to distinguish confirmed model facts from missing or provisional coverage.

## Rationale

Real-world service models and inventories are never perfect on day one. A future-safe requirements model has to preserve uncertainty and incomplete coverage explicitly rather than pretending the inventory is complete or blocking workflows until it is.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/KnowledgeState.java` (KnowledgeState enum (L0))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/OperationalAsset.java` (OperationalAsset.knowledgeState column)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/AssetRelation.java` (AssetRelation.knowledgeState column)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/AssetService.java` (AssetService threading + GC-M018 filter overload)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/AssetController.java` (AssetController knowledgeState filter + DTO threading)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/AssetGraphProjectionContributor.java` (Graph projection emits knowledgeState on nodes and edges)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V092__add_asset_knowledge_state.sql` (V092 — knowledge_state columns + filter indexes)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V093__add_asset_knowledge_state_audit.sql` (V093 — Envers audit parity for knowledge_state)
- IMPLEMENTS → ADR `architecture/adrs/046-partial-knowledge-and-unknown-dependencies.md` (ADR-046 — Partial knowledge and unknown dependency support)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/KnowledgeStateTest.java` (KnowledgeStateTest — enum + atLeast comparator)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetServiceTest.java` (AssetServiceTest — KnowledgeStateBehavior nested suite)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AssetControllerTest.java` (AssetControllerTest — knowledgeState round-trip + filter)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetGraphProjectionContributorTest.java` (AssetGraphProjectionContributorTest — knowledgeState node + edge property)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/MigrationSmokeTest.java` (MigrationSmokeTest — V092/V093 columns, NOT NULL, filter indexes)
- IMPLEMENTS → GITHUB_ISSUE `#726` (GC-M018: Partial Knowledge and Unknown Dependency Support)

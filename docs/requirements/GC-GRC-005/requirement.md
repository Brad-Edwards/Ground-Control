---
id: GC-GRC-005
title: "Architecture Model Aggregate (Graph-Native, Server-Side)"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:25:05.596826Z
updated_at: 2026-07-11T23:43:44.555544Z
---

# GC-GRC-005 — Architecture Model Aggregate (Graph-Native, Server-Side)

## Statement

The system shall provide a first-class, project-scoped architecture-model aggregate persisting the derived system model server-side in Ground Control.

(a) The model shall represent DFD semantics: components/processes, data stores, external entities, data flows, trust boundaries, and data classifications, as versioned snapshots with diffing between versions.

(b) Model elements shall be graph-native nodes: reachable through gc_graph traversal and linkable from threat models, risk scenarios, controls, and assets. The existing ThreatModelLink ARCHITECTURE_MODEL target type shall resolve to real elements.

(c) The aggregate shall expose REST and MCP read/write surfaces consistent with existing GRC aggregates (project-scoped, audited, auth-gated).

(d) Per-element provenance (which adapter/declaration produced it, at which commit) shall be retained.

(e) The model shall live in Ground Control's database, not the analyzed repository; agents reasoning over the project graph obtain DFD context via graph links, not repo files.

## Rationale

Threat enumeration, coverage gating, and drift detection all operate over a persistent system model. Storing it server-side keeps sensitive architecture detail out of public repos (default-safe per GC-E012's exclusion-boundary principle) while graph-native linkage gives every agent the same authoritative model that risks and threat models already enjoy.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1118` (Issue #1118: GC-GRC-005 architecture model aggregate (graph-native, server-side))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/architecturemodel/service/ArchitectureModelService.java` (ArchitectureModelService — aggregate CRUD, versioned snapshots, diffing (criteria a, d))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/architecturemodel/ArchitectureModelController.java` (ArchitectureModelController — project-scoped, auth-gated REST surface (criterion c))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/architecturemodel/service/ArchitectureModelGraphProjectionContributor.java` (ArchitectureModelGraphProjectionContributor — graph-native elements reachable via gc_graph (criterion b))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/GraphTargetResolverService.java` (GraphTargetResolverService — ThreatModelLink ARCHITECTURE_MODEL target resolves to real elements (criterion b))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/derivation/service/DerivationService.java` (DerivationService.buildFromDerivation — derivation runs populate the model end-to-end (acceptance criterion 3))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V166__create_architecture_model.sql` (V166 migration — server-side architecture-model storage (criterion e))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V167__create_architecture_model_audit.sql` (V167 migration — audit trail for the aggregate (criterion c: audited))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-architecture-model.js` (gc-architecture-model MCP tool — MCP read/write surface (criterion c))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/architecturemodel/ArchitectureModelServiceTest.java` (ArchitectureModelServiceTest — aggregate CRUD, snapshot/diff behavior (criterion a))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ArchitectureModelControllerTest.java` (ArchitectureModelControllerTest — REST surface, project-scoping, auth (criterion c))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ArchitectureModelGraphProjectionContributorTest.java` (ArchitectureModelGraphProjectionContributorTest — graph traversal reaches model elements (criterion b))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/derivation/DerivationServiceTest.java` (DerivationServiceTest — derivation populates the architecture model (acceptance criterion 3))
- TESTS → TEST `mcp/ground-control/gc-architecture-model.test.js` (gc-architecture-model.test.js — MCP tool surface tests (criterion c))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/architecturemodel/ArchitectureModelSnapshotSummaryResponse.java` (ArchitectureModelSnapshotSummaryResponse — bounded list-view projection of snapshots (criterion c))

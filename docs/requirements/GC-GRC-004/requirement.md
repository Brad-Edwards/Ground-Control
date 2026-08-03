---
id: GC-GRC-004
title: "Canonical Boundary Model (Derived plus Declared)"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:24:57.311073Z
updated_at: 2026-07-11T23:43:44.555534Z
---

# GC-GRC-004 — Canonical Boundary Model (Derived plus Declared)

## Statement

The unit of GRC modeling shall be the architectural boundary.

(a) Boundaries shall be derived where adapters support it (for example, layer/package analysis for Java via ArchUnit-style rules, import-graph analysis via CodeQL for other languages).

(b) Boundaries shall be declarable in the repository's .ground-control.yaml GRC block for polyglot or underivable surfaces; declared and derived boundaries merge into one canonical, versioned boundary set.

(c) Every derived component and flow shall be assigned to a boundary; unassignable elements constitute an explicit modeling gap, not a pass.

(d) The boundary set shall version with the architecture model (GC-GRC-005) so boundary drift is detectable.

## Rationale

Per-boundary modeling is the decided unit: fine enough to localize threats, coarse enough to avoid per-file alert fatigue. Ground Control consumers are polyglot, so the boundary model cannot depend on any single-language tool; declaration is the universal fallback with derivation layered on where available.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1117` (Issue #1117: GC-GRC-004 canonical boundary model (derived + declared))
- DOCUMENTS → DOCUMENTATION `docs/architecture/ARCHITECTURE.md` (Canonical boundary model architecture note)
- DOCUMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/derivation/service/BoundaryModelService.java` (BoundaryModelService canonical boundary snapshot builder)
- DOCUMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/BoundaryDerivationAdapter.java` (BoundaryDerivationAdapter derived trust-boundary inputs)
- DOCUMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/derivation/DerivationController.java` (Derivation boundary-model REST readback endpoint)
- DOCUMENTS → CODE_FILE `mcp/ground-control/gc-derivation.js` (gc_derivation declared-boundary forwarding and boundary-model action)
- DOCUMENTS → CONFIG `.ground-control.yaml` (Declared GRC boundary inputs for this repository)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/derivation/BoundaryModelServiceTest.java` (BoundaryModelService unit coverage)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/infrastructure/derivation/BoundaryDerivationAdapterTest.java` (BoundaryDerivationAdapter unit coverage)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/DerivationControllerTest.java` (DerivationController boundary-model WebMvc coverage)
- TESTS → TEST `mcp/ground-control/gc-derivation.test.js` (gc_derivation boundary forwarding tests)
- DOCUMENTS → DOCUMENTATION `docs/API.md` (Derivations API boundary-model reference)
- DOCUMENTS → DOCUMENTATION `docs/DEVELOPMENT_WORKFLOW.md` (Ground Control grc.boundaries config contract)
- DOCUMENTS → DOCUMENTATION `architecture/notes/canonical-boundary-model-preflight.md` (Canonical boundary model architecture preflight notes)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/derivation/service/BoundaryModelService.java` (BoundaryModelService canonical boundary snapshot builder)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/BoundaryDerivationAdapter.java` (BoundaryDerivationAdapter derived trust-boundary inputs)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/derivation/DerivationController.java` (Derivation boundary-model REST readback endpoint)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-derivation.js` (gc_derivation declared-boundary forwarding and boundary-model action)

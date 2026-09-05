---
id: GC-GRC-006
title: "Data Classification Lattice"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:25:18.483206Z
updated_at: 2026-07-11T23:43:44.555554Z
---

# GC-GRC-006 — Data Classification Lattice

## Statement

The system shall provide a project-scoped data-sensitivity label taxonomy forming an information-flow lattice.

(a) A default taxonomy shall ship (for example, PUBLIC, INTERNAL, CONFIDENTIAL, PII, CREDENTIALS/SECRETS, REGULATED), customizable per project via the GRC configuration surface (GC-GRC-023).

(b) Labels shall attach to architecture-model elements: flows, data stores, assets, and external interactions.

(c) Lattice policy shall define permitted label flows; a derived flow that violates the policy (sensitive data reaching a lower-trust sink) shall be a derivable finding by construction, requiring no LLM judgment.

(d) Lattice policy and label assignments shall be stored server-side with the architecture model.

## Rationale

Denning-style information-flow lattices turn 'does this leak?' from a generative judgment into a checkable property: label the data, and a lattice-violating flow IS the finding. This is the information-control-theory backbone that lets deterministic analysis carry the PII/secret-leak class of threats.

## Traceability

- IMPLEMENTS → ADR `architecture/adrs/072-data-classification-lattice.md` (ADR-072: Data Classification Lattice design of record (GC-GRC-006))
- IMPLEMENTS → GITHUB_ISSUE `1119` (Issue #1119: GC-GRC-006 data classification lattice)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/dataclassification/service/DataClassificationEvaluationService.java` (Deterministic data-classification lattice evaluator (GC-GRC-006 clause c))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/dataclassification/service/DataClassificationLatticeService.java` (Project-scoped lattice aggregate: resolve/replace/reset server-side policy (GC-GRC-006 clause d))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/dataclassification/DataClassificationController.java` (REST surface for lattice get/put/delete and evaluation (GC-GRC-006))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/dataclassification/service/DataClassificationLatticeFactory.java` (Lattice soundness validation and reflexive-transitive closure (GC-GRC-006 clause c))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/dataclassification/service/DefaultDataClassificationLattice.java` (Shipped default taxonomy and covering relation (GC-GRC-006 clause a))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V169__create_data_classification_lattice.sql` (Schema for server-side lattice storage (GC-GRC-006 clause d))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/ApiPathMatrix.java` (Admin-only lattice write authorization (GC-GRC-006, GC-TM-010))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-data-classification.js` (MCP config-surface tool for per-project lattice override (GC-GRC-006 clause a / GC-GRC-023))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/dataclassification/DataClassificationEvaluationServiceTest.java` (Acceptance criterion: PII-to-lower-trust-sink is a deterministic violation (GC-GRC-006 clause c))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/dataclassification/DataClassificationLatticeServiceTest.java` (Lattice persistence: default fallback, custom mapping, replace, reset (GC-GRC-006 clause d))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/dataclassification/DataClassificationLatticeFactoryTest.java` (Validation, closure, and antisymmetry of the lattice factory (GC-GRC-006 clause c))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/DataClassificationControllerTest.java` (REST contract slice for lattice and evaluation endpoints (GC-GRC-006))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/DataClassificationLatticeServiceIntegrationTest.java` (Persistence round-trip and Envers audit integration (GC-GRC-006 clause d))

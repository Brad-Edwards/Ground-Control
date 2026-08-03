---
id: GC-J001
title: "Architecture Decision Records"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-13T23:14:18.377091Z
updated_at: 2026-04-01T00:01:17.059258Z
---

# GC-J001 — Architecture Decision Records

## Statement

The system shall support managing architecture decision records (ADRs) as first-class entities linked to requirements, enabling traceability from architectural decisions to the requirements they satisfy or constrain.

## Rationale

ADRs capture the rationale behind architectural choices. Linking ADRs to requirements shows why specific technical decisions were made and which requirements drove them.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/adrs/` (ADR domain package (entity, service, repository, status enum))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/adrs/` (ADR REST API (controller, DTOs))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AdrStatusTest.java` (ADR status transition unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AdrControllerIntegrationTest.java` (ADR controller integration tests (CRUD, transitions, reverse traceability))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ArchitectureDecisionRecordTest.java` (ADR entity unit tests (defaults, transitions, accessors))
- IMPLEMENTS → GITHUB_ISSUE `464` (GC-J001: Architecture Decision Records)
- IMPLEMENTS → PULL_REQUEST `496` ([codex] Enforce ADR conformance across repo tooling)

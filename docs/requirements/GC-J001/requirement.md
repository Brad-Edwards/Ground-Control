---
id: GC-J001
title: "Architecture Decision Records"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-13T23:14:18.377091Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-J001 — Architecture Decision Records

## Statement

The system shall support managing architecture decision records (ADRs) as first-class entities linked to requirements, enabling traceability from architectural decisions to the requirements they satisfy or constrain.

## Rationale

ADRs capture the rationale behind architectural choices. Linking ADRs to requirements shows why specific technical decisions were made and which requirements drove them.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `464` (GC-J001: Architecture Decision Records)
- IMPLEMENTS → PULL_REQUEST `496` ([codex] Enforce ADR conformance across repo tooling)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/adrs/` (ADR domain package (entity, service, repository, status enum))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/adrs/` (ADR REST API (controller, DTOs))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AdrStatusTest.java` (ADR status transition unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AdrControllerIntegrationTest.java` (ADR controller integration tests (CRUD, transitions, reverse traceability))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ArchitectureDecisionRecordTest.java` (ADR entity unit tests (defaults, transitions, accessors))

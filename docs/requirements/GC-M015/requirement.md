---
id: GC-M015
title: "Observation and State Fact Entity"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T02:53:19.156559Z
updated_at: 2026-04-02T01:39:45.897056Z
---

# GC-M015 — Observation and State Fact Entity

## Statement

The system shall support a first-class Observation entity representing time-bounded state facts about operational assets, control implementations, or topology, such as configuration values, exposure status, identity assignments, deployment attributes, patch state, or discovered relationships. Observations shall remain distinct from the asset definition and shall record source, observed-at time, freshness or validity window, and supporting evidence references.

## Rationale

Cloud inventory and control-monitoring systems consistently separate the object being modeled from the observations collected about it. Treating mutable state as observations rather than as the whole asset definition preserves temporal reasoning, provenance, and continuous assurance workflows.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tool registrations for observations)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP API functions for observations)
- IMPLEMENTS → GITHUB_ISSUE `Brad-Edwards/Ground-Control#473` (GC-M015: Observation and State Fact Entity)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/model/Observation.java` (Observation JPA Entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/state/ObservationCategory.java` (ObservationCategory Enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/repository/ObservationRepository.java` (ObservationRepository)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/assets/service/ObservationService.java` (ObservationService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/assets/ObservationController.java` (ObservationController REST API)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V035__create_observation.sql` (Flyway migration: observation table)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V036__create_observation_audit.sql` (Flyway migration: observation audit table)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ObservationControllerTest.java` (ObservationController WebMvc unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ObservationServiceTest.java` (ObservationService unit tests)

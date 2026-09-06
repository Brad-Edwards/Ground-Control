---
id: GC-A002
title: "Status State Machine"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:18.249404Z
updated_at: 2026-03-14T02:52:36.390793Z
---

# GC-A002 — Status State Machine

## Statement

The system shall enforce a status state machine: DRAFT to ACTIVE, ACTIVE to DEPRECATED, ACTIVE to ARCHIVED, DEPRECATED to ARCHIVED. No transitions out of ARCHIVED. No backward transitions.

## Rationale

Lifecycle governance ensures requirements move forward through maturity stages. Prevents accidental reactivation of archived or deprecated requirements.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (gc_transition_status MCP tool)
- DOCUMENTS → ADR `architecture/adrs/011-requirements-data-model.md` (Data model design)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#322` (Tech debt: Entity constructors accept null required fields)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/Status.java` (State machine definition + validTargets())
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/Requirement.java` (transitionStatus() enforcement)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/RequirementService.java` (transitionStatus() + archive() service methods)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (POST /transition + /archive endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/StatusTransitionRequest.java` (Transition request DTO)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V001__create_requirement.sql` (status column + DRAFT default)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementTest.java` (All valid/invalid transitions + archive)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementServiceTest.java` (Unit: transition delegation + errors)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementControllerIntegrationTest.java` (Integration: transition 200, invalid 422)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementServiceIntegrationTest.java` (Integration: transition + archive persistence)

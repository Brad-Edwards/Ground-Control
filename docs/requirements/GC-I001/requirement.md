---
id: GC-I001
title: "Control Catalog"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-13T23:14:06.291514Z
updated_at: 2026-04-05T16:53:24.763291Z
---

# GC-I001 — Control Catalog

## Statement

The system shall support a control catalog with control definitions, control objectives, control role or function (preventive, detective, corrective, compensating), ownership, linked operational assets or asset classes, scoped implementations or assignments, methodology-aware factor mappings, effectiveness data, observation and evidence relationships, lifecycle tracking, and links to code, configuration, or operational artifacts that implement them.

## Rationale

Controls must be intelligible across FAIR, NIST, ISO, audit, and technical assurance workflows. A graph-native control model has to show what operational objects a control protects, where it is implemented, and what evidence or observations support that claim.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tools for control CRUD and linking)
- DOCUMENTS → ADR `ADR-022` (Content Pack Distribution Architecture)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/model/Control.java` (Control entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/model/ControlLink.java` (ControlLink entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/service/ControlService.java` (ControlService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/service/ControlLinkService.java` (ControlLinkService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/controls/ControlController.java` (ControlController REST API)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/controls/ControlLinkController.java` (ControlLinkController REST API)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V046__create_control.sql` (V046 migration: control and control_link tables)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ControlServiceTest.java` (ControlService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ControlLinkServiceTest.java` (ControlLinkService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ControlControllerTest.java` (ControlController WebMvcTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ControlLinkControllerTest.java` (ControlLinkController WebMvcTest)

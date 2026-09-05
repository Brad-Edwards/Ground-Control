---
id: GC-O002
title: "Self-Referential Traceability"
status: ACTIVE
type: CONSTRAINT
priority: MUST
wave: 2
created_at: 2026-03-13T23:15:12.694404Z
updated_at: 2026-04-11T04:09:57.505280Z
---

# GC-O002 — Self-Referential Traceability

## Statement

Ground Control's own requirements shall be traced to its own code, tests, and ADRs using Ground Control's traceability features, validating the traceability system with a real use case.

## Rationale

Self-referential traceability proves the system works end-to-end. If GC cannot trace its own requirements to its own code, it cannot credibly offer that capability to external users.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP Server - Self-referential traceability via MCP tools)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP lib - getTraceabilityByArtifact API wrapper)
- IMPLEMENTS → GITHUB_ISSUE `509` (GC-O002: Self-Referential Traceability)

## Historical traceability

Links below named artifacts the #1500 re-platform deleted. They are kept for
provenance and are outside the parsed `## Traceability` section, so no tool reads
them as live evidence. Do not infer current implementation from them.

- IMPLEMENTS → CODE_FILE `tools/ground_control/check_live_policy.mjs` (Live policy check - reverse traceability enforcement)
- IMPLEMENTS → CODE_FILE `tools/ground_control/sweep-baseline.json` (Sweep baseline - untracedFiles regression tracking)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/TraceabilityService.java` (TraceabilityService - forward and reverse traceability lookup)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/requirements/RequirementController.java` (RequirementController - reverse traceability lookup endpoint)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/repository/TraceabilityLinkRepository.java` (TraceabilityLinkRepository - reverse lookup query)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RequirementControllerTest.java` (RequirementControllerTest - reverse traceability lookup tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TraceabilityServiceTest.java` (TraceabilityServiceTest - findByArtifact unit tests)

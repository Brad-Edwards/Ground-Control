---
id: GC-O005
title: "Requirement-Before-Code Policy"
status: ACTIVE
type: CONSTRAINT
priority: SHOULD
wave: 2
created_at: 2026-03-14T01:24:26.169457Z
updated_at: 2026-03-31T03:45:51.263980Z
---

# GC-O005 — Requirement-Before-Code Policy

## Statement

The system shall enforce that a requirement must exist and be in ACTIVE status before an IMPLEMENTS traceability link can be created against it, preventing retroactive justification of unplanned work.

## Rationale

Without this constraint, traceability links are created after the fact to satisfy coverage gates, rather than requirements driving implementation. Requirement-before-code ensures the planning-to-execution flow is forward, not backward. This prevents the common failure mode where requirements are written to document what was already built rather than to specify what should be built.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/TraceabilityService.java` (TraceabilityService - ACTIVE status check for IMPLEMENTS links)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TraceabilityServiceTest.java` (TraceabilityService tests - status enforcement for IMPLEMENTS links)
- IMPLEMENTS → GITHUB_ISSUE `460` (GC-O005: Requirement-Before-Code Policy)

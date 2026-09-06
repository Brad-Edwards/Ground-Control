---
id: GC-P002
title: "Append-Only Audit Trail"
status: ACTIVE
type: NON_FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:15:27.785012Z
updated_at: 2026-03-15T16:35:47.836522Z
---

# GC-P002 — Append-Only Audit Trail

## Statement

The system shall maintain an append-only audit trail of every mutation (requirement changes, relation changes, traceability link changes, status transitions), recording actor identity, timestamp, and the change performed.

## Rationale

Audit trails are non-negotiable for compliance and accountability. Append-only ensures the trail cannot be tampered with. Every mutation must be traceable to an actor and timestamp.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/033-authenticated-audit-actor-provenance.md` (ADR-033: Authenticated Audit Actor Provenance)
- IMPLEMENTS → GITHUB_ISSUE `#301` (GC-P002: Append-Only Audit Trail)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#326` (Tech debt: Test suite quality — fake tests, getter/setter bloat, duplicated helpers)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/web/ActorFilter.java` (ActorFilter — populates actor identity from X-Actor header)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AuditService.java` (AuditService — relation and traceability link history queries)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AuditHistoryIntegrationTest.java` (Audit history integration tests — actor, relation, and traceability link history)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AuditActorProvenanceIntegrationTest.java` (Audit actor provenance integration tests — authenticated principal vs. spoofed X-Actor, unauthenticated rejection)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ActorFilterTest.java` (ActorFilter unit tests — actor resolution from SecurityContext / X-Actor fallback / MDC key)

---
id: GC-U001
title: "Audit Entity"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-14T19:31:47.208537Z
updated_at: 2026-05-18T02:26:37.952934Z
---

# GC-U001 — Audit Entity

## Statement

The system shall support an Audit entity with audit type, scope description, objectives, timeline phases, team assignment, and status lifecycle. Audits shall be linkable to compliance frameworks, operational assets, controls, risk scenarios or risk records, evidence, and prior audit findings to support risk-based audit planning and traceable follow-up.

## Rationale

Audit programs operate on controls, findings, evidence, and risk context. Explicit links to operational assets and evidence are required if audit work is going to inform graph-native risk management workflows instead of living in a separate silo.

## Traceability

- IMPLEMENTS → CODE_FILE `domain/audits` (Audit domain aggregate (GC-U001))
- IMPLEMENTS → CODE_FILE `api/audits` (Audit REST controllers (/api/v1/audits/**))
- DOCUMENTS → ADR `ADR-048` (Audit Entity Boundary)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AuditTest.java` (AuditTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AuditStatusPropertyTest.java` (AuditStatusPropertyTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AuditServiceTest.java` (AuditServiceTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AuditLinkServiceTest.java` (AuditLinkServiceTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AuditGraphProjectionContributorTest.java` (AuditGraphProjectionContributorTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditControllerTest.java` (AuditControllerTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditLinkControllerTest.java` (AuditLinkControllerTest)
- TESTS → TEST `mcp/ground-control/gc-audit.test.js`
- IMPLEMENTS → GITHUB_ISSUE `#275` (GC-U001: Audit Entity)

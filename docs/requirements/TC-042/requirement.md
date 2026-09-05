---
id: TC-042
title: "Compliance and Governance"
status: DEPRECATED
type: NON_FUNCTIONAL
priority: COULD
wave: 5
created_at: 2026-03-22T06:15:57.568037Z
updated_at: 2026-09-05T20:12:24Z
---

# TC-042 — Compliance and Governance

## Statement

The system shall support compliance features including: full audit logging of all CRUD operations with filtering and export, data retention policies, FDA-compatible audit trails, and configurable governance rules for test approval workflows.

## Rationale

PractiTest supports SOC 2, ISO 27001, HIPAA, and FDA audit trails. TestRail Enterprise supports audit logging with export. qTest supports enterprise governance. Compliance features are required for regulated industries (healthcare, finance, defense).

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `393` (TC-042: Compliance and Governance)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/audit/AuditController.java` (Audit Controller (project timeline + CSV export endpoints))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AuditExportService.java` (Audit Export Service (CSV formatting))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/compliance/AuditRetentionJob.java` (Audit Retention Job (scheduled cleanup))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/ComplianceIntegrationTest.java` (Compliance Integration Tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AuditExportServiceTest.java` (Audit Export Service Unit Tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AuditControllerTest.java` (Audit Controller Unit Tests)

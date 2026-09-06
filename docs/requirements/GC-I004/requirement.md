---
id: GC-I004
title: "Continuous Compliance Monitoring"
status: ACTIVE
type: FUNCTIONAL
priority: COULD
wave: 5
created_at: 2026-03-13T23:14:15.555400Z
updated_at: 2026-06-20T22:28:35.179015Z
---

# GC-I004 — Continuous Compliance Monitoring

## Statement

The system shall support continuous compliance monitoring by detecting when code changes, control modifications, or evidence expiration affect compliance posture, rather than relying on periodic manual assessments.

## Rationale

Point-in-time compliance assessments are immediately stale. Continuous monitoring tied to the artifact graph detects compliance drift as it happens, not months later during audit season.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (lib.js)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (index.js)
- IMPLEMENTS → GITHUB_ISSUE `#763` (GC-I004: Continuous Compliance Monitoring)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- TESTS → TEST `mcp/ground-control/gc-analyze.test.js` (gc-analyze.test.js)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/ComplianceMonitoringAnalysisService.java` (ComplianceMonitoringAnalysisService.java)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/GrcAnalysisService.java` (GrcAnalysisService.java)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/grcanalysis/GrcAnalysisController.java` (GrcAnalysisController.java)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/grcanalysis/ComplianceMonitoringAnalysisServiceTest.java` (ComplianceMonitoringAnalysisServiceTest.java)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/grcanalysis/ComplianceMonitoringResponseTest.java` (ComplianceMonitoringResponseTest.java)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GrcAnalysisControllerTest.java` (GrcAnalysisControllerTest.java)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/grcanalysis/GrcAnalysisIntegrationTest.java` (GrcAnalysisIntegrationTest.java)

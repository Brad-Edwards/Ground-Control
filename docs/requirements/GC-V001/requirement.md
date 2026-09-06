---
id: GC-V001
title: "Finding Entity"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T19:34:36.579939Z
updated_at: 2026-05-13T03:46:13.661664Z
---

# GC-V001 — Finding Entity

## Statement

The system shall support a unified Finding entity with finding type such as audit-finding, control-deficiency, policy-violation, vulnerability, or exception-escalation; severity; description; root cause analysis; affected controls; affected risks; affected operational assets or artifacts; status lifecycle; owner; and due date. Findings shall be first-class entities linkable to controls, risks, assets, observations, evidence, audits, and remediation plans.

## Rationale

Findings are the output of every GRC activity. Without a unified finding entity grounded in affected controls, risks, and operational assets, deficiencies remain disconnected from the systems and services they actually endanger.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#279` (GC-V001: Finding Entity)
- IMPLEMENTS → ADR `architecture/adrs/038-finding-entity-boundary.md`
- IMPLEMENTS → PULL_REQUEST `#903` (Add Finding entity (GC-V001) with cross-aggregate link integrity)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/findings/model/Finding.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/findings/service/FindingService.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/findings/FindingController.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/FindingTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/FindingStatusPropertyTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/FindingServiceTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/FindingLinkServiceTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/FindingGraphProjectionContributorTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/FindingControllerTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/FindingLinkControllerTest.java`

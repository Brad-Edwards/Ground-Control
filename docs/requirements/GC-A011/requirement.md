---
id: GC-A011
title: "Archive Exclusion from Default Listings"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:39.144833Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-A011 — Archive Exclusion from Default Listings

## Statement

The system shall exclude archived requirements from default listings, requiring explicit opt-in to include them in results.

## Rationale

Archived requirements are historical records. Including them in default views clutters the working set and confuses users about what is current.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `335` (GC-A011: Archive Exclusion from Default Listings)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/repository/RequirementSpecifications.java` (RequirementSpecifications - notArchived filter)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - archive exclusion in all analysis queries)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/domain/requirements/repository/RequirementSpecificationsIntegrationTest.java` (RequirementSpecificationsIntegrationTest - archive exclusion tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisServiceTest.java` (AnalysisServiceTest - archive exclusion in analysis operations)

---
id: GC-A011
title: "Archive Exclusion from Default Listings"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:10:39.144833Z
updated_at: 2026-03-18T05:21:34.667345Z
---

# GC-A011 — Archive Exclusion from Default Listings

## Statement

The system shall exclude archived requirements from default listings, requiring explicit opt-in to include them in results.

## Rationale

Archived requirements are historical records. Including them in default views clutters the working set and confuses users about what is current.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/repository/RequirementSpecifications.java` (RequirementSpecifications - notArchived filter)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService - archive exclusion in all analysis queries)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/domain/requirements/repository/RequirementSpecificationsIntegrationTest.java` (RequirementSpecificationsIntegrationTest - archive exclusion tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisServiceTest.java` (AnalysisServiceTest - archive exclusion in analysis operations)
- IMPLEMENTS → GITHUB_ISSUE `335` (GC-A011: Archive Exclusion from Default Listings)

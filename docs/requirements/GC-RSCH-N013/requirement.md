---
id: GC-RSCH-N013
title: "NFR-13 Human accountability"
status: ACTIVE
type: NON_FUNCTIONAL
priority: MUST
wave: 14
created_at: 2026-05-25T22:47:46.991780Z
updated_at: 2026-06-28T16:54:31.295221Z
---

# GC-RSCH-N013 — NFR-13 Human accountability

## Statement

Human accountability: final outputs shall disclose AI-generated parts, human approvals, and unresolved uncertainty.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1001` (Research decision gates and decision log)
- DOCUMENTS → GITHUB_ISSUE `1023` (Research evidence-constrained drafting)
- DOCUMENTS → GITHUB_ISSUE `1026` (Research automated review pipeline)
- IMPLEMENTS → GITHUB_ISSUE `1001` (Research decision gates and decision log)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchRunDisclosure.java` (ResearchRunDisclosure — accountability disclosure entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchRunService.java` (ResearchRunService — complete() disclosure gating for N013)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/research/ResearchRunController.java` (ResearchRunController — exposes disclosure endpoints for N013)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunDecisionSurfacesServiceTest.java` (ResearchRunDecisionSurfacesServiceTest — tests disclosure gating for completion)

---
id: GC-RSCH-R005
title: "R-5 Treat generated code, browser activity, lab/hardware actions, and external writes as high-risk operations requiring s..."
status: ACTIVE
type: CONSTRAINT
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.128715Z
updated_at: 2026-07-04T01:31:35.194436Z
---

# GC-RSCH-R005 — R-5 Treat generated code, browser activity, lab/hardware actions, and external writes as high-risk operations requiring s...

## Statement

The system shall treat generated code, browser activity, lab/hardware actions, and external writes as high-risk operations requiring sandboxing and explicit authorization.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1029` (Research adapter/plugin boundary)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchOperationAuthorizationService.java` (Default-deny high-risk operation authorization service)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchRunOperationAuthorization.java` (Run-scoped authorization aggregate)
- IMPLEMENTS → ADR `architecture/adrs/086-research-high-risk-operation-authorization.md` (ADR-086 high-risk operation authorization decision)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchOperationAuthorizationServiceTest.java` (Authorization lifecycle behavioral tests)
- IMPLEMENTS → GITHUB_ISSUE `1008` (Research privacy, security, and prompt-injection controls)

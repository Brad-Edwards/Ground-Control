---
id: GC-RSCH-N005
title: "NFR-5 Security"
status: ACTIVE
type: NON_FUNCTIONAL
priority: MUST
wave: 14
created_at: 2026-05-25T22:47:46.861410Z
updated_at: 2026-07-04T01:31:37.542583Z
---

# GC-RSCH-N005 — NFR-5 Security

## Statement

Security: generated code and browser activity shall run with least privilege, sandboxing, scoped credentials, and network/filesystem policy.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/ApiPathMatrix.java` (Admin-gated decision/consume routes (least privilege))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchOperationAuthorizationService.java` (allowedTools inventory (not authorization); bounded records)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/ApiSecurityConfigTest.java` (Decision/consume admin-gate security-matrix tests)
- IMPLEMENTS → GITHUB_ISSUE `1008` (Research privacy, security, and prompt-injection controls)

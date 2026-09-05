---
id: GC-RSCH-N014
title: "NFR-14 Robustness to prompt injection"
status: ACTIVE
type: NON_FUNCTIONAL
priority: MUST
wave: 14
created_at: 2026-05-25T22:47:47.007810Z
updated_at: 2026-07-04T01:31:41.369346Z
---

# GC-RSCH-N014 — NFR-14 Robustness to prompt injection

## Statement

Robustness to prompt injection: retrieved papers, web pages, PDFs, and metadata shall be treated as untrusted input.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- IMPLEMENTS → ADR `architecture/adrs/086-research-high-risk-operation-authorization.md` (ADR-086 §5 untrusted-input data-flow rule)
- IMPLEMENTS → GITHUB_ISSUE `1008` (Research privacy, security, and prompt-injection controls)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/research/OperationAuthorizationRequest.java` (Closed-enum typed policy fields (untrusted content cannot set policy))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchOperationAuthorizationServiceTest.java` (Concrete-effect / typed-field invariant tests)

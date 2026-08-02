---
id: GC-RSCH-N006
title: "NFR-6 Privacy"
status: ACTIVE
type: NON_FUNCTIONAL
priority: MUST
wave: 14
created_at: 2026-05-25T22:47:46.879775Z
updated_at: 2026-07-04T01:31:39.413416Z
---

# GC-RSCH-N006 — NFR-6 Privacy

## Statement

Privacy: unpublished papers, private libraries, credentials, reviewer notes, and proprietary PDFs shall not be sent to external services unless explicitly allowed.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1011` (Research Zotero and source-store integration)
- DOCUMENTS → GITHUB_ISSUE `1030` (Research local/offline execution mode)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/EgressPolicyEvaluator.java` (Default-deny egress policy evaluator)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchEgressAllowance.java` (Structured egress allowance value type)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/EgressPolicyEvaluatorTest.java` (Egress allow/deny/form-ordering/default-deny tests)
- IMPLEMENTS → GITHUB_ISSUE `1008` (Research privacy, security, and prompt-injection controls)

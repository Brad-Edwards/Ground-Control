---
id: GC-RSCH-N007
title: "NFR-7 Reliability"
status: ACTIVE
type: NON_FUNCTIONAL
priority: MUST
wave: 14
created_at: 2026-05-25T22:47:46.897570Z
updated_at: 2026-06-27T15:05:28.795720Z
---

# GC-RSCH-N007 — NFR-7 Reliability

## Statement

Reliability: the system shall use retries, timeouts, checkpointing, idempotent operations, and partial-failure recovery.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1000` (Research run lifecycle and phase gating)
- DOCUMENTS → GITHUB_ISSUE `1021` (Research full-text evidence Q&A adapter)
- DOCUMENTS → GITHUB_ISSUE `1028` (Research checkpointing, observability, and budgets)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchRunService.java` (ResearchRunService — idempotent operations + bounded partial-failure recovery)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunServiceTest.java` (ResearchRunServiceTest — idempotency, bounded fail/recover, resume tests)

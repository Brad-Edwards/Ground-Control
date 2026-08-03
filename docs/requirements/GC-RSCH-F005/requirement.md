---
id: GC-RSCH-F005
title: "FR-5 Select review methodology from a catalog and explicitly justify rejected alternatives"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.261048Z
updated_at: 2026-05-26T01:48:03.052404Z
---

# GC-RSCH-F005 — FR-5 Select review methodology from a catalog and explicitly justify rejected alternatives

## Statement

Select review methodology from a catalog and explicitly justify rejected alternatives.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1005` (Research methodology catalog and primary-source tracking)
- DOCUMENTS → GITHUB_ISSUE `1032` (Migrate Reactor discipline and artifacts into Ground Control research workflows)
- IMPLEMENTS → CODE_FILE `skills/lit-review/SKILL.md` (lit-review skill — methodology selection + requirements extraction)
- IMPLEMENTS → PULL_REQUEST `1039` (PR #1039 — research workflow skills + citation MCP (merged to dev))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchRunMethodologySelection.java` (Run-scoped methodology selection snapshot (method key + versions))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchRunService.java` (selectMethodology derives + snapshots the selected method (F005))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunMethodologyServiceTest.java` (Service tests: methodology selection + supersede/idempotency)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ResearchRunMethodologyControllerTest.java` (Controller slice tests: methodology selection endpoint)

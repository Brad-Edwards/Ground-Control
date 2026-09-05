---
id: GC-RSCH-N012
title: "NFR-12 Explainability"
status: DRAFT
type: NON_FUNCTIONAL
priority: MUST
wave: 14
created_at: 2026-05-25T22:47:46.974796Z
updated_at: 2026-09-05T20:22:53Z
---

# GC-RSCH-N012 — NFR-12 Explainability

## Statement

Explainability: methodology choice, search decisions, exclusions, charted values, synthesis claims, and writing claims shall expose their rationale.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Status note

The backend research-run control plane that implemented this requirement was
deleted by the #1500 re-platform
([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)).
The literature-review skill pipeline under `skills/lit-review*/` and the citation
MCP under `mcp/citation/` survive, but they enforce a lighter contract than this
requirement states, so no surviving artifact implements it as written.

The status is `DRAFT` rather than `DEPRECATED`: the research capability is not
retired, and how this requirement should fit the file-and-skill architecture is
an open question to revisit. `DRAFT` is the honest reading, since it says the
requirement is specified but not implemented. The original backend evidence is
preserved under `## Historical traceability` below (issue #650).

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1001` (Research decision gates and decision log)
- DOCUMENTS → GITHUB_ISSUE `1007` (Research protocol planning artifact)
- DOCUMENTS → GITHUB_ISSUE `1013` (Research screening workflow and exclusion log)
- DOCUMENTS → GITHUB_ISSUE `1016` (Research charting schema, pilot coding, and evidence spans)
- DOCUMENTS → GITHUB_ISSUE `1017` (Research screening and abstraction adapter workflows)
- DOCUMENTS → GITHUB_ISSUE `1020` (Research method-limit and overclaim checks)
- DOCUMENTS → GITHUB_ISSUE `1022` (Research argument claim ledger)
- DOCUMENTS → GITHUB_ISSUE `1024` (Research citation and prose grounding validation)
- DOCUMENTS → GITHUB_ISSUE `1026` (Research automated review pipeline)
- DOCUMENTS → GITHUB_ISSUE `1031` (Research workspace UI and dashboard)
- IMPLEMENTS → GITHUB_ISSUE `1001` (Research decision gates and decision log)
- DOCUMENTS → GITHUB_ISSUE `1006` (Research methodology requirements artifact)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchRunRationaleEntry.java` (ResearchRunRationaleEntry — explainability ledger entry)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchRunService.java` (ResearchRunService — addRationaleEntry implements explainability ledger)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/research/ResearchRunController.java` (ResearchRunController — exposes rationale entry endpoints for N012)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunDecisionSurfacesServiceTest.java` (ResearchRunDecisionSurfacesServiceTest — tests rationale entry explainability ledger)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/MethodologyRequirementsContractRejectedAlternative.java` (Rejected-alternative records expose methodology-choice rationale via ledger refs (NFR-12))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunMethodologyContractServiceTest.java` (Contract service tests: rejected-alternative rationale exposure (NFR-12))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ProtocolAnswerProvenance.java` (Typed, bounded answer provenance on filled protocol coverage (explainability, #1007))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunProtocolPlanServiceTest.java` (Answer-provenance validation tests (explainability, #1007))

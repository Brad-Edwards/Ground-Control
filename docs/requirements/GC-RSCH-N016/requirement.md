---
id: GC-RSCH-N016
title: "NFR-16 Scientific humility"
status: ACTIVE
type: NON_FUNCTIONAL
priority: MUST
wave: 14
created_at: 2026-05-25T22:47:47.040465Z
updated_at: 2026-07-02T17:42:00.900680Z
---

# GC-RSCH-N016 — NFR-16 Scientific humility

## Statement

Scientific humility: outputs shall expose negative results, failed searches, access gaps, missing evidence, method limits, and non-claims.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1005` (Research methodology catalog and primary-source tracking)
- DOCUMENTS → GITHUB_ISSUE `1007` (Research protocol planning artifact)
- DOCUMENTS → GITHUB_ISSUE `1014` (Research full-text acquisition and access-gap enforcement)
- DOCUMENTS → GITHUB_ISSUE `1016` (Research charting schema, pilot coding, and evidence spans)
- DOCUMENTS → GITHUB_ISSUE `1019` (Research thematic synthesis and conflict preservation)
- DOCUMENTS → GITHUB_ISSUE `1020` (Research method-limit and overclaim checks)
- DOCUMENTS → GITHUB_ISSUE `1024` (Research citation and prose grounding validation)
- DOCUMENTS → ADR `architecture/adrs/076-research-scientific-humility-surface.md` (ADR-076: humility surface — #1005 ships the BLOCKED access-gap state facet)
- DOCUMENTS → GITHUB_ISSUE `1006` (Research methodology requirements artifact)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ContractEntryKind.java` (METHOD_LIMIT and NON_CLAIM entry kinds — scientific-humility surface (NFR-16))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/MethodologyRequirementsContractEntry.java` (Contract entries carry method limits and non-claims (NFR-16))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunMethodologyContractServiceTest.java` (Contract service tests: METHOD_LIMIT / NON_CLAIM entries (NFR-16))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ProtocolMethodShape.java` (METHOD_LIMITS/NON_CLAIMS carried forward + deferral dispositions (scientific humility, #1007))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunProtocolPlanServiceTest.java` (Humility carry-forward (METHOD_LIMITS/NON_CLAIMS/deferral) tests (#1007))

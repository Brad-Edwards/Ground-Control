---
id: GC-RSCH-R004
title: "R-4 Maintain a full provenance chain from user goal to methodology source, query, candidate source, full text, charting c..."
status: ACTIVE
type: NON_FUNCTIONAL
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.107442Z
updated_at: 2026-06-28T20:57:41.645189Z
---

# GC-RSCH-R004 — R-4 Maintain a full provenance chain from user goal to methodology source, query, candidate source, full text, charting c...

## Statement

The system shall maintain a full provenance chain from user goal to methodology source, query, candidate source, full text, charting cell, synthesis claim, argument move, and final prose.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1002` (Research artifact registry and provenance ledger)
- DOCUMENTS → GITHUB_ISSUE `1003` (Research graph projection and traversal support)
- DOCUMENTS → GITHUB_ISSUE `1022` (Research argument claim ledger)
- IMPLEMENTS → ADR `architecture/adrs/069-research-artifact-provenance-ledger.md` (ADR-069 — research artifact provenance ledger design)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchProvenanceService.java` (Research provenance ledger service — node/edge record + backward chain traversal (ADR-069))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchProvenanceServiceTest.java` (Provenance service tests — chain traversal, cycle/self-edge, idempotency, supersession, scoping)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/ResearchGraphProjectionContributor.java` (Research graph projection — provenance chain traversable via the mixed graph (ADR-070))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/graph/ResearchGraphProjectionContributorTest.java` (Research graph projection tests — node/edge projection, direction, dangling-edge guard)

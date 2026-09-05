---
id: GC-RSCH-N002
title: "NFR-2 Provenance"
status: DRAFT
type: NON_FUNCTIONAL
priority: SHOULD
wave: 13
created_at: 2026-05-25T22:47:46.809021Z
updated_at: 2026-09-05T20:22:53Z
---

# GC-RSCH-N002 — NFR-2 Provenance

## Statement

Provenance: provenance should be representable in W3C PROV-like terms: entities, activities, agents, timestamps, and derivation edges.

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

- DOCUMENTS → GITHUB_ISSUE `1002` (Research full-text, screening, charting, and evidence matrices)
- DOCUMENTS → GITHUB_ISSUE `1003` (Research graph projection and traversal support)
- DOCUMENTS → GITHUB_ISSUE `1018` (Research evidence matrix and numerical synthesis)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ResearchProvenanceNode.java` (Provenance node entity — PROV entity with actor/timestamp (Envers) metadata)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ProvenanceEdgeRelation.java` (Provenance edge relations — PROV derivation edges (DERIVED_FROM/SUPPORTS/SELECTED/CITED/CONTRIBUTED_TO))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchProvenanceServiceTest.java` (Provenance service tests — node/edge model behavior)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/graph/ResearchGraphProjectionContributorTest.java` (Research graph projection tests — provenance nodes/edges)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/ResearchGraphProjectionContributor.java` (PROV-like provenance entities + derivation edges projected into the graph (ADR-070))

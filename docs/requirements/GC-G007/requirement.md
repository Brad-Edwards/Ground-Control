---
id: GC-G007
title: "Mixed-Entity Graph Participation"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T02:53:19.561586Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-G007 — Mixed-Entity Graph Participation

## Statement

The system shall support first-class graph participation for non-requirement entities including operational assets, risk scenarios, controls, findings, audits, evidence, issues, documents, and external artifacts, with typed relations and consistent graph semantics across the domain.

## Rationale

Ground Control's core differentiator is the graph itself. If risks, controls, assets, issues, and evidence are not graph participants in their own right, the platform falls back into siloed records linked only indirectly through requirements.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `727` (GH-727: Mixed-Entity Graph Participation)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/DocumentGraphProjectionContributor.java` (DocumentGraphProjectionContributor — clause 8: document graph participation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/documents/DocumentResponse.java` (DocumentResponse.graphNodeId — consistent graph identity on document API responses)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/evidence/EvidenceArtifactResponse.java` (EvidenceArtifactResponse.graphNodeId — cross-domain graph identity consistency (clause 11))
- IMPLEMENTS → CODE_FILE `frontend/src/lib/graph-constants.ts` (graph-constants.ts — DOCUMENT in GraphEntityType union + ENTITY_TYPE_COLORS sync)
- IMPLEMENTS → CODE_FILE `frontend/src/types/api.ts` (api.ts — GraphEntityType union extended with DOCUMENT for frontend type safety)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/graph/DocumentGraphProjectionContributorTest.java` (DocumentGraphProjectionContributorTest — verifies clause 8 graph projection behavior)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/GraphTargetResolverServiceTest.java` (GraphTargetResolverServiceTest — validateDocumentTarget tests added for GC-G007)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/AgeGraphServiceTest.java` (AgeGraphServiceTest — DOCUMENT entity handling in AGE graph service (GC-G007 coverage))
- TESTS → TEST `frontend/src/lib/graph-constants.test.ts` (graph-constants.test.ts — verifies DOCUMENT in GraphEntityType union and color mapping)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/AssetGraphProjectionContributor.java` (AssetGraphProjectionContributor — clause 1: operational asset graph participation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/RiskGraphProjectionContributor.java` (RiskGraphProjectionContributor — clause 2: risk scenario graph participation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/ControlGraphProjectionContributor.java` (ControlGraphProjectionContributor — clause 3: control graph participation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/FindingGraphProjectionContributor.java` (FindingGraphProjectionContributor — clause 4: finding graph participation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/audits/service/AuditGraphProjectionContributor.java` (AuditGraphProjectionContributor — clause 5: audit graph participation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/EvidenceArtifactGraphProjectionContributor.java` (EvidenceArtifactGraphProjectionContributor — clause 6: evidence graph participation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/GraphProjectionContributor.java` (GraphProjectionContributor interface — extension seam enabling typed multi-entity graph participation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphEntityType.java` (GraphEntityType enum — typed graph entity vocabulary covering all entity families including DOCUMENT)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphIds.java` (GraphIds — consistent graph node identity factory for all entity types (cross-domain consistency))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/GraphTargetResolverService.java` (GraphTargetResolverService — validateDocumentTarget + multi-entity target resolution (clause 9-11))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/RequirementGraphProjectionContributor.java` (Requirement graph projection of traceability artifacts)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RequirementGraphProjectionContributorTest.java` (Requirement traceability graph projection unit coverage)

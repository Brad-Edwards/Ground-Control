---
id: GC-E001
title: "Universal Artifact Linking"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:12:50.258531Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-E001 — Universal Artifact Linking

## Statement

The system shall support creating traceability links between requirements and any artifact type including: source code, tests, ADRs, configuration files, specifications, proof artifacts, documentation, and policies.

## Rationale

The artifact traceability graph is Ground Control's differentiator. Requirements connected to their implementing code, tests, proofs, and documentation provide end-to-end visibility across the entire development lifecycle.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `454` (GC-E001: Universal Artifact Linking)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#314` (Bug: Traceability link/relation endpoints ignore parent requirement ID)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#327` (Tech debt: TraceabilityLink has no JML contracts)
- IMPLEMENTS → PULL_REQUEST `496` ([codex] Enforce ADR conformance across repo tooling)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/TraceabilityService.java` (TraceabilityService - Universal artifact linking)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/TraceabilityLink.java` (TraceabilityLink - Artifact link model)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TraceabilityLinkTest.java` (TraceabilityLink unit tests - all artifact types)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/TraceabilityLinkIntegrationTest.java` (TraceabilityLink JPA integration tests - all artifact types)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/TraceabilityLinkControllerIntegrationTest.java` (TraceabilityLink REST API integration tests - all artifact types)

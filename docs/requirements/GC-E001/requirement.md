---
id: GC-E001
title: "Universal Artifact Linking"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:12:50.258531Z
updated_at: 2026-03-30T04:43:15.111709Z
---

# GC-E001 — Universal Artifact Linking

## Statement

The system shall support creating traceability links between requirements and any artifact type including: source code, tests, ADRs, configuration files, specifications, proof artifacts, documentation, and policies.

## Rationale

The artifact traceability graph is Ground Control's differentiator. Requirements connected to their implementing code, tests, proofs, and documentation provide end-to-end visibility across the entire development lifecycle.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/TraceabilityService.java` (TraceabilityService - Universal artifact linking)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/model/TraceabilityLink.java` (TraceabilityLink - Artifact link model)
- IMPLEMENTS → GITHUB_ISSUE `454` (GC-E001: Universal Artifact Linking)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TraceabilityLinkTest.java` (TraceabilityLink unit tests - all artifact types)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/TraceabilityLinkIntegrationTest.java` (TraceabilityLink JPA integration tests - all artifact types)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/TraceabilityLinkControllerIntegrationTest.java` (TraceabilityLink REST API integration tests - all artifact types)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#314` (Bug: Traceability link/relation endpoints ignore parent requirement ID)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#327` (Tech debt: TraceabilityLink has no JML contracts)
- IMPLEMENTS → PULL_REQUEST `496` ([codex] Enforce ADR conformance across repo tooling)

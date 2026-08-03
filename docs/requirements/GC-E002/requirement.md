---
id: GC-E002
title: "Typed Link Semantics"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-13T23:12:53.367746Z
updated_at: 2026-05-16T04:46:56.751629Z
---

# GC-E002 — Typed Link Semantics

## Statement

The system shall support typed traceability link semantics including: implements, tests, documents, constrains, and verifies, capturing the nature of the relationship between a requirement and its linked artifact.

## Rationale

Not all artifact links are equal. A test link means something different from an implements link. Typed semantics enable precise queries like 'which requirements lack test coverage' vs 'which lack implementation.'

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/LinkType.java` (LinkType enum - Typed link semantics)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TraceabilityLinkTest.java` (TraceabilityLinkTest - typed link semantics)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#314` (Bug: Traceability link/relation endpoints ignore parent requirement ID)
- CONSTRAINS → GITHUB_ISSUE `autarchy-ai/Ground-Control#327` (Tech debt: TraceabilityLink has no JML contracts)
- DOCUMENTS → GITHUB_ISSUE `#666` (GC-E002: Typed Link Semantics)

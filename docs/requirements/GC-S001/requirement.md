---
id: GC-S001
title: "Evidence Collection Adapter Interface"
status: DEPRECATED
type: INTERFACE
priority: MUST
wave: 4
created_at: 2026-03-14T16:55:57.194905Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-S001 — Evidence Collection Adapter Interface

## Statement

The system shall define a pluggable evidence collection adapter interface that agents can invoke to collect evidence from external systems. The interface shall specify: connection configuration, collection scope, output schema, error handling, and rate limiting. Adapters shall be registrable at runtime via the plugin architecture.

## Rationale

This is the #1 agentic differentiator. AuditBoard has 200+ static integrations. GC's approach is agent-executable adapters — AI agents invoke adapters to pull evidence from live systems, making evidence collection a programmable workflow rather than a configured integration.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#210` (GC-S001: Evidence Collection Adapter Interface)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/EvidenceCollectionAdapter.java` (EvidenceCollectionAdapter port)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/EvidenceConnectionConfig.java` (Evidence collection connection configuration)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/EvidenceCollectionScope.java` (Evidence collection scope contract)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/EvidenceCollectionOutputSchema.java` (Evidence collection output schema contract)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/EvidenceCollectionRateLimit.java` (Evidence collection rate-limit contract)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/EvidenceCollectionError.java` (Evidence collection error handling contract)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/EvidenceCollectionRequest.java` (Evidence collection request contract)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/EvidenceCollectionResult.java` (Evidence collection result contract)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/EvidenceCollectionStatus.java` (Evidence collection status contract)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/service/EvidenceCollectionAdapterRegistry.java` (Evidence collection adapter registry)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/plugins/state/PluginType.java` (Evidence collector plugin type)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/EvidenceCollectionAdapterContractTest.java` (Evidence collection adapter contract tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/EvidenceCollectionAdapterRegistryTest.java` (Evidence collection adapter registry tests)

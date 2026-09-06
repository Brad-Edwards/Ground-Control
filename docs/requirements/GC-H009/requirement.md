---
id: GC-H009
title: "Vulnerability-Threat Linking"
status: ACTIVE
type: FUNCTIONAL
priority: COULD
wave: 5
created_at: 2026-03-14T19:32:59.007099Z
updated_at: 2026-05-16T04:02:22.758970Z
---

# GC-H009 — Vulnerability-Threat Linking

## Statement

The system shall support linking vulnerability records such as CVEs, scan findings, penetration test results, and exposure observations to threat-model entries and affected operational assets, showing which real-world conditions instantiate which modeled threats. The model shall be agent-consumable for automated threat validation and risk reassessment.

## Rationale

Threat models are theoretical; vulnerabilities and exposure observations are empirical. Linking them through the affected operational asset validates threat models and enriches remediation prioritization.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP THREAT_MODEL_LINK_TARGET_TYPES mirror (added FINDING))
- IMPLEMENTS → GITHUB_ISSUE `#269` (GC-H009: Vulnerability-Threat Linking)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/state/ThreatModelLinkTargetType.java` (ThreatModelLinkTargetType enum (added FINDING for GC-H009))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/GraphTargetResolverService.java` (GraphTargetResolverService.validateThreatModelTarget FINDING dispatch)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/ThreatModelGraphProjectionContributor.java` (ThreatModelGraphProjectionContributor FINDING edge mapping)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/findings/service/FindingService.java` (FindingService.delete ThreatModelLink reverse-link guard)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/repository/ThreatModelLinkRepository.java` (ThreatModelLinkRepository reverse-link projection query)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/GraphTargetResolverServiceTest.java` (GraphTargetResolverServiceTest FINDING positive + 4 negative-path tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ThreatModelGraphProjectionContributorTest.java` (ThreatModelGraphProjectionContributorTest contributesEdgeToFinding)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/FindingServiceTest.java` (FindingServiceTest rejectsDeleteWhenThreatModelLinkReferencesFinding)
- TESTS → TEST `mcp/ground-control/gc-threat-model.test.js` (MCP gc-threat-model FINDING handler + Zod schema tests)

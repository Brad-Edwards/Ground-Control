---
id: GC-H006
title: "Threat-Control Mapping"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-14T19:32:36.399633Z
updated_at: 2026-06-20T17:33:33.936167Z
---

# GC-H006 — Threat-Control Mapping

## Statement

The system shall support mapping between threat-model entries or risk scenarios and mitigating controls on relevant operational assets or boundaries, recording control role and methodology-specific influence when available, such as preventive, detective, corrective, or compensating function and qualitative or quantitative effect on risk reduction. The system shall identify threat scenarios with no mapped controls, controls not mapped to any relevant threat scenario, and scenarios where mapped controls have insufficient demonstrated effectiveness from observations, tests, or evidence.

## Rationale

Threat-control mapping connects security analysis to operational controls across multiple methodologies. Grounding the mapping in asset scope and evidence-backed effectiveness prevents the model from claiming mitigation without showing what operational object is protected and how that claim is supported.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#266` (GC-H006: Threat-Control Mapping)
- DOCUMENTS → ADR `ADR-052` (ADR-052 — Risk-Control Mapping Aggregate (GC-T003), amended 2026-06-20 for GC-H006 threat-control mapping)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskcontrol/model/RiskControlMapping.java` (RiskControlMapping — ThreatModel analysis-side endpoint)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskcontrol/service/RiskControlMappingService.java` (RiskControlMappingService — 3-way analysis-endpoint validation + threat create paths)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskcontrol/service/RiskControlCoverageService.java` (RiskControlCoverageService — threat-side coverage queries (no-controls, unmapped-controls, insufficient-effectiveness))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskcontrol/repository/RiskControlMappingRepository.java` (RiskControlMappingRepository — threat reverse-lookup + coverage JPQL)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskcontrol/RiskControlAnalysisController.java` (RiskControlAnalysisController — threat coverage endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V137__add_threat_model_to_risk_control_mapping.sql` (V137 — threat_model_id endpoint + 3-way CHECK + partial unique indexes)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/riskcontrol/RiskControlCoverageServiceTest.java` (RiskControlCoverageServiceTest — threat coverage + insufficient-effectiveness tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RiskControlAnalysisControllerTest.java` (RiskControlAnalysisControllerTest — threat coverage endpoint slices)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/riskcontrol/RiskControlMappingServiceTest.java` (RiskControlMappingServiceTest — threat endpoint create + 3-way validation tests)

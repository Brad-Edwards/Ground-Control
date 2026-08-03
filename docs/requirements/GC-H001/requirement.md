---
id: GC-H001
title: "Threat Source and Threat Event Modeling"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-13T23:13:57.289251Z
updated_at: 2026-04-12T04:29:09.978786Z
---

# GC-H001 — Threat Source and Threat Event Modeling

## Statement

The system shall support first-class threat modeling entities that distinguish at minimum threat source or actor, threat event or method, affected operational asset or system boundary, and effect or consequence. Threat-model entries may carry STRIDE or other threat-modeling taxonomies, but shall not be treated as equivalent to quantified risk assessments or risk register records. Threat-model outputs shall be linkable to scoped operational assets, topology or boundary context, risk scenarios, requirements, controls, observations, evidence, architecture models, code, and issues.

## Rationale

NIST distinguishes threat source from threat event, and FAIR treats threats as inputs to scoped loss scenarios rather than as risk measurements. Grounding threat entries in operational assets and boundaries keeps the model graph-native and prevents threat records from floating free of the systems and services they actually describe.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/model/ThreatModel.java` (ThreatModel entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/model/ThreatModelLink.java` (ThreatModelLink entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/state/ThreatModelStatus.java` (ThreatModelStatus enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/state/StrideCategory.java` (StrideCategory enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/state/ThreatModelLinkTargetType.java` (ThreatModelLinkTargetType enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/state/ThreatModelLinkType.java` (ThreatModelLinkType enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/repository/ThreatModelRepository.java` (ThreatModelRepository)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/repository/ThreatModelLinkRepository.java` (ThreatModelLinkRepository)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/service/ThreatModelService.java` (ThreatModelService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/threatmodels/service/ThreatModelLinkService.java` (ThreatModelLinkService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/ThreatModelGraphProjectionContributor.java` (ThreatModelGraphProjectionContributor)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/GraphTargetResolverService.java` (GraphTargetResolverService (validateThreatModelTarget + upgraded asset/risk resolvers))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphEntityType.java` (GraphEntityType (added THREAT_MODEL))
- DOCUMENTS → ADR `ADR-024` (ADR-024: Threat Model Entry Boundary)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/threatmodels/ThreatModelController.java` (ThreatModelController)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ThreatModelServiceTest.java` (ThreatModelServiceTest)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/threatmodels/ThreatModelLinkController.java` (ThreatModelLinkController)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ThreatModelLinkServiceTest.java` (ThreatModelLinkServiceTest)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V055__create_threat_model.sql` (V055: create threat_model table)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ThreatModelGraphProjectionContributorTest.java` (ThreatModelGraphProjectionContributorTest)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V056__create_threat_model_audit.sql` (V056: create threat_model_audit table)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V058__create_threat_model_link_audit.sql` (V058: create threat_model_link_audit table)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/GraphTargetResolverServiceTest.java` (GraphTargetResolverServiceTest (threat model resolver coverage))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ThreatModelLinkControllerTest.java` (ThreatModelLinkControllerTest (@WebMvcTest))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V057__create_threat_model_link.sql` (V057: create threat_model_link table)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP lib.js (threat model constants, functions, field mappings))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ThreatModelControllerTest.java` (ThreatModelControllerTest (@WebMvcTest))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP index.js (gc_*_threat_model* tool registrations))
- IMPLEMENTS → GITHUB_ISSUE `519` (GC-H001: Threat Source and Threat Event Modeling)

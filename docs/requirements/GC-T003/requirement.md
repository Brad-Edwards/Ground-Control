---
id: GC-T003
title: "Risk Scenario-Control Mapping"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T19:32:07.155911Z
updated_at: 2026-05-21T18:30:16.425595Z
---

# GC-T003 — Risk Scenario-Control Mapping

## Statement

The system shall support bidirectional many-to-many mapping between controls or scoped control implementations and risk scenarios or risk register records on relevant operational assets or boundaries. A mapping shall record the control objective, control role, scope, and methodology-specific influence on risk reduction, such as qualitative likelihood or consequence dimensions or FAIR-aligned frequency and magnitude factors. The system shall identify scenarios or records with no mapped controls and controls not mapped to any relevant scenario, and control evaluation results from GC-I013 plus relevant observations or evidence shall feed linked risk assessments.

## Rationale

Controls are the shared mitigation layer across FAIR, NIST, ISO, audit, and third-party risk workflows. A method-aware mapping model has to explain how a control changes a scenario in a specific operational context, not merely assert that a control exists.

## Traceability

- DOCUMENTS → DOCUMENTATION `architecture/notes/risk-control-mapping-preflight.md` (Risk-Control Mapping Preflight (architecture guardrails))
- DOCUMENTS → DOCUMENTATION `architecture/notes/risk-control-mapping-verification.md` (GC-T003 Risk Scenario-Control Mapping — Verification Record)
- IMPLEMENTS → GITHUB_ISSUE `#258` (GC-T003: Risk-Control Mapping)
- DOCUMENTS → GITHUB_ISSUE `824` (Verify GC-T003 (Risk Scenario-Control Mapping): clause-by-clause audit, transition DRAFT→ACTIVE)
- IMPLEMENTS → ADR `architecture/adrs/052-risk-control-mapping.md` (ADR-052: Risk-Control Mapping)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskcontrol/model/RiskControlMapping.java` (RiskControlMapping domain model)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskcontrol/model/ScopedControlImplementation.java` (ScopedControlImplementation domain model)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskcontrol/service/RiskControlMappingService.java` (RiskControlMappingService — many-to-many mapping CRUD)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskcontrol/service/ScopedControlImplementationService.java` (ScopedControlImplementationService — scoped control CRUD)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskcontrol/service/RiskControlCoverageService.java` (RiskControlCoverageService — unmapped scenario/control gap detection)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskcontrol/service/RiskControlMappingFeedService.java` (RiskControlMappingFeedService — assessment feed from control evaluation results)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskcontrol/service/MethodologyInfluenceValidator.java` (MethodologyInfluenceValidator — methodology-specific influence validation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/RiskControlMappingGraphProjectionContributor.java` (RiskControlMappingGraphProjectionContributor — graph projection for risk-control edges)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskcontrol/RiskControlMappingController.java` (RiskControlMappingController — REST API for mapping CRUD)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskcontrol/ScopedControlImplementationController.java` (ScopedControlImplementationController — REST API for scoped control CRUD)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskcontrol/RiskControlAnalysisController.java` (RiskControlAnalysisController — unmapped gap analysis endpoints)
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V119__create_scoped_control_implementation.sql` (V119: create scoped_control_implementation table)
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V121__create_risk_control_mapping.sql` (V121: create risk_control_mapping table)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/riskcontrol/RiskControlMappingServiceTest.java` (RiskControlMappingServiceTest — unit tests for mapping service)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/riskcontrol/ScopedControlImplementationServiceTest.java` (ScopedControlImplementationServiceTest — unit tests for scoped control service)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/riskcontrol/RiskControlCoverageServiceTest.java` (RiskControlCoverageServiceTest — unit tests for gap detection)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/riskcontrol/RiskControlMappingFeedServiceTest.java` (RiskControlMappingFeedServiceTest — unit tests for assessment feed service)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/riskcontrol/MethodologyInfluenceValidatorTest.java` (MethodologyInfluenceValidatorTest — unit tests for methodology influence validation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RiskControlMappingControllerTest.java` (RiskControlMappingControllerTest — unit tests for mapping API controller)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ScopedControlImplementationControllerTest.java` (ScopedControlImplementationControllerTest — unit tests for scoped control API controller)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RiskControlAnalysisControllerTest.java` (RiskControlAnalysisControllerTest — unit tests for gap analysis API controller)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskControlMappingGraphProjectionContributorTest.java` (RiskControlMappingGraphProjectionContributorTest — unit tests for graph projection contributor)

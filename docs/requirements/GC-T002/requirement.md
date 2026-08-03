---
id: GC-T002
title: "Risk Assessment Methodology Profiles"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T19:32:00.002507Z
updated_at: 2026-08-02T18:39:44.325066Z
---

# GC-T002 — Risk Assessment Methodology Profiles

## Statement

The system shall support versioned risk assessment methodology profiles defining the semantics, factors, scales, units, and output rules used to assess risk scenarios. Methodology profiles shall support at minimum the FAIR Model v3.0, FAIR-CAM-aligned control analytics inputs, FAIR-MAM-aligned loss magnitude extensions when configured, NIST SP 800-30 Rev. 1-style likelihood and impact assessment, and ISO 27005 and ISO 27001-compatible risk criteria. Every risk assessment result shall identify the methodology profile used.

## Rationale

Supporting multiple risk methodologies requires explicit preservation of their semantics, not a single overloaded score field. Versioned methodology profiles let the platform support FAIR, NIST, ISO, and organization-specific variants without redefining core entities every time the assessment method changes.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/MethodologyProfile.java` (MethodologyProfile entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/MethodologyFamily.java` (MethodologyFamily enum (FAIR, NIST, ISO, CUSTOM))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/MethodologyProfileStatus.java` (MethodologyProfileStatus enum (ACTIVE, DEPRECATED))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/repository/MethodologyProfileRepository.java` (MethodologyProfileRepository)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/MethodologyProfileService.java` (MethodologyProfileService with methodology-specific schema seeding)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/MethodologyProfileController.java` (MethodologyProfileController REST API)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V043__create_risk_assessment.sql` (V043 migration: methodology_profile table and seed data)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V045__populate_methodology_profile_schemas.sql` (V045 migration: methodology profile schemas (FAIR/NIST/ISO/Legacy))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/RiskAssessmentResult.java` (RiskAssessmentResult entity with required methodologyProfile FK)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tools for methodology profile CRUD)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/MethodologyProfileServiceTest.java` (MethodologyProfileService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/MethodologyProfileControllerTest.java` (MethodologyProfileController WebMvcTest)
- IMPLEMENTS → GITHUB_ISSUE `#257` (GC-T002: Risk Scoring Engine)

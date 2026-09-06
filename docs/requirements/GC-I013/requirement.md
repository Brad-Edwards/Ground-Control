---
id: GC-I013
title: "Control Effectiveness Assessment"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T19:33:13.503661Z
updated_at: 2026-08-02T18:39:44.325098Z
---

# GC-I013 — Control Effectiveness Assessment

## Statement

The system shall support design effectiveness and operating effectiveness assessments per control, plus methodology-specific influence on risk reduction. Assessments shall be able to express effects on qualitative likelihood or consequence criteria and, for FAIR-aligned analyses, on relevant frequency or magnitude factors. Effectiveness results may be scoped to specific control implementations, asset populations, or observation sets and shall feed linked risk assessment results rather than only a single residual score field.

## Rationale

Controls can affect different parts of a risk model depending on the methodology in use. A shared effectiveness model must be rich enough to support FAIR factor analysis, NIST or ISO-style likelihood and impact reasoning, and the operational scope where the control is actually observed.

## Traceability

- IMPLEMENTS → ADR `architecture/adrs/039-control-verification-subsystem.md` (ADR-039 Control Verification Subsystem (GC-T003 consumption seam))
- IMPLEMENTS → GITHUB_ISSUE `#271` (GC-I013: Control Effectiveness Assessment)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/model/ControlEffectivenessAssessment.java` (ControlEffectivenessAssessment entity (design + operating rating))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/service/ControlEffectivenessAssessmentService.java` (ControlEffectivenessAssessmentService (supportingTestIds validation))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/controls/ControlEffectivenessAssessmentController.java` (ControlEffectivenessAssessmentController (/api/v1/control-effectiveness-assessments))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controls/state/ControlEffectivenessRating.java` (ControlEffectivenessRating enum (EFFECTIVE/PARTIALLY_EFFECTIVE/INEFFECTIVE))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V067__create_control_effectiveness_assessment.sql` (V067 control_effectiveness_assessment schema)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/ControlEffectivenessAssessmentGraphProjectionContributor.java` (ControlEffectivenessAssessment graph projection (OF_CONTROL + SUPPORTED_BY edges))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ControlEffectivenessAssessmentServiceTest.java` (ControlEffectivenessAssessmentService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ControlEffectivenessAssessmentControllerTest.java` (ControlEffectivenessAssessmentController @WebMvcTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/ControlEffectivenessAssessmentControllerIntegrationTest.java` (ControlEffectivenessAssessmentController integration test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ControlEffectivenessAssessmentGraphProjectionContributorTest.java` (ControlEffectivenessAssessment graph projection contributor unit test)

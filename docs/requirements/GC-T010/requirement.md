---
id: GC-T010
title: "Risk Assessment Result Entity"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T01:53:34.225846Z
updated_at: 2026-08-02T18:39:44.325062Z
---

# GC-T010 — Risk Assessment Result Entity

## Statement

The system shall support first-class Risk Assessment Result entities linked to a risk scenario and a methodology profile. An assessment result shall store input factors, assumptions, analyst or agent identity, observation date, time horizon, confidence or uncertainty metadata, relevant observation or evidence set references, computed outputs, and approval state. Multiple assessments of the same scenario using different methodologies or at different times shall coexist without overwriting one another.

## Rationale

A platform that supports FAIR, NIST, ISO, and organization-specific methods cannot store only one score per risk. Assessment results must be explicit, versioned, methodology-aware, and traceable to the evidence and observations used.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#731` (GC-T010: Risk Assessment Result Entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/RiskAssessmentResult.java` (RiskAssessmentResult JPA entity — stores all GC-T010 fields and the @Audited revision history)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/RiskAssessmentApprovalStatus.java` (RiskAssessmentApprovalStatus enum — DRAFT/SUBMITTED/APPROVED/REJECTED with canTransitionTo (clause 11 state machine))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/RiskAssessmentResultService.java` (RiskAssessmentResultService — project-scoped CRUD + approval-state transition + multi-assessment listing per scenario / record)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/repository/RiskAssessmentResultRepository.java` (RiskAssessmentResultRepository — project-scoped queries ordered by createdAt DESC (clauses 12-14: coexisting multi-assessment / multi-methodology / multi-time-point retrieval))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/RiskAssessmentResultController.java` (RiskAssessmentResultController — REST endpoints incl. PUT /approval-state (clause 11))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V043__create_risk_assessment.sql` (V043 migration — risk_assessment_result table (no uniqueness constraint over scenario/methodology/date; clauses 1-10, 12, 13))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V044__create_risk_assessment_audit.sql` (V044 migration — risk_assessment_result_audit Envers revision history (clause 14 temporal/versioning))
- IMPLEMENTS → DOCUMENTATION `architecture/notes/risk-assessment-result-preflight.md` (GC-T010 architecture preflight note — boundary, incumbents, cross-cutting layers, gotchas, non-goals (added in #826))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RiskAssessmentResultControllerTest.java` (RiskAssessmentResultControllerTest — @WebMvcTest covering CRUD + approval-state transition endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskAssessmentResultServiceTest.java` (RiskAssessmentResultServiceTest — service-layer mockito unit tests covering link resolution, multi-observation handling, project-scoped validation, approval-state transitions)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/MigrationSmokeTest.java` (MigrationSmokeTest — Flyway integration smoke covering risk_assessment_result + audit + observation join tables created by V043/V044)
- IMPLEMENTS → GITHUB_ISSUE `826` (Verify GC-T010 (Risk Assessment Result Entity): clause-by-clause audit, transition DRAFT→ACTIVE)

---
id: GC-T001
title: "Risk Register Record"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T19:31:39.668766Z
updated_at: 2026-05-10T23:04:54.669826Z
---

# GC-T001 — Risk Register Record

## Statement

The system shall support a Risk Register Record entity representing the governance and decision record for a risk scenario or set of related risk scenarios. A risk record shall include canonical scenario reference, affected operational asset or asset-group context, methodology-agnostic category tags, owner, status (identified, analyzing, assessed, treating, monitoring, accepted, closed), review cadence, linked controls, linked treatments, linked evidence and findings, and decision metadata. Quantitative or qualitative assessment values shall be stored in linked risk assessment results rather than conflated into the register record itself.

## Rationale

FAIR, NIST, and ISO-compatible workflows all need a persistent management record, but they do not define risk identically. Separating the risk register record from the scenario and the assessment result avoids collapsing governance state, semantic scope, and measurement output into one ambiguous entity while preserving asset context.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#256` (GC-T001: Risk Entity)
- DOCUMENTS → GITHUB_ISSUE `823` (Verify GC-T001 (Risk Register Record): clause-by-clause audit, transition DRAFT→ACTIVE)
- DOCUMENTS → DOCUMENTATION `architecture/notes/risk-register-record-preflight.md` (GC-T001 Codex architecture preflight note)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/RiskRegisterRecord.java` (RiskRegisterRecord entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/RiskRegisterStatus.java` (RiskRegisterStatus lifecycle enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/RiskRegisterRecordService.java` (RiskRegisterRecordService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/repository/RiskRegisterRecordRepository.java` (RiskRegisterRecordRepository)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/RiskRegisterRecordController.java` (RiskRegisterRecordController)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/RiskRegisterRecordRequest.java` (RiskRegisterRecordRequest DTO)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/RiskRegisterRecordResponse.java` (RiskRegisterRecordResponse DTO)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/UpdateRiskRegisterRecordRequest.java` (UpdateRiskRegisterRecordRequest DTO)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/RiskRegisterStatusTransitionRequest.java` (RiskRegisterStatusTransitionRequest DTO)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V043__create_risk_assessment.sql` (V043 — risk_register_record + risk_assessment_result + treatment_plan tables)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RiskRegisterRecordControllerTest.java` (RiskRegisterRecordControllerTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RiskRegisterRecordResponseTest.java` (RiskRegisterRecordResponseTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskRegisterRecordServiceTest.java` (RiskRegisterRecordServiceTest)

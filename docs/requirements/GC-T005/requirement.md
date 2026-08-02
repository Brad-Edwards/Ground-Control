---
id: GC-T005
title: "Risk Appetite and Tolerance Profiles"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-14T19:33:42.995170Z
updated_at: 2026-06-22T15:18:55.370682Z
---

# GC-T005 — Risk Appetite and Tolerance Profiles

## Statement

The system shall support organizational risk appetite statements and tolerance thresholds using methodology-appropriate semantics, including qualitative criteria and quantitative thresholds such as monetary loss ranges, loss event frequency, or exceedance probability. Appetite profiles shall be queryable, versioned, and evaluable against risk assessment results and risk register records.

## Rationale

Appetite and tolerance only make sense relative to the semantics of the assessment method. FAIR-oriented programs often need monetary and probabilistic thresholds, while NIST and ISO-style programs may use qualitative or semi-quantitative criteria.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#260` (GC-T005: Risk Appetite & Tolerance)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskappetite/model/RiskAppetiteProfile.java` (RiskAppetiteProfile aggregate (versioned appetite + tolerance thresholds))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskappetite/service/RiskAppetiteProfileService.java` (RiskAppetiteProfileService (validation, versioning, effective-window overlap))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/riskappetite/RiskAppetiteProfileController.java` (RiskAppetiteProfileController (CRUD REST surface))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/RiskAppetiteEvaluationService.java` (RiskAppetiteEvaluationService (residual-vs-tolerance evaluation, escalation flags))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/RiskAppetiteProfileControllerTest.java` (RiskAppetiteProfileController @WebMvcTest slice)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskAppetiteProfileServiceTest.java` (RiskAppetiteProfileService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskAppetiteEvaluationServiceTest.java` (RiskAppetiteEvaluationService unit tests)

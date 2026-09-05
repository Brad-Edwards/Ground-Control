---
id: GC-T014
title: "NIST SP 800-30 Risk Assessment Support"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T01:58:19.330120Z
updated_at: 2026-05-29T02:05:59.779808Z
---

# GC-T014 — NIST SP 800-30 Risk Assessment Support

## Statement

The system shall support NIST SP 800-30 Rev. 1-style risk assessment of risk scenarios, including explicit modeling of threat sources, threat events, vulnerabilities, predisposing conditions, threat source relevance, likelihood of initiation or occurrence, likelihood that a threat event results in adverse impact, overall likelihood, impact level, and assessment timeframe. The model shall support both adversarial and non-adversarial threat events.

## Rationale

Explicit NIST support requires more than storing a generic likelihood and impact pair. SP 800-30 distinguishes threat source, threat event, vulnerabilities, predisposing conditions, and multiple likelihood dimensions, and these concepts need first-class representation if the platform claims NIST compatibility.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `721` (GC-T014: NIST SP 800-30 Risk Assessment Support)
- IMPLEMENTS → PULL_REQUEST `1054` (added: gc-t014 NIST SP 800-30 risk-assessment view)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js`
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js`
- IMPLEMENTS → CODE_FILE `tools/policy/checks.py`

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/ThreatSourceRelevance.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/NistAssessmentService.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/NistAssessmentResult.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/grcanalysis/GrcAnalysisController.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/grcanalysis/NistAssessmentResponse.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/GrcAnalysisService.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/MethodologyProfileService.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/ThreatEventKind.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/NistLikelihoodBand.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/NistImpactBand.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V128__update_nist_methodology_profile_schema.sql`
- IMPLEMENTS → CODE_FILE `frontend/src/types/api.ts`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/grcanalysis/NistAssessmentServiceTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GrcAnalysisControllerTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/grcanalysis/GrcAnalysisIntegrationTest.java`
- TESTS → TEST `mcp/ground-control/gc-analyze.test.js`

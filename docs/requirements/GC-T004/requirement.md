---
id: GC-T004
title: "Risk Treatment Plans"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-14T19:32:15.341717Z
updated_at: 2026-05-26T07:40:18.155900Z
---

# GC-T004 — Risk Treatment Plans

## Statement

The system shall support risk treatment plans linked to risk register records and, when applicable, the underlying risk scenarios, operational asset scope, and controls implementing mitigation. Treatment plans shall support strategies such as mitigate, accept, transfer, share, avoid, or methodology-specific equivalents, action items with owner and due date, status tracking, and triggers for reassessment when treatment progress, asset state, or control state changes.

## Rationale

FAIR, NIST, and ISO-style workflows all require a formal mechanism for moving from analysis to decision and action. Treatment plans should operate on risk records and scenarios in the context of the actual operational objects and controls being changed.

## Traceability

- DOCUMENTS → DOCUMENTATION `architecture/notes/risk-treatment-plan-preflight.md` (Risk Treatment Plan Preflight (architecture guardrails))
- DOCUMENTS → DOCUMENTATION `architecture/notes/risk-treatment-plan-verification.md` (GC-T004 Risk Treatment Plans — Verification Record)
- DOCUMENTS → GITHUB_ISSUE `861` (GC-T004 / C5: typed methodology-strategy binding for TreatmentStrategy.OTHER)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ControlLinkServiceTest.java` (ControlLinkServiceTest — per-internal-type rejection coverage (GC-T004 / C4))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/GraphTargetResolverServiceTest.java` (GraphTargetResolverServiceTest — validateControlTarget per-internal-type rejection (GC-T004 / C4))
- DOCUMENTS → GITHUB_ISSUE `862` (GC-T004 / C6: typed action-item shape with explicit owner / dueDate / status)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/shared/JacksonTextCollectionConvertersTest.java` (ActionItemListConverter tests - roundtrip + V043 legacy compat + key folding (GC-T004 / C6))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TreatmentPlanServiceTest.java` (TreatmentPlanServiceTest - bypass-write validation guard tests (GC-T004 / C6))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/TreatmentPlanControllerTest.java` (TreatmentPlanControllerTest - REST @Valid + ArgumentCaptor plumbing tests (GC-T004 / C6))
- TESTS → TEST `mcp/ground-control/gc-risk-governance.test.js` (gc_risk_governance adapter tests - typed action_items wire-body + nested camelCase (GC-T004 / C6))
- IMPLEMENTS → GITHUB_ISSUE `863` (GC-T004 / C8: categorised reassessment triggers + event publisher / listener wiring)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/ReassessmentSignalService.java` (ReassessmentSignalService — synchronous @EventListener writing reassessmentRequiredAt (GC-T004 / C8))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/ReassessmentTrigger.java` (ReassessmentTrigger — typed (category, optionalTargetRef) value record (GC-T004 / C8))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V126__add_risk_assessment_result_reassessment_required_at.sql` (V126 — risk_assessment_result.reassessment_required_at signal column (GC-T004 / C8))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ReassessmentSignalServiceTest.java` (ReassessmentSignalServiceTest — listener-level coverage of bounded traversal (GC-T004 / C8))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/ReassessmentSignalIntegrationTest.java` (ReassessmentSignalIntegrationTest — end-to-end coverage through @SpringBootTest (GC-T004 / C8))
- IMPLEMENTS → GITHUB_ISSUE `#259` (GC-T004: Risk Treatment Plans)
- DOCUMENTS → GITHUB_ISSUE `825` (Verify GC-T004 (Risk Treatment Plans): clause-by-clause audit, transition DRAFT→ACTIVE)
- DOCUMENTS → GITHUB_ISSUE `860` (GC-T004 / C4: project-scoped ControlLink target validation via GraphTargetResolverService)

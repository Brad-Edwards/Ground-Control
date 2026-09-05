---
id: GC-T016
title: "FAIR Materiality and Loss Taxonomy Support"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-30T01:58:19.526230Z
updated_at: 2026-08-02T18:39:44.325088Z
---

# GC-T016 — FAIR Materiality and Loss Taxonomy Support

## Statement

The system shall support FAIR-aligned loss modeling with explicit primary and secondary loss distinctions, FAIR loss forms, and optional FAIR-MAM-aligned extensions for more granular materiality analysis. FAIR assessment outputs shall be able to express monetary loss ranges, percentile summaries, stakeholder-specific secondary effects, and linkage between materiality analysis and the originating risk scenario.

## Rationale

Explicit FAIR support is incomplete if the platform can estimate frequency but cannot represent FAIR-style loss magnitude and materiality reasoning. FAIR-MAM expands the loss side of the model and is important for executive decision support and defensible financial quantification.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#745` (GC-T016: FAIR Materiality and Loss Taxonomy Support)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/FairQuantitativeAnalysisService.java` (FAIR materiality/loss-form decomposition + stakeholder secondary effects)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/FairQuantitativeAnalysisResult.java` (Materiality / LossFormBreakdown / StakeholderSecondaryLoss output records)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/grcanalysis/FairQuantitativeAnalysisResponse.java` (Materiality view on the FAIR analysis API response)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V138__extend_fair_schema_materiality.sql` (FAIR_V3_0 schema extension: materiality + secondary_loss_by_stakeholder vocabulary)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/grcanalysis/FairQuantitativeAnalysisServiceTest.java` (Materiality decomposition + stakeholder + currency-exclusion + ALE-unaffected tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/GrcAnalysisControllerTest.java` (@WebMvcTest slice asserting materiality fields serialize through the API)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/FairFormOfLoss.java` (FairFormOfLoss enum (O-RT six forms of loss, The Open Group))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/grcanalysis/FairFormOfLossTest.java` (FairFormOfLoss 6-form + fromJsonKey tests)

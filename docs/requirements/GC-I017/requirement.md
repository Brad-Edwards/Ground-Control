---
id: GC-I017
title: "FAIR-CAM Control Analytics Support"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 5
created_at: 2026-03-30T01:58:19.629066Z
updated_at: 2026-08-02T18:39:44.325102Z
---

# GC-I017 — FAIR-CAM Control Analytics Support

## Statement

The system shall support FAIR-CAM-aligned control analytics, including representation of controls in the Loss Event Control, Variance Management Control, and Decision Support Control domains, with measurements for control capability, coverage, operational performance, and methodology-specific effect on risk factors or decision quality. Controls shall be analyzable in terms of how they affect loss event frequency, loss magnitude, control reliability, or decision alignment.

## Rationale

Explicit FAIR support requires explicit control physiology, not just a generic effectiveness label. FAIR-CAM provides the structure needed to understand how controls change risk and to integrate control measurement with quantitative analysis.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/grcanalysis/service/FairCamControlAnalyticsService.java` (FairCamControlAnalyticsService (FAIR-CAM control analytics))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/grcanalysis/GrcAnalysisController.java` (GrcAnalysisController fair-cam-control-analytics endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/grcanalysis/FairCamControlAnalyticsServiceTest.java` (FairCamControlAnalyticsServiceTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/grcanalysis/FairCamControlAnalyticsResponseTest.java` (FairCamControlAnalyticsResponseTest)
- IMPLEMENTS → GITHUB_ISSUE `#746` (GC-I017: FAIR-CAM Control Analytics Support)

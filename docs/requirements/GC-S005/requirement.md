---
id: GC-S005
title: "Scheduled Evidence Collection"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T16:56:12.031945Z
updated_at: 2026-07-01T02:41:10.977746Z
---

# GC-S005 — Scheduled Evidence Collection

## Statement

The system shall support scheduling evidence collection campaigns with configurable frequency (daily, weekly, monthly, quarterly), scope (which adapters and which evidence types), and retention policies. Campaign results shall be stored as evidence artifacts linked to the relevant controls and frameworks.

## Rationale

Compliance requires evidence collected at regular intervals, not just ad-hoc. Scheduled collection ensures evidence freshness and completeness without manual intervention — the agent runs the campaign automatically.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#214` (GC-S005: Scheduled Evidence Collection)
- DOCUMENTS → DOCUMENTATION `architecture/adrs/074-scheduled-evidence-collection.md` (ADR-074: Scheduled Evidence Collection Campaigns)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/evidencecampaign/EvidenceCampaignController.java` (EvidenceCampaignController — REST surface for scheduled evidence-collection campaigns)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/campaign/service/EvidenceCampaignService.java` (EvidenceCampaignService — scheduling, sweep claim/execute, retention pruning, control-linked evidence)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/campaign/model/EvidenceCampaign.java` (EvidenceCampaign aggregate — frequency, scope, target controls, retention policy)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/campaign/EvidenceCampaignRunner.java` (EvidenceCampaignRunner — scheduled due-campaign sweep and retention prune ticks)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/evidence/EvidenceCampaignServiceTest.java` (EvidenceCampaignServiceTest — lifecycle, sweep claim/coalesce/pause-race, SSRF pin, retention, redaction)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/evidencecampaign/EvidenceCampaignControllerTest.java` (EvidenceCampaignControllerTest — REST surface positive/negative paths (@WebMvcTest))

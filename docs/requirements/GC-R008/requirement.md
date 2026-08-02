---
id: GC-R008
title: "Third-Party Continuous Monitoring"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-14T16:55:39.750259Z
updated_at: 2026-03-30T02:56:40.750255Z
---

# GC-R008 — Third-Party Continuous Monitoring

## Statement

The system shall support continuous monitoring of third-party risk through event-driven triggers such as contract expiry, certification lapse, incident report, external risk score change, integration change, or materially relevant observations about linked third-party assets or dependencies that automatically initiate reassessment workflows or update risk profiles.

## Rationale

Point-in-time vendor assessments miss inter-assessment risk changes. Continuous monitoring becomes materially stronger when it is grounded in observed changes to the third-party dependency surface, not only in questionnaire refresh cycles.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#207` (GC-R008: Third-Party Continuous Monitoring)

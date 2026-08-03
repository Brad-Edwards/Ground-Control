---
id: GC-T006
title: "Risk Assessment Workflow"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-14T19:33:50.705340Z
updated_at: 2026-07-12T23:08:15.210383Z
---

# GC-T006 — Risk Assessment Workflow

## Statement

The system shall support structured risk assessment campaigns including scenario scoping to operational assets and boundaries, methodology profile selection, factor or criteria collection, observation and evidence review, assessment execution, evaluation against appetite or tolerance, and treatment selection. Campaigns shall have a state machine with review and approval gates and shall support running different methodologies against the same underlying risk scenarios without semantic loss.

## Rationale

Risk assessment workflows must remain valid whether the organization is using FAIR quantitative analysis, NIST SP 800-30-style qualitative assessment, ISO-aligned criteria, or a hybrid. Asset and evidence scoping are part of the workflow, not optional afterthoughts.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#261` (GC-T006: Risk Assessment Workflow)

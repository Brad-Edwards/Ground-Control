---
id: GC-T007
title: "Risk Monitoring & KRIs"
status: DEPRECATED
type: FUNCTIONAL
priority: COULD
wave: 5
created_at: 2026-03-14T19:33:58.265561Z
updated_at: 2026-07-12T23:08:15.210403Z
---

# GC-T007 — Risk Monitoring & KRIs

## Statement

The system shall support Key Risk Indicator (KRI) definition with threshold-based monitoring. KRIs shall be linked to risks and have configurable thresholds (green/yellow/red). KRI breaches shall trigger risk reassessment workflows or notifications via GC-P004. Agent-driven continuous risk monitoring shall be supported via MCP tools.

## Rationale

Point-in-time risk assessments decay. KRIs provide continuous risk monitoring between formal assessments. Agent-consumable KRIs enable autonomous risk posture monitoring — a key differentiator over manual GRC tools.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#262` (GC-T007: Risk Monitoring & KRIs)

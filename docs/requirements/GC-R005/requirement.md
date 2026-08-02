---
id: GC-R005
title: "Questionnaire Instance and Response Tracking"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-14T16:55:28.050529Z
updated_at: 2026-03-14T16:55:28.050529Z
---

# GC-R005 — Questionnaire Instance and Response Tracking

## Statement

The system shall support questionnaire instances created from templates, linked to a specific third party and assessment campaign. Each instance shall track: assignment date, due date, completion status, per-question responses, and overall score. Partial saves and incremental completion shall be supported.

## Rationale

A template defines the questions; an instance captures a specific vendor's answers. Agents need to track completion status and extract structured responses for automated scoring and risk assessment.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#204` (GC-R005: Questionnaire Instance and Response Tracking)

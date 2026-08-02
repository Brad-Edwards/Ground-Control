---
id: GC-C011
title: "CI/CD Traceability Non-Regression Gate"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-03-14T01:24:26.071147Z
updated_at: 2026-03-14T01:24:26.071147Z
---

# GC-C011 — CI/CD Traceability Non-Regression Gate

## Statement

The system shall provide a CI/CD-integrable check that compares traceability coverage before and after a proposed change, failing when coverage decreases (e.g., a PR removes an IMPLEMENTS link without replacing it, or deletes a linked test without relinking).

## Rationale

Traceability links decay when code changes happen without corresponding link updates. A non-regression gate prevents merging changes that silently degrade traceability coverage. Gating on non-regression rather than perfection allows incremental adoption without blocking all work on day one.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#685` (GC-C011: CI/CD Traceability Non-Regression Gate)

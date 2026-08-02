---
id: GC-J006
title: "Architecture Enforcement Coverage Analysis"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-14T18:37:42.014328Z
updated_at: 2026-03-14T18:37:42.014328Z
---

# GC-J006 — Architecture Enforcement Coverage Analysis

## Statement

The system shall provide architecture enforcement coverage analysis identifying: (a) ADRs with no linked constraint requirements, (b) architectural constraint requirements with no linked enforcement tests, (c) enforcement tests with no recent passing verification results, and (d) the overall ratio of enforced-to-unenforced architectural decisions. Results shall be exposed via both REST API and MCP tools.

## Rationale

An ADR without an enforcement test is a decision that can be violated silently. An enforcement test that hasn't run recently provides false confidence. This analysis surfaces the "drift risk surface" -- the set of architectural decisions that are documented but unprotected. It extends GC-C003's coverage gap analysis from "requirements without traceability links" to "architectural decisions without enforcement." For agents, this enables autonomous identification of architecture governance gaps.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#236` (GC-J006: Architecture Enforcement Coverage Analysis)

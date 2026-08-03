---
id: GC-F009
title: "AI-Assisted Specification Generation"
status: DRAFT
type: FUNCTIONAL
priority: COULD
wave: 5
created_at: 2026-03-14T18:38:18.730499Z
updated_at: 2026-03-14T18:38:18.730499Z
---

# GC-F009 — AI-Assisted Specification Generation

## Statement

The system shall support AI-assisted translation of natural-language requirement statements into candidate formal specifications (TLA+, Dafny, JML, or Alloy), presenting candidates for human review and approval before linking to the originating requirement. Generated specifications shall be stored as SPEC artifacts with provenance metadata (model used, prompt, confidence score) and shall require human confirmation before being treated as verification targets.

## Rationale

The compass analysis identifies NL-to-formal-spec translation as "the critical UX layer that determines adoption." ADR-014 notes this as a future "Verification UX" concern. Human-in-the-loop approval prevents specification errors from becoming silent correctness assumptions. This is the platform's end-game differentiator: making formal methods accessible to developers who cannot write TLA+ directly.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#242` (GC-F009: AI-Assisted Specification Generation)

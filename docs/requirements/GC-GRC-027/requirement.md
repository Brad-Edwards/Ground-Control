---
id: GC-GRC-027
title: "Breadth Derivation Adapter (Semgrep and Additional Languages)"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T23:26:01.303520Z
updated_at: 2026-07-12T16:35:41.563841Z
---

# GC-GRC-027 — Breadth Derivation Adapter (Semgrep and Additional Languages)

## Statement

The derivation layer shall provide breadth coverage beyond the CodeQL adapter's core languages, implementing the GC-GRC-001 port.

(a) A Semgrep-based adapter shall provide taint and pattern derivation for languages and surfaces CodeQL does not cover well, and shall accept custom, project- or org-authored rule sets.

(b) Together with the CodeQL adapter, supported languages shall extend to cover the common polyglot set (for example, Go, Ruby, C#, C/C++, Kotlin, Swift, PHP, Rust) to the extent any available analyzer permits.

(c) When multiple adapters cover the same surface, their facts shall be merged and de-duplicated into the single normalized model, with each retained fact keeping its own provenance.

(d) Languages and surfaces still uncovered after all adapters run shall be recorded as capture limits per GC-GRC-025 — breadth never silently implies completeness.

## Rationale

Ground Control consumers are mixed-language; CodeQL's core set is not enough. Semgrep adds language breadth and custom-rule authoring, and the merge-with-provenance contract lets multiple engines reinforce coverage without double-counting. Boiling the ocean on coverage is the point — declination is the floor, not the target.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1141` (Issue #1141: GC-GRC-027 breadth derivation adapter (Semgrep and additional languages))

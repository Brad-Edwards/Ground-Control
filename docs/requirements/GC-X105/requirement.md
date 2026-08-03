---
id: GC-X105
title: "Structured cycle-3 escalation decision aid"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-05-09T18:38:58.724923Z
updated_at: 2026-05-09T18:38:58.724923Z
---

# GC-X105 — Structured cycle-3 escalation decision aid

## Statement

When a `/implement` run reaches cycle 3 of `gc_codex_review` and the GC-X102 exit gates are not satisfied at cycle 3 completion, the escalation comment posted to the issue thread per GC-O007 shall present a structured decision aid containing: (a) the cycle-by-cycle severity-weighted scores from GC-X103 and the cycle-N → cycle-N+1 decay ratios, (b) the set of finding categories surfaced in each cycle and which categories first appeared in cycle 3, (c) a projected cycle-4 severity-weighted yield computed by extrapolating the observed decay trend, (d) the count of unconfirmed `Critical` findings per GC-X104 still pending second-reviewer confirmation, and (e) a recommended user action drawn from {`approve_cap_override`, `accept_remaining_findings_as_wontfix_with_rationale`, `stop_run_and_open_new_issue`} with the signals supporting that recommendation. The comment shall present this decision aid as a structured marker block (parseable by the MCP server) in addition to the human-readable rendering. The user retains full authority over the decision; the requirement is that the decision input be signal, not free-text vibes.

## Rationale

Cost-benefit stopping (Freimut, Briand, Vollei IEEE TSE 31(12), 2005; Kemerer & Paulk IEEE TSE 2009) is the framework that justifies all the other stopping rules — the dominant cost in this workflow is the user's time on false positives, not the agent's compute. Replacing the user's "is this round of findings serious enough" vibes with severity decay + categorical novelty + cost-of-escape signals improves the override-or-stop decision without sacrificing user authority. This requirement is the surface that makes the rest of the model valuable to the user as opposed to merely auditable.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/031-codex-review-stopping-model.md` (ADR-031: Severity Rubric and Stopping Model for Pre-Push Codex Review)
- DOCUMENTS → GITHUB_ISSUE `818` (Severity-weighted early-stop + structured cycle-3 escalation decision aid)

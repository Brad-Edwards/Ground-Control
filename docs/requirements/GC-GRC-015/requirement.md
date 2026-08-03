---
id: GC-GRC-015
title: "Disposition and Risk Acceptance Model"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:26:33.593931Z
updated_at: 2026-07-12T16:35:41.562882Z
---

# GC-GRC-015 — Disposition and Risk Acceptance Model

## Statement

Declining identified GRC work shall require explicit user authorization and shall produce durable artifacts.

(a) An accepted risk shall materialize as a risk-register record plus an ACCEPT treatment plan carrying the rationale, the authorizing user, and an owner.

(b) All dispositions (accept, wontfix, not-applicable) shall be recorded both on the issue thread and in the project graph, attached to the dispositioned entity.

(c) Gates (GC-GRC-012) shall honor recorded dispositions so authorized acceptances do not re-block subsequent runs, and shall re-open them if the dispositioned surface changes materially (per drift detection).

(d) There shall be no silent skip path: every identified-but-not-done item ends in either completed work or a recorded, authorized disposition.

## Rationale

Blocking gates need a legitimate relief valve or they get gamed; an audited acceptance with an owner and a register entry is the GRC-correct relief valve. Re-opening on material change keeps acceptances from becoming permanent blind spots.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1128` (Issue #1128: GC-GRC-015 disposition and risk acceptance model)

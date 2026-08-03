---
id: GC-GRC-020
title: "Sensitive Data Flow Protection (PII and Secrets)"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:27:16.842436Z
updated_at: 2026-07-12T16:35:41.563572Z
---

# GC-GRC-020 — Sensitive Data Flow Protection (PII and Secrets)

## Statement

Derived taint paths from sensitive sources to sinks shall be evaluated against the project's lattice policy.

(a) Sensitive sources shall include data labeled PII, credentials/secrets, and regulated classes per the lattice (GC-GRC-006); sinks shall include logs, third-party calls, build artifacts, telemetry, and any boundary-crossing egress.

(b) A lattice-violating flow introduced by a change shall be a blocking finding in the implementation loop (surfaced through the GC-GRC-012 gate).

(c) The architecture model shall maintain a queryable inventory of sensitive flows (where PII and secrets travel) kept current by the drift loop.

(d) Pipeline secret exposure shall be covered through the IaC adapter facts (GC-GRC-003), including secret scope widening and secrets reaching untrusted runners or artifacts.

## Rationale

PII leaks and secret exposure are the highest-consequence failure classes this program exists to prevent. Taint analysis plus lattice policy makes them derivable, blockable findings rather than things an LLM might notice.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1133` (Issue #1133: GC-GRC-020 sensitive data flow protection (PII and secrets))

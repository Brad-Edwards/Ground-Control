---
id: GC-GRC-032
title: "Compliance Posture and Evidence Export"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T23:26:43.829034Z
updated_at: 2026-07-12T16:35:41.563970Z
---

# GC-GRC-032 — Compliance Posture and Evidence Export

## Statement

The system shall generate auditor-facing compliance posture and evidence artifacts from the GRC graph.

(a) Coverage chains (surface → threat → control → efficacy test → evidence) shall be exportable, mapped to installed control-framework catalogs (OSCAL: NIST SP 800-53/800-171/800-218 and others) via the existing control-pack framework mappings.

(b) Exports shall report, per framework control: covered / partially covered / gap / accepted-with-disposition, each traceable to the graph elements that substantiate it.

(c) Export formats shall include a machine-readable form (e.g., OSCAL assessment results / CycloneDX VEX where applicable) and a human-readable report; exports shall carry generation markers tying them to the graph snapshot they were produced from.

(d) Exports shall draw on the existing evidence and audit aggregates and shall not require any GRC content to live in the analyzed repository.

## Rationale

Secure-by-design is only provable if the posture is exportable as evidence. Generating framework-mapped attestations from the same graph that gates enforce closes the loop from engineering control to audit assurance, and reuses the OSCAL/control-pack and evidence/audit machinery already in the system.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1146` (Issue #1146: GC-GRC-032 compliance posture and evidence export)

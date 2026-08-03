---
id: GC-I005
title: "Cross-Framework Control Mapping"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 5
created_at: 2026-03-14T16:56:39.828183Z
updated_at: 2026-07-12T23:08:15.210231Z
---

# GC-I005 — Cross-Framework Control Mapping

## Statement

The system shall support many-to-many mapping between controls and compliance framework requirements across multiple frameworks simultaneously. A single control shall be mappable to requirements in SOC 2, ISO 27001, SOX, NIST CSF, and PCI-DSS concurrently, with per-mapping metadata (coverage level: full, partial, compensating).

## Rationale

Organizations implement controls once but must demonstrate compliance across multiple frameworks. Without cross-framework mapping, each framework requires separate control documentation — exactly the redundancy that unified control frameworks (SCF, UCF) exist to eliminate. Agents need this mapping to answer "which frameworks does this control satisfy?" in a single query.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#220` (GC-I005: Cross-Framework Control Mapping)

---
id: GC-E011
title: "Extensible Artifact Type Registry"
status: DRAFT
type: FUNCTIONAL
priority: COULD
wave: 4
created_at: 2026-03-14T18:38:06.224272Z
updated_at: 2026-03-14T18:38:06.224272Z
---

# GC-E011 — Extensible Artifact Type Registry

## Statement

The system shall support a configurable artifact type registry that extends the built-in artifact types (GITHUB_ISSUE, CODE_FILE, ADR, CONFIG, POLICY, TEST, SPEC, PROOF, DOCUMENTATION) with project-defined custom types (e.g., THREAT_MODEL, ARCHITECTURE_MODEL, DESIGN_DOCUMENT), enabling projects to classify artifacts with domain-specific granularity without modifying the core system.

## Rationale

The current ArtifactType enum is a hardcoded set of 9 values. As the platform expands to support threat modeling (H-series), architecture models (J-series), and diverse artifact ecosystems, the enum will need frequent updates. An extensible registry enables projects to define artifact taxonomies that match their domain without core code changes.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#240` (GC-E011: Extensible Artifact Type Registry)

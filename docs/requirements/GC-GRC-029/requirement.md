---
id: GC-GRC-029
title: "Software-Composition and Supply-Chain Derivation Adapter"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T23:26:16.348295Z
updated_at: 2026-07-12T16:35:41.563901Z
---

# GC-GRC-029 — Software-Composition and Supply-Chain Derivation Adapter

## Statement

The system shall provide a software-composition (SCA) and supply-chain derivation adapter implementing the GC-GRC-001 port.

(a) The adapter shall derive the dependency graph and generate or ingest an SBOM (for example, CycloneDX/SPDX) for the project's package ecosystems and container images.

(b) Derived facts shall include third-party components as external entities with known-vulnerability associations, license posture, and provenance/integrity signals (for example, pinning, signatures, lockfile presence).

(c) Supply-chain threat categories (vulnerable dependency, unpinned/floating dependency, typosquat/namespace risk, build-tool and CI dependency exposure, container base-image risk) shall be enumerable by the GC-GRC-007 rules over these facts.

(d) The adapter shall reconcile platform dependency alerts (for example, Dependabot) into the facts model rather than maintaining a separate list, and shall feed the asset/operational-asset graph where components are deployed artifacts.

## Rationale

Vulnerable and malicious dependencies are a primary breach class entirely outside first-party taint analysis — and this repo already carries dozens of open dependency alerts. An SBOM-backed component graph makes supply-chain risk a derived, enumerable, trackable part of the same model instead of a siloed scanner output.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1143` (Issue #1143: GC-GRC-029 software-composition and supply-chain derivation adapter)

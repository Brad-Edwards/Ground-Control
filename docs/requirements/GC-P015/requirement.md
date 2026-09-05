---
id: GC-P015
title: "Control Pack Distribution and Installation"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-04-05T19:29:24.357706Z
updated_at: 2026-04-09T04:25:54.908759Z
---

# GC-P015 — Control Pack Distribution and Installation

## Statement

The system shall support versioned control packs as installable content bundles containing control definitions, control metadata, framework mappings, implementation guidance, expected evidence patterns, and related pack metadata. Control packs shall be loadable into a project idempotently, preserve source provenance, support version-aware updates and deprecations, and allow organization- or repo-level tailoring without copying the entire originating control catalog.

## Rationale

Reusable control content is the operational unit teams actually adopt: platform baselines, regulatory baselines, and domain-specific assurance bundles. First-class control packs make curated control catalogs portable and maintainable while preserving upgrade paths, provenance, and local customization.

## Traceability

- DOCUMENTS → ADR `ADR-022` (Content Pack Distribution Architecture)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tool definitions for control packs)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP client transport for control pack operations)

## Historical traceability

Links below named artifacts the #1500 re-platform deleted. They are kept for
provenance and are outside the parsed `## Traceability` section, so no tool reads
them as live evidence. Do not infer current implementation from them.

- DOCUMENTS → DOCUMENTATION `docs/API.md` (Control pack API and registry-backed install documentation)
- IMPLEMENTS → CONFIG `.github/workflows/pack-registry-sync.yml` (GitHub Actions pack registry sync workflow)
- IMPLEMENTS → CODE_FILE `scripts/pack-sync.sh` (Local GitHub workflow dispatcher for pack sync)
- IMPLEMENTS → CODE_FILE `tools/packs/sync_packs.mjs` (Repo-native control pack sync CLI)
- IMPLEMENTS → CODE_FILE `tools/packs/pack_catalog.mjs` (Pack catalog integrity helper)
- TESTS → TEST `tools/packs/pack_catalog.test.mjs` (Pack sync decision tests)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controlpacks/model/ControlPack.java` (ControlPack entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controlpacks/model/ControlPackEntry.java` (ControlPackEntry entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controlpacks/model/ControlPackOverride.java` (ControlPackOverride entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controlpacks/state/ControlPackLifecycleState.java` (ControlPackLifecycleState enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/controlpacks/service/ControlPackService.java` (ControlPackService - install, upgrade, deprecate, tailoring)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/controlpacks/ControlPackController.java` (ControlPackController - REST API endpoints)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V052__create_control_pack.sql` (Migration V052 - control pack tables)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/ControlPackControllerTest.java` (ControlPackController unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ControlPackServiceTest.java` (ControlPackService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ControlPackLifecycleStateTest.java` (ControlPackLifecycleState unit tests)
- IMPLEMENTS → CONFIG `packs/catalog.json` (Committed control pack catalog)
- DOCUMENTS → DOCUMENTATION `packs/README.md` (Pack catalog operating guide)
- TESTS → TEST `tools/tests/test_pack_catalog.py` (Pack catalog integrity tests)
- IMPLEMENTS → CODE_FILE `deploy/scripts/sync_pack_catalog.sh` (Host-side pack catalog sync script)

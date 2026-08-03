---
id: GC-P016
title: "Pack Registry, Resolution, and Trust Model"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-04-05T19:41:51.411969Z
updated_at: 2026-04-09T14:09:39.787710Z
---

# GC-P016 — Pack Registry, Resolution, and Trust Model

## Statement

The system shall support a registry and resolution model for installable plugins and content packs. The model shall represent pack identity, publisher, semantic version, compatibility constraints, dependency relationships, provenance metadata, and integrity verification material such as checksums or signatures. Pack installation and upgrade workflows shall evaluate trust policy before applying a pack, reject untrusted or incompatible packs, and produce an auditable install record identifying what was installed, from which source, and under what policy decision.

## Rationale

Versioned requirement packs and control packs are only operationally safe if discovery, dependency resolution, compatibility, and trust are first-class concerns. A registry and trust model separates curated portable content from arbitrary imports, enabling repeatable installation across repositories and organizations without sacrificing provenance, upgrade safety, or supply-chain controls.

## Traceability

- DOCUMENTS → ADR `ADR-022` (Content Pack Distribution Architecture)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/packregistry/model/PackRegistryEntry.java` (PackRegistryEntry entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/packregistry/model/PackInstallRecord.java` (PackInstallRecord entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/packregistry/model/TrustPolicy.java` (TrustPolicy entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/packregistry/service/PackRegistryService.java` (PackRegistryService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/packregistry/service/PackResolver.java` (PackResolver - semver resolution and compatibility)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/packregistry/service/TrustEvaluator.java` (TrustEvaluator - trust policy evaluation)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/packregistry/service/TrustPolicyService.java` (TrustPolicyService)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/packregistry/service/PackInstallOrchestrator.java` (PackInstallOrchestrator - registry-aware install with trust gate)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/packregistry/PackRegistryController.java` (PackRegistryController)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/packregistry/TrustPolicyController.java` (TrustPolicyController)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/packregistry/PackInstallRecordController.java` (PackInstallRecordController)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V053__create_pack_registry.sql` (V053 migration - pack registry tables)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tool definitions for pack registry)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/PackRegistryServiceTest.java` (PackRegistryService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/PackResolverTest.java` (PackResolver unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TrustEvaluatorTest.java` (TrustEvaluator unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/TrustPolicyServiceTest.java` (TrustPolicyService unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/PackInstallOrchestratorTest.java` (PackInstallOrchestrator unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/PackRegistryControllerTest.java` (PackRegistryController WebMvc tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/TrustPolicyControllerTest.java` (TrustPolicyController WebMvc tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/PackInstallRecordControllerTest.java` (PackInstallRecordController WebMvc tests)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/packregistry/PackRegistryImportRequest.java` (PackRegistryImportRequest DTO)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/packregistry/service/PackRegistryImportFormat.java` (PackRegistryImportFormat enum)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/packregistry/service/PackRegistryImportOptions.java` (PackRegistryImportOptions)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/packregistry/service/PackRegistryImportService.java` (PackRegistryImportService)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/admin.tsx` (Admin pack registry import UI)
- IMPLEMENTS → CODE_FILE `frontend/src/lib/api-client.ts` (Frontend API client pack import support)
- IMPLEMENTS → CODE_FILE `frontend/src/types/api.ts` (Frontend API types for pack import)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP client pack import transport)
- DOCUMENTS → DOCUMENTATION `docs/API.md` (Pack registry import API documentation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/PackRegistryImportServiceTest.java` (PackRegistryImportService unit tests)
- IMPLEMENTS → CONFIG `packs/catalog.json` (Committed registry pack metadata catalog)
- IMPLEMENTS → CODE_FILE `tools/packs/sync_packs.mjs` (Repo-native registry import and install sync CLI)
- IMPLEMENTS → CONFIG `.github/workflows/pack-registry-sync.yml` (GitHub Actions pack registry auth and sync workflow)
- IMPLEMENTS → CODE_FILE `deploy/scripts/enable_pack_registry_auth.sh` (Host-side pack registry auth bootstrap script)
- IMPLEMENTS → CONFIG `deploy/docker/docker-compose.prod.yml` (Production compose pack registry auth wiring)
- IMPLEMENTS → CONFIG `deploy/terraform/modules/compute/user-data.sh.tftpl` (Compute bootstrap env wiring for pack registry auth)

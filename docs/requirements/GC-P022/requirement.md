---
id: GC-P022
title: "Deployment Image Provenance and Staleness Guard"
status: ACTIVE
type: NON_FUNCTIONAL
priority: SHOULD
wave: 1
created_at: 2026-06-23T06:58:31.727924Z
updated_at: 2026-06-23T15:19:36.059967Z
---

# GC-P022 — Deployment Image Provenance and Staleness Guard

## Statement

The deployment pipeline shall reference a single canonical container-image registry namespace across all deploy and CI artifacts, enforced by a repo-native policy gate, and the deploy process shall fail loudly (non-zero exit) when the resolved image cannot be confirmed to advance — specifically when the pulled image lacks an `org.opencontainers.image.revision` label, or when that revision equals the currently-running container's revision (absent an explicit operator override) — preventing silent stale-image deploys.

## Rationale

red-dragon silently served a stale build for ~10 days (#953): the CI publish namespace diverged from the deploy-host image pin, so `docker compose pull` kept resolving a frozen image while the still-healthy old container kept the deploy script's health check green, hiding the staleness. A canonical-namespace policy gate makes namespace drift fail at `make policy` time, and a revision-advance guard in the deploy script makes a non-advancing pull fail loudly at deploy time, closing both halves of the silent-stale failure mode.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/030-on-prem-hetzner-deployment.md` (ADR-030 On-prem Hetzner deployment — canonical GHCR namespace and deploy path GC-P022 governs)
- IMPLEMENTS → CODE_FILE `deploy/docker/deploy.sh` (Deploy-time staleness guard: revision-advance check before docker compose up)
- IMPLEMENTS → CODE_FILE `tools/policy/checks.py` (run_ghcr_namespace_drift — make policy canonical-namespace gate)
- IMPLEMENTS → GITHUB_ISSUE `953` (#953 Deploy image-namespace mismatch + silent stale deploys)
- DOCUMENTS → ADR `architecture/adrs/063-release-deployment-model.md` (ADR-063 Release & Deployment Model — immutable digest-pinned release artifact; floating :main is not a release (GC-P022 provenance domain))
- TESTS → TEST `tools/tests/test_policy_repo_identity_drift.py` (run_ghcr_namespace_drift tests: passes-on-repo, fires-on-drift, accepts-canonical)

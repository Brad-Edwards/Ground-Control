---
id: GC-P023
title: "Deployment Reliability: Artifact Drift Detection, Env Validation, Rollback, and Deploy Observability"
status: ACTIVE
type: NON_FUNCTIONAL
priority: SHOULD
created_at: 2026-06-23T21:37:21.002038Z
updated_at: 2026-06-25T17:27:30.004531Z
---

# GC-P023 — Deployment Reliability: Artifact Drift Detection, Env Validation, Rollback, and Deploy Observability

## Statement

The Ground Control operator-driven deployment (ADR-030) shall provide deployment reliability and observability controls, enforced by a repo-native policy gate (make policy / bin/policy) and the canonical deploy script:

(a) Single source of truth + drift detection. The deploy artifacts (deploy script, production compose file, env template, env schema) shall have one canonical repo source with no divergent second copy of the deploy logic, and a deploy shall fail loudly (non-zero exit) before rollout when the deploy-host runtime mirror (/opt/gc/) drifts from the canonical repo artifacts.

(b) Environment validation. Each deploy shall validate the deploy-host environment file against the committed environment schema before restarting the backend — confirming required variables are present and well-formed, that GC_IMAGE is an immutable versioned release pin (a semver image tag such as ...:X.Y.Z / ...:X.Y) rather than a floating branch tag (:main, :latest, :dev) or an untagged reference — with a deliberate digest pin permitted only under an explicit operator override (GC_ALLOW_IMAGE_PIN=1) for a controlled rollback/cutover — and that ADR-026 indexed credential slots are fully populated or fully absent — and shall never print secret values.

(c) Automatic rollback. A failed rollout (candidate fails readiness within the health window) shall automatically restore the previous image/container, and shall never leave a newly unhealthy backend as the steady state.

(d) Deploy observability. Each deploy shall resolve and publish the rolled-out image digest and source commit SHA to a queryable surface (GitHub Deployments) answerable without SSH access to the deploy host, and the published record shall carry no secrets.

(e) Single schema source. The environment schema shall be one committed source consumed by both the repo policy gate and the deploy-time validator, with no schema duplication across shell, compose, and documentation.

## Rationale

Issue #855: the red-dragon deploy has silently broken multiple times (digest-pinned GC_IMAGE froze rollouts; a tailnet-bind change broke the host health check for ~30 days; a private GHCR package failed pulls; /opt/gc/.env schema drift was invisible until backend startup failure). #953 (GC-P022) closed the image-provenance/staleness half. This requirement closes the rest: the repo carries two divergent deploy scripts (one with a broken curl health check), two contradictory env templates, and no env validation, rollback, drift gate, or queryable deploy record. Sibling to GC-P022; both harden the operator-driven ADR-030 path per the 2026-06-23 ADR-030 amendment rather than migrating topology. GC-GRC-003 (deterministic pipeline screening) is the future automated-classification home and is out of scope here.

## Traceability

- DOCUMENTS → ADR `ADR-030` (On-prem Hetzner deployment (2026-06-23 CI/CD hardening amendment + #855 implementation note))
- TESTS → TEST `tools/tests/test_validate_env.py` (validate-env.sh tests incl. secret-non-leakage)
- IMPLEMENTS → CODE_FILE `deploy/docker/deploy.sh` (Canonical deploy script: env validation, drift refusal, rollback, deploy-state (clauses a-d))
- IMPLEMENTS → CONFIG `deploy/docker/env.schema` (Committed env schema — single source for policy gate + deploy validator (clauses b, e))
- IMPLEMENTS → CODE_FILE `deploy/docker/validate-env.sh` (Deploy-time env validator; reports var names only, never secret values (clause b))
- IMPLEMENTS → CONFIG `deploy/docker/MANIFEST.sha256` (Canonical deploy-artifact digest manifest for drift detection (clause a))
- IMPLEMENTS → CODE_FILE `scripts/deploy.sh` (Host-aware deploy wrapper: artifact sync + GitHub Deployment publish (clauses a, d))
- IMPLEMENTS → CODE_FILE `tools/policy/checks.py` (run_deploy_artifact_consistency repo-native drift/schema policy gate (clauses a, e))
- TESTS → TEST `tools/tests/deploy-rollback-integration.sh` (Container integration test: clean deploy, staleness override, auto-rollback, drift refusal (clause c))
- IMPLEMENTS → GITHUB_ISSUE `855` (Replace SSH-forced-command + manual file sync with a proper CI/CD-driven deploy)
- DOCUMENTS → ADR `architecture/adrs/063-release-deployment-model.md` (ADR-063 Release & Deployment Model — cut-a-release observability and rollback through the canonical deploy path (GC-P023 reliability domain))
- IMPLEMENTS → GITHUB_ISSUE `1222` (Deploy immutable versioned images (vX.Y.Z) instead of the floating :main tag)
- IMPLEMENTS → PULL_REQUEST `1235` (changed: pin production deploy to an immutable versioned image tag (ADR-063))
- IMPLEMENTS → CODE_FILE `scripts/rollback.sh` (One-command operator rollback wrapper: validates an immutable X.Y.Z/digest pin (same-repo), patches GC_IMAGE host-local, drives the canonical deploy path (clauses b, c))
- TESTS → TEST `tools/tests/test_rollback_ref_resolution.py` (Docker-free rollback ref-resolution tests: immutable-pin/digest validation, same-repo provenance, injection rejection, Makefile env-quoting (clause b))
- IMPLEMENTS → CONFIG `deploy/docker/.env.example` (Canonical production env template — the operator-facing /opt/gc/.env contract; every key it advertises must have an executable consumer (clauses a, e))
- IMPLEMENTS → GITHUB_ISSUE `1384` (Remove retired orchestration configuration and console assumptions — adds the env-template orphan-key gate (deploy-env-template-orphan-key))
- IMPLEMENTS → PULL_REQUEST `1389` (fix: remove retired orchestration config and console assumptions — reverse template-to-consumer invariant on the env contract)
- TESTS → TEST `tools/tests/test_policy_deploy_artifact_consistency.py` (run_deploy_artifact_consistency policy-gate tests)

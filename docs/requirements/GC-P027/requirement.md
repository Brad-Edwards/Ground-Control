---
id: GC-P027
title: "Release Please Versioning and Release/Changelog Ownership"
status: ACTIVE
type: NON_FUNCTIONAL
priority: SHOULD
created_at: 2026-07-15T05:49:00.174210Z
updated_at: 2026-07-15T17:42:49.753571Z
---

# GC-P027 — Release Please Versioning and Release/Changelog Ownership

## Statement

Ground Control's product release, version, and changelog lifecycle shall be owned by a Release Please manifest workflow, replacing the operator-assembled procedure (ADR-063 amendment, issue #1399), enforced by repo-native policy gates (make policy / bin/policy) and pinned GitHub Actions:

(a) Single version ownership. A .release-please-manifest.json shall record the last released product version (baseline 1.0.1 per the ADR-063 lineage decision), and release-please-config.json shall mechanically update every product-version mirror (backend/build.gradle.kts, frontend/package.json, and the root package entries in frontend/package-lock.json) through one root component. MCP-server, citation-package, dependency, Flyway-migration, schema, pack, and domain-record versions remain independent and shall not be swept into the product bump. A repo-native policy gate shall fail when a product-version mirror diverges from the manifest, driven by a declarative version-surface inventory rather than a second hard-coded list.

(b) Conventional Commits and changelog ownership. PR-title conventional-commit enforcement shall run in CI, and the repository's feature-to-dev and dev-to-main merge topology shall preserve the commit signals Release Please parses (a green title check alone is insufficient). Release Please shall be the sole writer of CHANGELOG.md; feature PRs shall not edit it directly. The Towncrier fragment convention (fragments, towncrier config, templates, the changelog policy check, the Stop-hook vocabulary, the PR-template checkbox, the gc_render_pr_body fragment field, and the workflow/ADR prose) shall be retired as one coherent ownership change with no surviving second changelog authority.

(c) Artifact publication and idempotency. On release_created, the release workflow shall build and push ghcr.io/autarchy-ai/ground-control:X.Y.Z, record the manifest digest and source commit in the GitHub Release, preserve the existing build, test, integration, Sonar, contract, Docker, and smoke gates, and treat (version, tag, source SHA, digest) as one write-once idempotency binding (reruns may repair metadata but shall never repoint an existing SemVer tag or image coordinate to different content). Publication shall stay in the Release Please run; a tag or release created with GITHUB_TOKEN shall not be relied upon to trigger a second workflow. Release creation shall not deploy production; promotion and rollback remain operator-driven through make deploy and the ADR-030/ADR-063/GC-P023 validated, health-gated, auto-rollback path, and :main/:latest/build-coordinate tags shall never be the production pin.

(d) main-to-dev back-merge. A content-aware workflow (push to protected main plus workflow_dispatch) shall compare origin/main and origin/dev committed trees, exit cleanly when they already match, and otherwise update one dedicated automation branch and one human-merged PR targeting dev, reusing the PR by head plus base branch rather than title, never force-pushing main or dev, and serialized by workflow concurrency.

(e) Policy, documentation, and rollout. There shall be one current release contract expressed consistently across ADR-063, ADR-021, and the workflow documentation (no parallel manual and automated procedures), and the change shall pass make policy and the repository policy validators (immutable action pins, PR-title policy, version-mirror consistency, and workflow-guardrail synchronization).

## Rationale

Issue #1399. Ground Control's accepted release model (ADR-063 original) was operator-assembled: a maintainer chose a version, collated Towncrier fragments, bumped version literals, pushed an annotated tag, and relied on the tag-triggered CI path — the procedure-drift risk ADR-063 explicitly called out, with three disagreeing version literals (0.20.1 / 0.76.0 / 0.116.3) and git tags at v1.0.1. Adopting the Release Please shape already operating in the ACES repositories makes releases mechanical and consistent while preserving ADR-063's immutable artifact identity, write-once tags, operator-driven promotion, and health-gated/auto-rollback deploy path. Anchors the issue #1399 release-automation structural gates (version-mirror consistency policy check, PR-title CI gate, release-please and sync-main-to-dev workflows) for traceability per the /implement structural-gate planning rule; the /implement workflow-tooling touches (gc_render_pr_body Release Please mode, skill prose, changelog-fragment retirement) additionally link to GC-O007.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/063-release-deployment-model.md` (ADR-063: Release &amp; Deployment Model (2026-07-15 Release Please Ownership amendment))
- IMPLEMENTS → CONFIG `release-please-config.json` (Release Please config (one root component; version-mirror extra-files))
- IMPLEMENTS → CONFIG `.release-please-manifest.json` (Release Please manifest (baseline 1.0.1))
- IMPLEMENTS → CONFIG `.github/workflows/release-please.yml` (Release Please workflow (release PR + gated GHCR publish, digest+source recorded))
- IMPLEMENTS → CONFIG `.github/workflows/sync-main-to-dev.yml` (main-to-dev back-merge workflow (content-aware, human-merged PR))
- IMPLEMENTS → CONFIG `.github/workflows/pr-title.yml` (Conventional Commit PR-title CI gate (pinned amannn action))
- IMPLEMENTS → CODE_FILE `tools/policy/checks.py` (run_version_mirror_consistency_check (version-mirror-drift gate))
- IMPLEMENTS → GITHUB_ISSUE `1399` (#1399 Adopt Release Please versioning and automated main-to-dev back-merges)
- TESTS → TEST `tools/tests/test_version_mirror_consistency_checks.py` (VersionMirrorConsistencyChecksTest (+ broadened _is_release_pr))

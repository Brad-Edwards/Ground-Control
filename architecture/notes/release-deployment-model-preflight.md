# Release and Deployment Model Preflight

Issue #1221 asks for the release model ADR that ADR-030 deliberately does not
cover: versioning, release artifacts, promotion, cut-a-release, and rollback.
This note records the architecture guardrails for that ADR and its companion
mechanism issues. It does not implement the release model itself.

## Architecture Boundaries

- Treat release and deploy as different lifecycle events. A merge to `main`
  may publish branch images, but production promotion must be tied to an
  explicit release version and deploy record, not the mutable `:main` tag.
- Keep the release model in ADRs, workflow docs, CI, deploy scripts, and
  operator tooling. Do not introduce backend controllers, DTOs, services,
  repositories, database tables, or exception classes just to represent
  releases.
- Extend ADR-030 with a new release decision record or a small amendment that
  points to one. The change reverses ADR-030's current floating-tag deploy
  assumption, so make the contract transition explicit instead of editing
  scattered docs as if this were wording cleanup.
- `dev` is the integration branch, not a deployed staging environment unless a
  real target exists. `main` is the protected release baseline. Production runs
  a promoted versioned image, not whichever branch image was last pushed.
- A release artifact must be a versioned GHCR image plus its resolved digest
  and source commit. The tag is the human release coordinate; the digest is the
  audit/rollback identity captured in deploy state.

## Cross-Cutting Concerns to Reuse

- **Changelog/versioning:** reuse `changelog.d/`, `towncrier.toml`,
  `CHANGELOG.md`, and the existing changelog policy checks. Bump rules belong
  beside that convention; do not create a second release-notes schema.
- **CI image publishing:** build on the existing `.github/workflows/ci.yml`
  `docker` job, pinned Docker actions, OCI revision labels, and GHCR namespace
  guard. Verify the actual tag emitted by `docker/metadata-action`; the current
  `type=semver,pattern={{version}}` path may not emit a literal `v` prefix.
- **Deploy artifact contract:** reuse `deploy/docker/deploy.sh`,
  `deploy/docker/docker-compose.prod.yml`, `deploy/docker/env.schema`,
  `deploy/docker/validate-env.sh`, `deploy/docker/MANIFEST.sha256`,
  `scripts/deploy.sh`, `make deploy`, `make deploy-status`, and the
  `run_deploy_artifact_consistency` policy gate. Any change from branch tags to
  release tags must update the schema, validator, docs, tests, and manifest
  together.
- **Deployment ledger:** reuse `/opt/gc/deploy-state.json` and GitHub
  Deployments for "what is running?" Do not add a Ground Control domain entity
  for release status unless a later product requirement asks for a UI/API.
- **Security controls:** preserve ADR-030's tailnet-only host, forced-command
  `gc-deploy` SSH model, `/opt/gc/.env` mode-600 secret boundary, ADR-026
  credential inherit-only compose form, and env validation that reports names
  only.

## Security and Runtime Guardrails

- Do not pass release versions, tokens, registry credentials, database
  passwords, or session material through SSH argv, shell history, workflow
  summaries, GitHub Release text, deploy status descriptions, or logs.
- The forced-command deploy path ignores SSH argv by design. If promotion needs
  to choose a version, the ADR must define a safe parameter surface such as a
  host-local env update, checked-in deploy target config, or CI-controlled
  environment input; do not smuggle parameters through the forced command.
- Keep GitHub write permissions narrow. Release creation or deployment-status
  posting should use the existing GitHub Actions `GITHUB_TOKEN` or established
  operator/MCP surfaces, with job-scoped permissions instead of broad workflow
  permissions.
- Preserve deploy-time validation before restart. The new model must still pass
  `env.schema`, `validate-env.sh`, compose variable checks, Spring security
  binding validation, and health/rollback behavior before declaring success.
- Release rollback is redeploying a previous known-good version through the
  same deploy path. Do not add an ad-hoc `docker compose` rollback script that
  bypasses validation, health checks, or deploy-state publication.

## Extensibility

The extension seam is deploy-target and release-candidate configuration:
environment name, host, SSH identity, remote directory, compose project, image
package, release version/tag, and status publisher. A future staging host or
additional package should be one configuration row, not a fork of the deploy
script or backend code.

## Gotchas and Anti-Patterns

- Do not say "immutable `vX.Y.Z` image" unless CI actually publishes that exact
  tag and the release process prevents retagging. Record both tag and digest.
- Do not leave `GC_IMAGE=...:main` in production documentation after the ADR
  says `:main` is not a release. That would keep deploy and release conflated.
- Do not treat a digest pin as the normal release model unless the policy,
  validator, and operator docs are changed intentionally. Today's guard rejects
  long-lived digest pins because they previously caused stale deploys.
- Do not duplicate version bump rules in CI, docs, and scripts. One ADR section
  plus the towncrier/changelog convention should be the source; checks should
  enforce structure, not carry competing prose.
- Do not broaden backend error handling, audit, persistence, or API schemas for
  a workflow-only release model.

## Non-Goals

- No multi-host or multi-region deployment design.
- No public ingress, TLS termination, auth redesign, or browser-session change.
- No Kubernetes, Watchtower, Argo/Flux, or managed platform migration.
- No new release dashboard, database-backed release entity, or Ground Control
  requirement baseline automation in this issue.
- No replacement for the existing backup/restore, Flyway migration, or policy
  gates.

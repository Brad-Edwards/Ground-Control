# Release and Deployment Model Preflight

Issue #1221 asked for the release model ADR that ADR-030 deliberately does not
cover: versioning, release artifacts, promotion, cut-a-release, and rollback.
Issue #1222 is the companion mechanism change that moves production off the
floating `:main` deploy pin. Issue #1224 adds the publication surface: a GitHub
Release for each `vX.Y.Z` tag, with notes derived from the collated changelog and
the exact GHCR artifact named in the release. This note records the architecture
guardrails for those surfaces. It does not implement the release model, the
deployment mechanism, or the release-publication workflow itself.

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
- For issue #1222, the steady-state production image reference must be either a
  release semver image tag (`:X.Y.Z`; ADR-063 notes that CI strips the leading
  `v` from git tag `vX.Y.Z`) or a digest (`@sha256:...`). Branch tags
  (`:main`, `:dev`, `:latest`) and build-coordinate tags (`:sha-<short>`) must
  not pass as the normal production pin. If a temporary non-release candidate is
  needed later, it needs an explicit loud override path rather than weakening
  the steady-state validator.

## Cross-Cutting Concerns to Reuse

- **Changelog/versioning:** reuse `changelog.d/`, `towncrier.toml`,
  `CHANGELOG.md`, and the existing changelog policy checks. Bump rules belong
  beside that convention; do not create a second release-notes schema.
- **CI image publishing:** build on the existing `.github/workflows/ci.yml`
  `docker` job, pinned Docker actions, OCI revision labels, and GHCR namespace
  guard. Verify the actual tag emitted by `docker/metadata-action`; the current
  `type=semver,pattern={{version}}` path may not emit a literal `v` prefix.
- **GitHub Release publication:** build on the same tag-triggered CI workflow
  and the same `vX.Y.Z` -> image tag `X.Y.Z` mapping from ADR-063. The release
  body must be extracted from the already-collated `CHANGELOG.md` section for
  `X.Y.Z`; do not hand-copy notes, re-run towncrier in the tag workflow, or
  invent a parallel release-notes schema. The job must fail loudly if the
  changelog section is missing or ambiguous.
- **Deploy artifact contract:** reuse `deploy/docker/deploy.sh`,
  `deploy/docker/docker-compose.prod.yml`, `deploy/docker/env.schema`,
  `deploy/docker/validate-env.sh`, `deploy/docker/MANIFEST.sha256`,
  `scripts/deploy.sh`, `make deploy`, `make deploy-status`, and the
  `run_deploy_artifact_consistency` policy gate. Any change from branch tags to
  release tags must update the schema, validator, docs, tests, and manifest
  together.
- **Env-image validation:** change the existing `env.schema` directive and
  `validate-env.sh` parser deliberately; do not layer a second ad hoc
  `GC_IMAGE` checker into the wrapper or compose file. The current
  `FLOATING_TAG` invariant in `tools/policy/checks.py` and
  `tools/tests/test_validate_env.py` is intentionally the thing #1222 must
  replace or rename, with tests proving `:main` fails and `:X.Y.Z` /
  `@sha256:...` pass under the chosen steady-state rule.
- **Deployment ledger:** reuse `/opt/gc/deploy-state.json` and GitHub
  Deployments for "what is running?" Do not add a Ground Control domain entity
  for release status unless a later product requirement asks for a UI/API.
- **Version discoverability:** reuse the existing actuator/build-info surface
  (`springBoot { buildInfo() }`, `/actuator/info` is already anonymous in
  `ApiPathMatrix`) and the pinned image/deploy-state record. Do not add a
  release controller, release repository, or duplicate version table just to
  answer this issue's discoverability acceptance criterion.
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
- The release-publishing job needs `contents: write` only for the GitHub
  Release write. Keep that permission scoped to the release job instead of
  widening the whole workflow, and do not introduce a long-lived PAT.
- Preserve deploy-time validation before restart. The new model must still pass
  `env.schema`, `validate-env.sh`, compose variable checks, Spring security
  binding validation, and health/rollback behavior before declaring success.
- Keep the `.env` parser non-executing. `validate-env.sh` must keep parsing
  lines as data rather than `source`ing the file, and failure output must keep
  reporting variable names only. A bad image ref is configuration data, not a
  reason to expose database passwords, bearer tokens, or registry credentials.
- Release rollback is redeploying a previous known-good version through the
  same deploy path. Do not add an ad-hoc `docker compose` rollback script that
  bypasses validation, health checks, or deploy-state publication.
- If the implementation updates `/opt/gc/.env` from tooling, the safe parameter
  surface is a host-local file update with mode `0600` and no secret echoing.
  Do not pass the target version through SSH forced-command argv, `curl`
  headers, process argv, workflow summaries, or GitHub Release text.

## Extensibility

The extension seam is deploy-target and release-candidate configuration:
environment name, host, SSH identity, remote directory, compose project, image
package, release version/tag, and status publisher. A future staging host or
additional package should be one configuration row, not a fork of the deploy
script or backend code.

For GitHub Releases, the seam is the release artifact descriptor: repository
package name, git tag, semver image tag, digest, source commit, changelog section,
and publisher. A future second image/package or SBOM/provenance attachment should
add another artifact descriptor to the release record, not a second changelog
parser or a second tag workflow.

## Gotchas and Anti-Patterns

- Do not say "immutable `vX.Y.Z` image" unless CI actually publishes that exact
  tag and the release process prevents retagging. Record both tag and digest.
- Do not leave `GC_IMAGE=...:main` in production documentation after the ADR
  says `:main` is not a release. That would keep deploy and release conflated.
- Do not treat a digest pin as the normal release model unless the policy,
  validator, and operator docs are changed intentionally. Today's guard rejects
  long-lived digest pins because they previously caused stale deploys.
- Do not preserve `GC_ALLOW_IMAGE_PIN=1` as a permanent "digest is exceptional"
  flag if digest becomes the production steady state. Either retire that
  meaning or narrow it to non-release/test overrides so operators are not taught
  that the desired immutable pin is an incident mode.
- Do not leave `scripts/deploy.sh` publishing an empty or mismatched digest
  field. `deploy/docker/deploy.sh` emits `active_digest` in
  `DEPLOY_STATE_JSON`; the wrapper and `make deploy-status` must use the same
  field names if the release pin/deploy-state surface is touched.
- Do not duplicate version bump rules in CI, docs, and scripts. One ADR section
  plus the towncrier/changelog convention should be the source; checks should
  enforce structure, not carry competing prose.
- Do not let the release job recompute an image tag independently from the
  Docker metadata output when that output is available. Tag pushes already cross
  the `docker` job; the release surface should name the artifact that job
  produced, including the resolved digest when available.
- Do not create duplicate releases on rerun. A rerun for the same tag should be
  deterministic: update the existing GitHub Release body/assets if needed or
  leave it unchanged when identical.
- Do not broaden backend error handling, audit, persistence, or API schemas for
  a workflow-only release model.

## Non-Goals

- No multi-host or multi-region deployment design.
- No public ingress, TLS termination, auth redesign, or browser-session change.
- No Kubernetes, Watchtower, Argo/Flux, or managed platform migration.
- No new release dashboard, database-backed release entity, or Ground Control
  requirement baseline automation in this issue.
- No release-note authoring surface outside `changelog.d/`, `towncrier.toml`,
  and `CHANGELOG.md`.
- No replacement for the existing backup/restore, Flyway migration, or policy
  gates.

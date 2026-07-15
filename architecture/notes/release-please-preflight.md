# Release Please and Main-to-Dev Sync Preflight

Issue #1399 moves Ground Control from the operator-assembled procedure in the
original ADR-063 decision to Release Please. This note fixes the repo-wide
boundaries before implementation. It does not add workflows, version files,
policy code, or release automation.

## Authority and Concept Boundaries

- Release Please is the only release coordinator and `CHANGELOG.md` writer.
  Conventional Commit history is release input; the manifest is released-state
  input; version mirrors are generated output. None of those concepts should
  be renamed "the version source of truth" without stating which lifecycle
  phase it owns.
- Configure one root Ground Control component. Do not model the backend and
  frontend as independently releasable packages merely because they use
  different package managers. They ship in one image and receive one tag,
  GitHub Release, digest, and source commit.
- Product-version mirrors are `backend/build.gradle.kts`,
  `frontend/package.json`, and both root-package occurrences in
  `frontend/package-lock.json`. The lockfile is not an independent version
  authority, but it must remain consistent with `package.json`.
- `mcp/ground-control/package.json` and the `McpServer` version identify the MCP
  protocol/server package, not the shipped product. `mcp/citation/pyproject.toml`
  identifies the citation package. Dependency, Flyway migration, schema,
  methodology-profile, pack, test-plan, and test-run versions are also
  independent domain/protocol coordinates. Do not sweep them into a product
  bump by matching the word `version`.
- The release manifest records the last released product version. The current
  build consumes a mechanically synchronized mirror. The tag and GHCR digest
  identify the released source and artifact. Keep these roles distinct rather
  than adding another version file or runtime endpoint.
- Keep release state out of the backend `api/ -> domain/ <- infrastructure/`
  boundary. There is no release aggregate, controller, DTO, repository,
  persistence schema, exception hierarchy, or audit entity in this issue.

## Baseline Decision

The repository's two existing annotated tags, `v1.0.0` and `v1.0.1`, point to
the former Python/Jira application; the current backend and frontend do not
exist at either tag. The newest old-format changelog section (`0.116.3`), the
backend literal (`0.20.1`), and frontend literal (`0.76.0`) are later,
independent counters. GitHub Releases are incomplete relative to tags because
only `v1.0.0` has a release.

ADR-063 already resolved this conflict in favor of the tag lineage and
explicitly abandoned the `0.x` lineages. Therefore seed the root manifest at
`1.0.1`. This prevents reuse of a published tag and preserves the accepted
lineage, while the bootstrap record must disclose that `1.0.1` did not version
the current Java/React application. Do not present the baseline as evidence
that today's application was previously released at `1.0.1`.

The first Release Please dry run must bound the commit search and show the
proposed bump/changelog before merge. Historical non-conventional commits and
the application rewrite make an unreviewed "all history since v1.0.1" import
unsafe. A bootstrap SHA/search-depth seam may be used when needed, but it must
select history, not fabricate a second version lineage or bypass the manifest.

## Existing Contracts to Reuse

- **Release artifact:** preserve ADR-063's
  `vX.Y.Z <-> X.Y.Z <-> @sha256:... <-> source SHA` mapping and the existing
  OCI revision label from `.github/workflows/ci.yml`.
- **Quality gates:** preserve the existing policy, build, unit, integration,
  Sonar, static verification, MCP/OpenAPI contract, Docker, and smoke jobs.
  Release Please must consume the protected release PR's successful checks;
  publication-specific work must not duplicate a weaker copy of the test
  graph. If jobs are extracted, use one reusable workflow as the canonical
  graph. The current branch-protection baseline is not sufficient evidence:
  `docker` and `smoke` do not run on PR events, and `mcp-contract`, `docker`,
  and `smoke` are not listed as required contexts. The automated release path
  must close that gap rather than assuming "the release PR was green" proves
  the published image passed every artifact gate.
- **Runtime lookup and observability:** reuse Gradle `springBoot.buildInfo()`,
  anonymous `/actuator/info`, and the intended `service.version` field in
  structured production logs. The backend mirror must be updated before the
  image build. Verify the log binding explicitly: `logback-spring.xml` currently
  reads `info.app.version`, while Spring Boot build info normally contributes
  build metadata rather than that configuration property, so production logs
  may still emit the configured `unknown` fallback. Fix that consumer against
  the existing build-info source if confirmed; do not add another version
  literal to make the log field pass.
- **Deployment:** preserve `deploy/docker/env.schema`'s `RELEASE_PIN`,
  `deploy/docker/validate-env.sh`, the canonical `deploy/docker/deploy.sh`,
  `deploy/docker/MANIFEST.sha256`, `scripts/deploy.sh`, `make deploy`, rollback,
  health gating, revision-advance checks, `/opt/gc/deploy-state.json`, and
  GitHub Deployments. Release automation must not write `/opt/gc/.env` or call
  the production deploy path.
- **PR-title shape:** reuse the Conventional Commit vocabulary and lowercase
  subject rule already documented in `skills/implement/steps/step-09-pr-body.md`
  and `docs/DEVELOPMENT_WORKFLOW.md`. Move authoritative enforcement into CI;
  do not maintain a different action allow-list and local allow-list.
- **PR rendering:** extend the canonical `gc_render_pr_body` input validator,
  renderer, tool schema, and compose tests for a Release Please changelog mode
  per issue #1336. Do not special-case Ground Control only in skill prose.
- **Policy:** replace `run_changelog_fragment_check` and its tests as one
  ownership change. Retire the Towncrier config, fragment templates/readme,
  Stop-hook vocabulary, PR-template checkbox, `.gitattributes` union rule, and
  workflow/ADR prose in the same change so no second authority survives.
- **GitHub writes:** Actions own release and sync-PR writes. Agent workflows
  continue to use the established MCP/GitHub boundaries; no release feature is
  a reason for application code or an agent sandbox to gain registry/repository
  write credentials.

## Merge-Signal Contract

Release Please parses commits on `main`, not PR checks. Enforcement must prove
both halves of the contract:

1. feature PR titles accept and classify `feat`, `fix`, breaking changes, and
   explicitly non-release types; and
2. the configured merge method leaves an equivalent Conventional Commit on
   `dev`, then the `dev` to `main` promotion leaves those signals discoverable.

Squash merges can use the validated PR title as the commit subject. Merge
commits require Conventional Commit subjects in the commits being traversed,
or a promotion strategy Release Please is proven to parse. A green title check
alone is not evidence. Test the repository's actual feature and promotion
topology with representative commits, including `feat!:` or a
`BREAKING CHANGE:` footer, before rollout.

Keep the release PR title pattern compatible with the same CI title gate. The
automation PR (`chore(main): release X.Y.Z`) and the `sync/main-to-dev` PR need
documented, narrow exemptions only where their machine-authored shape cannot
carry normal issue metadata; do not exempt every PR targeting `main`.
`tools/policy/checks.py::_is_release_pr` currently exempts only `dev -> main`;
a Release Please branch targeting `main` and a sync branch targeting `dev`
therefore hit the ordinary PR-body contract. Any exemption must bind the exact
base/head pattern and trusted automation identity (or render a compliant body);
title text alone is spoofable and must not grant a policy bypass.

## Artifact Publication and Idempotency

- Run the pinned Release Please action on pushes to protected `main`. Use the
  root `release_created` output to gate all versioned artifact work in that
  same run, or call an explicit reusable workflow. GitHub suppresses ordinary
  workflow events caused by `GITHUB_TOKEN`, so a separate tag/release-triggered
  workflow is not a reliable continuation.
- Keep action versions pinned to immutable commit SHAs, including Release
  Please and any PR-creation action. Dependabot-style pin updates are reviewable
  maintenance; mutable major tags are not supply chain pins.
- The version, tag, and source SHA used by Docker come from Release Please
  outputs. The manifest digest comes from the one successful push. Release
  metadata must consume those outputs rather than re-parse a tag or recompute
  an image name independently.
- Treat `(version, tag, source SHA, digest)` as one idempotency binding. Before
  publishing an already-existing SemVer image tag, verify its recorded source
  revision. If it exists for the same release, reuse its digest and repair
  missing release metadata; if it points elsewhere, fail closed. Rebuilding and
  pushing a same-named tag can change the manifest digest even from the same
  source, so "same SHA" alone is not permission to repoint a write-once image
  coordinate.
- Keep `:main`, `:latest`, and `sha-*` production-neutral. Existing branch
  image publication may continue, but it must not feed release metadata or
  deployment selection.
- Ensure the protected release PR has passed the canonical gates before human
  merge. On `release_created`, build/push the immutable coordinate, smoke the
  published image or exact digest, and only then mark artifact metadata
  complete. Do not treat the concurrently running `main` branch build as proof
  for a different versioned manifest.

## Main-to-Dev Sync Boundary

- Trigger only from a protected `main` push and `workflow_dispatch`. Compare
  the committed trees of `origin/main` and `origin/dev`, not commit counts:
  after a normal `dev` to `main` promotion the histories can differ while the
  content is identical.
- Use one constant automation branch and one PR targeting `dev`. Search/update
  by head branch plus base branch, not title text. A rerun updates that PR; it
  must not create a duplicate.
- The automation branch is disposable and may be force-updated with lease from
  current `main`. Never force-push `main` or `dev`, never resolve conflicts with
  an `ours` strategy, and never bypass the human merge gate. A conflict is a
  visible failed run/PR requiring normal resolution.
- Bot-created PR checks may require maintainer approval because GitHub applies
  special recursion rules to `GITHUB_TOKEN`-created PRs. Preserve the ACES
  one-click/admin merge behavior if that is the repository's token model; do
  not enable auto-merge that waits forever or add a broad PAT merely to make
  recursive checks fire.
- Serialize by workflow/branch concurrency so simultaneous `main` pushes do
  not race the automation branch or open duplicate PRs.

## Security and Cross-Cutting Layers

- **Workflow event trust:** use `push` on protected `main`, not
  `pull_request_target` with untrusted checkout. The executed workflow and
  scripts must come from the protected commit. Manual dispatch must apply the
  same ref guard.
- **Permissions:** default to `contents: read`; grant Release Please only the
  `contents: write` and `pull-requests: write` it needs, artifact publication
  only `packages: write` plus required reads, and sync only `contents: write`
  and `pull-requests: write`. Do not preserve the current workflow-wide
  `packages`, PR-write, or OIDC permissions in a new workflow without a job
  that needs them.
- **Secrets:** use the ephemeral `GITHUB_TOKEN` unless a documented GitHub App
  token is necessary for branch-protection behavior. Never place tokens in
  checkout URLs, process argv, generated release notes, artifacts, summaries,
  or logs. GitHub/registry credentials stay action inputs or environment
  variables and must be masked.
- **Input shaping:** treat PR titles, changelog entries, branch names, action
  outputs, and API JSON as data. Pass dynamic values through action inputs,
  environment variables, or JSON files; never interpolate them directly into
  a `run:` shell program. Validate SemVer, tag, repository/image namespace,
  non-empty digest, and full source SHA before publishing.
- **Repository policy validators:** changes must pass YAML/JSON parsing,
  pre-commit secret scanning, action-pin checks, PR-title policy, version-mirror
  consistency, workflow guardrail sync, and `make policy`. Release PRs and sync
  PRs need explicit tested classifications rather than broad policy bypasses.
- **Runtime/config validators:** the produced image still passes Docker/compose
  variable expansion, `env.schema`, non-executing `validate-env.sh`, Spring
  configuration binding/validation, application startup, actuator health, and
  deploy revision-advance/rollback checks. Product version is build metadata,
  not a new environment variable, so no duplicate `@ConfigurationProperties`
  surface is warranted.
- **OS exposure:** no product version or secret is passed through forced SSH
  argv. `/opt/gc/.env` remains host-local mode 0600; release automation does
  not read or modify it. Public release notes contain only public changelog
  text, image coordinate, digest, and source commit.
- **Error and logging surface:** workflow failures use GitHub annotations and
  non-zero exits without dumping response bodies or environment state. Backend
  `ErrorResponse`, `GlobalExceptionHandler`, `ActorHolder`, and application
  exception hierarchies are out of path and must remain unchanged. Structured
  application logs inherit the synchronized build-info version; do not add a
  release-specific logger or audit stream.

## Extensibility Seams

- The Release Please manifest's root package entry is the seam for release
  type, component name, changelog sections, version-file updaters, and bounded
  bootstrap history. Adding a legitimate product-version mirror should be one
  `extra-files` entry plus a mirror-policy fixture, not another bump script.
- A declarative version-surface inventory is the seam for classifying paths as
  `product mirror`, `derived mirror`, or `independent protocol/package`. The
  policy check should consume that inventory or the Release Please config
  rather than carry a second hard-coded list.
- The artifact descriptor remains image repository, version/tag, digest,
  source SHA, and release URL/body. A future SBOM, provenance attestation, or
  second shipped image extends that descriptor and publication job; it does
  not create a second changelog or tag workflow.
- Sync branch, source branch, target branch, PR title, and labels belong in
  one workflow-level parameter block. The default remains `main -> dev`; a
  future maintenance branch should not require copying the workflow logic.

## Gotchas and Anti-Patterns

- Do not keep Towncrier "temporarily" while Release Please writes the same
  changelog. Dual ownership is the procedure drift this issue removes.
- Do not use the manifest, Gradle literal, frontend JSON, git tag, and runtime
  build info interchangeably. They are synchronized surfaces with different
  lifecycle roles.
- Do not classify MCP protocol, citation package, dependency, migration, pack,
  or domain-record versions as product mirrors.
- Do not duplicate the CI gate graph, Conventional Commit vocabulary, SemVer
  parser, release-note schema, GHCR naming, or PR-body renderer.
- Do not use `paths-ignore` on the Release Please workflow in a way that misses
  its own release-only merge or back-merge content.
- Do not assume a Release Please-created tag triggers `.github/workflows/ci.yml`.
  Do not solve that recursion failure with an undeclared long-lived PAT.
- Do not key sync-PR reuse by title, use `git diff` alone without fetching both
  remote refs, or overwrite a human branch that happens to share a name.
- Do not push moving `X.Y` or `latest` tags as if they were immutable releases.
  ADR-063's required release coordinate is full `X.Y.Z` plus digest.
- Do not auto-merge the release PR or back-merge PR around branch protection.
- Do not add backend abstractions, database state, API error envelopes, or
  configuration properties for repository release automation.

## Non-Goals

- Automatic production deployment or changes to the operator promotion gate.
- PyPI, npm, Maven, or standalone MCP/citation-package publication.
- A release dashboard, backend release aggregate, or persisted release ledger.
- Multi-host deployment, a staging environment, or deployment-platform change.
- Auto-merge, branch-protection bypass, or a general-purpose branch-sync engine.
- Rewriting historical tags, changelog headings, or old package versions to
  pretend the divergent histories never existed.

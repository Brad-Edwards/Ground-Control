# Ground-Control plan rules

Mandatory constraints the `/implement` skill applies during plan phase.

- Plans that add database migrations MUST update the hardcoded version
  lists in `MigrationSmokeTest.java` and
  `RequirementsE2EIntegrationTest.java`.
- Plans that add `@Audited` JPA entities MUST add `@NotAudited` on any
  `@ManyToOne` references to non-audited entities (for example, Project), and
  MUST include a Flyway migration for the `_audit` table.
- Plans that add API endpoints MUST include `@WebMvcTest` controller
  unit tests (not just integration tests). The sonar CI job does not run
  Testcontainers, so only unit tests contribute to SonarCloud coverage.
- Plans for a `security_relevant` change (per the Step 3.5 screening
  record) MUST enumerate the change's GRC deliverables as structured
  `grc_deliverables` on `gc_post_implementation_plan` (GC-GRC-010):
  threats to model/update, risks to assess, controls to select and
  implement, and stale entities to refresh. Coverage is kind-aware: a
  gap surface must be covered by a `threat` / `risk` / `control`
  deliverable and a stale entity by a `stale_refresh` deliverable.
  In-scope GRC work MUST NOT be deferred to a follow-up issue. A
  disposition (`accept` / `wontfix` / `not_applicable` with
  `authorized_by` + `rationale`) is the only relief valve and is honored
  only when the run is authorized via `override=true` + `override_reason`
  quoting the user's authorization (GC-GRC-015 adds graph-verified
  dispositions). Controls are selected at plan time so they ship with the
  change (secure by design), not retrofitted.
- Plans whose diff touches application source (`backend/src/main/**`,
  `backend/src/test/**`, `frontend/src/**`, `mcp/**`, or `tools/**`
  outside `tools/policy/` and `tools/tests/`) MUST drop a changelog
  fragment under `changelog.d/<issue>.<type>.md` (or
  `changelog.d/+<slug>.<type>.md` for issue-free entries), where
  `<type>` is one of `security`, `added`, `changed`, `deprecated`,
  `removed`, `fixed`. CI-only diffs (paths under `.github/workflows/`
  only) and docs-only diffs (paths under `docs/**`, `architecture/**`,
  `README.md`, `CONTRIBUTING.md`, `.gc/**`, `skills/**`, and equivalent
  metadata) may ship without a fragment. There is no "pure refactor"
  carve-out - the policy is path-based, so a behavior-preserving
  refactor under an application-source path still files a fragment
  (a one-line `### Changed - Internal refactor of X` is fine). Do NOT
  edit `CHANGELOG.md` directly - release-time `towncrier build`
  collates fragments into the changelog. See `changelog.d/README.md`
  for the convention. Enforced by
  `tools/policy/checks.py::run_changelog_fragment_check` (codes
  `changelog-signal-missing` and `changelog-fragment-invalid-name`).

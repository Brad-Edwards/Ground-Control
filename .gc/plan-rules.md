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
- Release Please owns `CHANGELOG.md` and the product version (GC-P027, ADR-063
  2026-07-15 amendment). Feature PRs do NOT edit `CHANGELOG.md` or file changelog
  fragments - the Towncrier `changelog.d/` convention was retired in #1399. The
  changelog is generated from Conventional Commit history on `main`, so PR titles
  MUST follow Conventional Commits (`<type>(<optional-scope>): <lowercase
  subject>`), enforced in CI by `.github/workflows/pr-title.yml`.
- Plans MUST NOT hand-edit a product-version literal. `backend/build.gradle.kts`,
  `frontend/package.json`, and `frontend/package-lock.json` are mechanically
  updated by the release PR; their consistency with `.release-please-manifest.json`
  is enforced by
  `tools/policy/checks.py::run_version_mirror_consistency_check` (code
  `version-mirror-drift`). MCP-server, citation, and dependency versions are
  independent and are not product mirrors.

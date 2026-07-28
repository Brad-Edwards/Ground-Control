# CI Fast-Feedback Preflight

Issue: #1461
Requirement: none

This note records architecture guardrails for shortening PR feedback time
without weakening protected-branch verification. It is preflight guidance only.
It does not implement the workflow changes, shard layout, timing scripts, or CI
documentation updates.

## Boundary

The implementation belongs in the existing GitHub Actions workflow surface:
`.github/workflows/ci.yml`, the existing Gradle tasks under `backend/`, the
repo-native `Makefile` verification commands, and the workflow documents. Do
not introduce an application runtime feature, controller, DTO, service,
repository, database table, MCP tool, or Ground Control workflow primitive for
this issue.

The current bottleneck is orchestration, not missing verification tools. The
design should keep one authoritative verification model and change only when
work starts, what it depends on, and how deterministic outputs are merged.

## Incumbents To Reuse

- Existing required-check topology in `.github/workflows/ci.yml`: `policy`,
  `trivy`, `osv-scanner`, `build`, `test`, `integration`, `sonar`, `verify`,
  `mcp-contract`, `docker`, and `smoke`.
- Existing repo-native commands in `Makefile`: `make policy`, `make check`,
  `make verify`, `make test-cov`, `make frontend-test`, `make frontend-lint`,
  `make frontend-build`, and `make contracts-check`.
- Existing Gradle task ownership in [backend/build.gradle.kts](/home/atomik/src/gc2/backend/build.gradle.kts):
  `test`, `integrationTest`, `generateContractOpenApi`, `jacocoTestReport`,
  `jacocoTestCoverageVerification`, and `openjmlEsc`.
- Existing Sonar contract in [backend/build.gradle.kts](/home/atomik/src/gc2/backend/build.gradle.kts):
  Sonar reads `build/reports/jacoco/test/jacocoTestReport.xml`. If coverage is
  sharded, the implementation must still produce that canonical merged XML path
  before `sonar`.
- Existing workflow and policy docs: `docs/DEVELOPMENT_WORKFLOW.md`,
  `docs/WORKFLOW.md`, `docs/CODING_STANDARDS.md`, and
  `docs/architecture/ARCHITECTURE.md`.

## Cross-Cutting Layers

- **Security / secret surface:** keep PR-safe jobs on `ubuntu-latest` with
  least-privilege permissions. Do not move PR-executed jobs onto self-hosted
  runners and do not expose new secrets to shard, timing, or cache jobs.
- **Config shape:** prefer workflow-local env vars, matrix entries, and
  artifact names over a new repo config schema. This issue does not justify a
  new `.ground-control.yaml` or application `@ConfigurationProperties` surface.
- **Coverage contract:** JaCoCo ownership stays in Gradle. Merge shard outputs
  into the existing `jacocoTestReport` contract rather than teaching Sonar or
  policy a new coverage path.
- **Quality-gate contract:** Sonar remains a required merge gate. Faster
  feedback may start Sonar earlier, but must not make it advisory, partial, or
  based on a reduced source/coverage subset.
- **Static-analysis contract:** `verify` owns OpenJML ESC; `mcp-contract` owns
  OpenAPI/MCP drift; `policy` owns repo policy; `trivy` and `osv-scanner` own
  their security lanes. Do not collapse them into a generic "analysis" job that
  obscures ownership or widens failure blast radius.
- **Observability / measurement:** baseline and after timings should come from
  GitHub Actions job/run timestamps or generated workflow artifacts, not manual
  stopwatch measurement. Record median and p95 in versioned docs, not only in
  ephemeral PR discussion.
- **Error surface:** CI failures remain native job failures and artifact/report
  outputs. Do not add a second summary parser that can disagree with the
  authoritative job result.

## Extensibility

The extension seam is the workflow DAG plus deterministic shard inventory:

- Test sharding must be driven by a stable, reviewable partition rule
  (class-name list, package inventory, or deterministic Gradle selector), not
  ad hoc discovery at runtime.
- Coverage merging must tolerate adding or removing one shard without rewriting
  Sonar or downstream jobs.
- The early feedback lane must be conservative and parameterized by changed
  paths or surfaces, while the full suite remains the required merge gate.
- Timing capture should be scriptable from workflow metadata so the next
  before/after comparison does not require editing the CI logic again.

## Gotchas And Anti-Patterns

- Do not keep `test` as `./gradlew check` if the goal is independent feedback.
  `check` currently bundles unit tests, coverage verification, and any task
  dependencies behind one result, which delays first failure and downstream
  Sonar/verify scheduling.
- Do not create a fast lane that becomes a shadow policy. A reduced-scope lane
  may be advisory for earlier feedback, but `policy`, full backend tests,
  required static analysis, Sonar quality gate, contract drift, and security
  gates remain authoritative before merge.
- Do not let `integration` depend on successful completion of all unit-test
  work when only the build output is required to start it.
- Do not run Sonar on incomplete coverage inputs or on one shard's partial XML.
  If Sonar starts before all coverage-producing shards are done, its input is
  wrong even if the job is faster.
- Do not invent shard-local coverage thresholds or a second JaCoCo verifier
  unless the existing bundle-level threshold is deliberately reworked in
  Gradle.
- Do not broaden caches in ways that hide dependency drift or make results
  non-reproducible. Reuse pinned action caches and existing Gradle/Buildx/Sonar
  cache patterns.
- Do not satisfy the measurement requirement with one-off screenshots or issue
  comments. The timings need a durable, repeatable recording surface.
- Do not forget local reproduction. Any new shard or fast lane needs a repo
  command or documented exact Gradle/npm invocation so failures are reproducible
  outside Actions.

## Non-Goals

- No reduction of protected-branch verification coverage.
- No replacement of Gradle, JaCoCo, SonarCloud, OpenJML, Trivy, OSV-scanner,
  MCP contract tests, or repo policy with a new orchestrator.
- No frontend/product feature, backend API, persistence, or traceability-model
  change.
- No dynamic test scheduler, flaky-test quarantine system, or generic CI
  framework abstraction.
- No second source of truth for CI status outside GitHub Actions required job
  results.

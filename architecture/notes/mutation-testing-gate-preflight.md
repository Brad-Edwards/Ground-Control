# Mutation Testing Gate Preflight

Issue #1293 adds PIT and Stryker as the CLD mutation meta-oracle. This note is
architecture preflight guidance only. It does not implement the gate, choose
initial thresholds, or define an implementation plan.

## Architecture Boundary

Mutation testing is verification and CI infrastructure, not a production
aggregate. It should not add runtime controllers, database tables, workflow
state, issue-thread markers, dashboards, or service abstractions just to run a
tool.

ADR-087 owns the CLD vocabulary: boundary, lock level, oracle battery,
invariant inventory, design authority, implementer, and verifier. The mutation
gate measures the oracle battery's strength for a registered boundary; it does
not define a second battery taxonomy or a second boundary registry.

The existing repo already has advisory backend PIT wiring through
`make test-quality` and `backend/build.gradle.kts`. Tighten and scope that
incumbent path instead of creating a parallel Java mutation lane. The frontend
uses npm, Vitest, and `package-lock.json`; Stryker belongs in that package
surface if TypeScript boundaries are brought under contract.

Thresholds must live with architecture-registry boundary data. Do not hardcode
boundary ids, path selectors, package globs, mutation thresholds, or exemptions
in Gradle, npm scripts, Makefile targets, or CI YAML. Build files and CI should
receive resolved tool targets and threshold values from the registry-facing
runner.

## Incumbents To Reuse

- CLD method authority: ADR-087 and the contract-locked-development research
  packet under `docs/research/contract-locked-development/`.
- Oracle battery surface: `docs/research/contract-locked-development/oracle-battery-toolkit.md`,
  Java oracle scaffolds, `frontend/src/test/oracle-battery.ts`, and
  `mcp/ground-control/oracle-battery.js`.
- Boundary data: existing `grc.boundaries` validation in
  `mcp/ground-control/lib.js`, `gc_derivation`, `BoundaryModelService`,
  boundary-model readback at `/api/v1/derivations/runs/{id}/boundary-model`,
  and architecture-model `sourcePath` / `trustBoundaryKey` fields.
- CI conventions: `.github/workflows/ci.yml` pinned actions, Java and Node
  setup, artifact uploads, existing required status checks, and
  `.github/branch-protection-baseline.json`.
- Policy style: inventory-driven checks in `tools/policy/checks.py` with unit
  tests under `tools/tests/`, rather than one bespoke checker per boundary.
- Backend test conventions: unit tests, `@WebMvcTest` controller slices for
  coverage-sensitive controller work, ArchUnit boundary rules, jqwik property
  tests, and Testcontainers only where the boundary genuinely requires it.
- Frontend test conventions: Vitest, React Testing Library where UI behavior is
  involved, npm scripts, and the committed npm lockfile.

## Cross-Cutting Layers

- Security and auth: the mutation gate should have no HTTP surface. If a later
  registry API is needed, it must stay under `/api/v1/**`, use `ApiPathMatrix`,
  Spring Security tests, Bean Validation DTOs, service-layer validation,
  `GroundControlException` subclasses, `GlobalExceptionHandler`, and
  `ErrorResponse`.
- Config validation: registry and boundary declarations need a single strict
  schema with bounded lists, stable boundary ids, repo-contained path selectors,
  explicit exemption reasons, and unknown-key rejection. Do not add ad hoc YAML
  parsing to shell, Gradle, npm, or CI.
- Secret handling: never read local secret files, inject tokens into tool argv,
  upload environment dumps, or place credentials in mutation reports. Treat
  Stryker/PIT reports as source-adjacent artifacts that may contain file names
  and snippets; upload only bounded HTML/XML/JSON reports and sanitized
  summaries.
- OS/process exposure: invoke local tools with fixed argv, repo-contained path
  inputs, timeouts, output caps, and no shell interpolation of changed paths.
  Path lists must come from `git diff`/merge-base resolution and registry
  matching, not caller-supplied arbitrary command text.
- Error shape: CLI and policy failures should return structured names and
  boundary ids. Do not dump full tool output into CI annotations. Any backend
  validation added for registry data must use the existing error envelope.
- Logging and observability: record low-cardinality fields only: boundary id,
  tool, score, threshold, mutant counts, skipped reason, elapsed time, and
  report path. Avoid raw payloads, source snippets, headers, env, or secrets.
- Supply chain: adding Stryker changes the frontend dependency graph and
  `package-lock.json`; keep npm as the package manager. Gradle dependency
  changes remain under `backend/gradle.lockfile` and the existing OSV scanner
  surface.

## Gate Semantics

The CI check must always emit a deterministic context if it is a required PR
control. Interior-only diffs should produce a successful no-op result with an
explicit skipped reason; changed contract boundaries should run the appropriate
tool and fail on scores below the registry threshold.

Changed-boundary scoping should resolve repo-relative changed paths to boundary
ids through the architecture registry or the existing boundary/architecture
model surfaces. Do not infer contract boundaries from filenames alone, and do
not let each tool own a separate path-to-boundary map.

A boundary under mutation contract is either exempt with a registry reason
such as fluid interior or it has a threshold and baseline. Missing threshold,
missing baseline, a tool timeout, or a failed report parse for a changed
contract boundary should not silently pass as score `0` or a no-mutations
result.

Baseline scores should be committed as structured, machine-readable registry
data keyed by boundary id, tool, version, scope, score, mutant counts, and the
commit or derivation snapshot used to measure them. A prose table or a CI
artifact alone is not enough to enforce future thresholds.

## Extensibility

The extension seam is a registry boundary entry plus a tool adapter. A boundary
entry should be able to name its lock level, source selectors or model element
keys, language/package, oracle battery id, mutation tool, tool target patterns,
minimum mutation score, allowed exclusions, time budget, and baseline record.

Adding another package or language should add a native tool adapter and
registry rows, not another CI job with hardcoded boundary logic. PIT and Stryker
outputs should normalize to the same small result shape: boundary id, tool,
score, threshold, mutant counts, elapsed time, report path, and verdict.

## Gotchas And Anti-Patterns

- Do not leave the existing PIT threshold at `0` as the default for contracted
  boundaries. Zero is advisory calibration, not a CLD threshold.
- Do not conflate line coverage, SonarCloud quality gates, oracle battery
  checks, and mutation score. They are complementary controls with different
  failure meanings.
- Do not run full-repo mutation on every PR. The PR path must scope to changed
  contract boundaries and stay inside the agreed time budget.
- Do not make Stryker or PIT mutate generated contract artifacts, build output,
  dependency folders, test fixtures, or oracle reference models unless a
  registry entry explicitly makes that boundary the subject under test.
- Do not add blanket exclusions for equivalent mutants or DTOs in build files.
  Exclusions that affect score meaning belong in boundary registry data with a
  rationale.
- Do not make the mutation gate depend on Testcontainers integration tests for
  ordinary controller/service coverage. Prefer unit and `@WebMvcTest` batteries
  unless the boundary contract is explicitly persistence or integration
  behavior.
- Do not let a conditional GitHub Actions job disappear for interior-only diffs
  if branch protection expects it. The check context must be present and green
  when correctly skipped.
- Do not add prompt-only instructions to skills as the enforcement layer.
  Registry validation, CI, policy, and tool exit codes are the trust boundary.
- Do not invoke `gh`, `curl`, or live Ground Control writes from mutation
  tooling. This issue should not need privileged GitHub side effects.

## Non-Goals

- No production REST, MCP, frontend UI, persistence, audit, deployment, or
  runtime behavior change for the mutation runner itself.
- No replacement for SonarCloud, JaCoCo, Checkstyle, SpotBugs, Error Prone,
  OpenJML, ArchUnit, or the oracle battery.
- No full architecture-registry productization beyond the minimum data the gate
  must consume; broader registry modeling remains the architecture-registry
  workstream.
- No protected-path power-separation gate; threshold and battery weakening are
  addressed by the protected-path workstream.
- No runtime DAST, cloud inventory, external service scans, or provider
  credential collection.

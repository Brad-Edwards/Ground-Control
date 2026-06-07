# ADR-058: Gate manifest, runner contract, and gate-pack bundles

## Status

accepted

## Date

2026-06-06

## Context

ADR-062 establishes the portable `/implement` platform architecture: engine,
gate packs, and consuming repositories. This Architecture Decision Record (ADR)
defines the concrete manifest schema, gate-runner contract, pack bundle
format, version lock, installer behavior, and initial pack registry that sit
under that platform decision.

The `/implement` workflow already has a small provider-indirection pattern:
repositories define `completion_command`, `test_command`, and `lint_command`,
and the workflow runs those commands instead of hard-coding one ecosystem.
The redesigned workflow needs the same indirection for assurance, mutation
testing, diff coverage, static analysis, accessibility, architecture rules,
documentation gates, and required remote status checks.

ADR-027 requires `.ground-control.yaml` plus
`gc_get_repo_ground_control_context` to remain the repository configuration
boundary. The gate manifest must be resolved through that boundary and then
validated by the Model Context Protocol (MCP) server. Agents do not parse or
execute manifest commands directly.

## Decision

Ground Control uses a gate manifest at `.gc/gates.yaml`, or at an explicit
repo-relative path declared in `.ground-control.yaml`. The manifest uses an
array of gate entries, not a capability-keyed map, because multi-language
repositories can bind the same capability more than once under different path
scopes.

The manifest schema is:

```yaml
schema_version: 1
engine:
  min_version: ">=1.0.0"
  manifest_version: "1.0.0"

defaults:
  timeout_seconds: 900
  provider_missing: reviewer_fallback
  fail_fast: false

packs:
  - id: jvm-gradle
    version: "1.0.0"
    scope: backend/
  - id: node-ts
    version: "1.0.0"
    scope: frontend/

gates:
  - id: backend.type_safety
    capability: type_safety
    pack: jvm-gradle
    provider: gradle
    cwd: backend
    command: "./gradlew compileJava"
    blocking: true
    scope: changed
    applies_when:
      paths:
        - "backend/src/main/java/**"
    timeout_seconds: 600

  - id: frontend.accessibility
    capability: accessibility
    pack: node-ts
    provider: playwright-axe
    cwd: frontend
    command: "npm run test:a11y"
    blocking: true
    scope: changed
    applies_when:
      paths:
        - "frontend/src/**/*.tsx"
    threshold:
      metric: wcag_violations
      max: 0

  - id: docs.docs_policy
    capability: docs_policy
    pack: docs-generic
    command: "pre-commit run --all-files"
    blocking: true
    scope: repo
```

The example `wcag_violations` metric refers to Web Content Accessibility
Guidelines (WCAG) violations.

The schema rules are:

- unknown keys are rejected at every level;
- each `id` is globally unique within the manifest;
- `capability` must be in the ADR-062 engine vocabulary;
- `cwd`, artifact paths, config paths, and generated-file paths are
  repo-relative and containment-checked;
- `command` is a string executed by the MCP gate runner, not by agent prose;
- `command` can be absent only for MCP-native gates or declared unavailable
  providers;
- `blocking: false` gates record telemetry but do not block phase advance;
- `applies_when` is evaluated by the MCP server against the current diff;
- thresholds are typed by metric and may use `min`, `max`, `break`,
  `severity`, or `policy`;
- every result records the pack id, pack version, manifest hash, command hash,
  diff hash, exit code, duration, and artifact references.

The engine exposes one local gate runner:

```json
{
  "tool": "gc_run_gates",
  "input": {
    "repo_path": "/repo",
    "issue_number": 1075,
    "base_ref": "origin/dev",
    "head_ref": "HEAD",
    "phase": "local",
    "capabilities": ["format", "type_safety", "unit_tests"]
  }
}
```

The runner:

1. resolves `.ground-control.yaml` through
   `gc_get_repo_ground_control_context`;
2. loads the configured `.gc/gates.yaml` path through that boundary;
3. validates the manifest and `.gc/workflow-lock.json`;
4. computes the changed-file set and diff hash itself;
5. selects applicable gates by capability, scope, and `applies_when`;
6. executes each command in its declared `cwd` with a bounded timeout and
   sanitized environment;
7. parses only typed outputs that a provider declares, otherwise exit code is
   authoritative;
8. evaluates thresholds;
9. emits a structured result envelope;
10. writes a `gates_green` marker only when all blocking applicable local
    gates pass.

Failure envelopes use this shape:

```json
{
  "ok": false,
  "error": "blocking_gate_failed",
  "gate_id": "backend.mutation",
  "capability": "mutation",
  "pack": "jvm-gradle",
  "pack_version": "1.0.0",
  "blocking": true,
  "exit_code": 1,
  "threshold": { "metric": "mutation_score", "min": 60, "actual": 47 },
  "artifacts": ["backend/build/reports/pitest/index.html"],
  "next_action": "fix_tests_and_rerun_gate"
}
```

Provider-missing envelopes use this shape:

```json
{
  "ok": true,
  "status": "degraded",
  "error": "provider_missing",
  "capability": "mutation",
  "pack": "docs-generic",
  "blocking": false,
  "fallback": "reviewer_lens",
  "telemetry": ["provider_missing", "reviewer_fallback_used"]
}
```

Required remote checks are represented by the `remote_status` capability and
the `remote_gates_green` marker from ADR-061. The engine uses the manifest plus
the repository's discovered required checks to decide the expected remote
status set. Continuous integration (CI), code scanning, coverage services, and
quality dashboards are providers. They are not engine phases.

Gate packs are immutable versioned bundles. Each pack contains:

- `pack.yaml` with id, version, compatible engine range, profiles, signer, and
  checksum metadata;
- `capabilities.yaml` with capability bindings, default commands, threshold
  recommendations, typed parser declarations, and artifact paths;
- `templates/**` for manifest fragments, tool configs, and optional CI
  snippets;
- an installer entry point;
- `selftest/**` fixtures and expected outcomes;
- documentation for supported profiles and known provider gaps.

Engine and pack artifacts are distributed as signed release bundles by default:

- `gc-engine-<version>.tgz`
- `gc-gate-pack-<id>-<version>.tgz`
- `gate-catalog.json`
- Secure Hash Algorithm 256-bit (SHA-256) checksums
- provenance and signature metadata

Consumers vendor resolved artifacts under:

```text
.gc/vendor/ground-control/engine/<version>/
.gc/vendor/ground-control/packs/<pack-id>/<version>/
.gc/workflow-lock.json
.gc/gates.yaml
```

`.gc/workflow-lock.json` records exact engine and pack versions, checksums,
source URLs, compatibility ranges, signer identity, trust-policy result, and
install timestamp. `.ground-control.yaml` remains the declarative boundary:

```yaml
workflow:
  engine:
    version: "^1.0.0"
  gate_manifest: .gc/gates.yaml
  packs:
    - id: jvm-gradle
      version: "^1.0.0"
      scope: backend/
      profile: spring
    - id: node-ts
      version: "^1.0.0"
      scope: frontend/
      profile: react-vite
  gate_overrides:
    backend.mutation.threshold.min: 60
    frontend.diff_coverage.threshold.min: 90
```

The default transport is signed GitHub release artifacts with SHA-256
checksums. A Ground Control pack-registry extension and Open Container
Initiative (OCI) artifacts are allowed alternatives when they share the same
lockfile shape, signature checks, and trust policy. A mutable symlink to a
host checkout is allowed only for dogfood development and must never be the
platform distribution model.

`repo-setup` invokes an MCP installer, `gc_install_workflow_assets`, for
install and upgrade:

1. resolve semver constraints from the catalog or registry;
2. verify checksums, signatures, provenance, and trusted signer policy;
3. vendor exact engine and pack artifacts;
4. write or update `.gc/gates.yaml`;
5. write `.gc/workflow-lock.json`;
6. update `.ground-control.yaml` with semver constraints and selected scopes;
7. run every selected pack's self-test;
8. leave the repository change for normal review.

Threshold ownership has three tiers:

- platform minimums: a repository cannot silently configure below them;
- pack recommendations: default thresholds selected by a pack profile;
- repository ratchets: a repository may raise thresholds, or baseline
  pre-existing violations with an expiry, but it may not lower below the
  platform minimum.

Pack-generated manifests retain those tiers under `thresholds.platform_minimum`
and `thresholds.recommendation`. The active `threshold` is the enforced platform
minimum by default; repository ratchets update the active threshold and must name
the metric explicitly when the gate does not already carry one.

Existing `.ground-control.yaml` files with only `completion_command`,
`test_command`, or `lint_command` keep working until packs are installed. The
compatibility adapter synthesizes a temporary manifest with legacy `policy`,
`unit_tests`, and `lint` bindings. The adapter records that legacy mode was
used and does not claim pack coverage.

The initial pack registry is:

| Pack | Primary scope | Notes |
|------|---------------|-------|
| `rust-cargo` | Rust workspaces and crates | Cargo format, build, check, clippy, tests, property tests, mutation, coverage, security, and dependency policy. |
| `python` | Python packages and applications | Ruff, type checking, pytest, Hypothesis, import boundaries, mutation, coverage, security, and dependency policy. |
| `jvm-gradle` | Gradle Java Virtual Machine (JVM) projects | Spotless, compile, nullness and static checks, tests, property tests, architecture, complexity, mutation, coverage, security, and dependency policy. |
| `jvm-maven` | Maven JVM projects | Maven equivalents for format, verify, compiler checks, Surefire, Failsafe, property tests, architecture, complexity, mutation, coverage, security, and dependency policy. |
| `node-ts` | TypeScript and JavaScript packages, services, and frontends | Package-manager scripts, type checking, test runners, schema contracts, property tests, architecture, mutation, coverage, security, dependency policy, and accessibility profiles. |
| `cpp-cmake` | C and C++ CMake projects | Format, configure, build, warnings-as-errors, tests, sanitizers, fuzz or property harnesses, coverage, security, and dependency policy. |
| `docs-generic` | Documentation, policy, and code-light repositories | Documentation policy, link checks, secrets, dependency and workflow policy, and traceability. Code assurance capabilities are `not_applicable` unless another pack is selected. |

Pack capability bindings are scoped examples, not engine preferences:

| Capability | `rust-cargo` | `python` | `jvm-gradle` | `jvm-maven` | `node-ts` | `cpp-cmake` | `docs-generic` |
|------------|--------------|----------|--------------|-------------|-----------|-------------|----------------|
| `format` | `cargo fmt --all --check` | Ruff format check | Spotless | Spotless Maven | Biome, Prettier, or script | clang-format check | pre-commit or docs formatter |
| `lint` | Clippy | Ruff | Checkstyle or PMD | Checkstyle or PMD Maven | Biome or ESLint | clang-tidy or lizard | markdownlint or policy hook |
| `build` | Cargo build | package build command | Gradle assemble or build | Maven package or verify | package build script | CMake configure and build | not applicable |
| `type_safety` | Cargo check | pyright or mypy | compiler, nullness, static checks | compiler, nullness, static checks | TypeScript compiler | warnings as errors and clang-tidy | not applicable |
| `unit_tests` | Cargo test | pytest | Gradle test | Surefire | Vitest or Jest | CTest | not applicable |
| `integration_tests` | workspace integration target | pytest marker | Gradle integration task | Failsafe | Playwright or script | CTest labels | not applicable |
| `contract_boundary` | type-level and runtime guards | Pydantic or runtime contracts | Bean Validation, JML where useful, guards | Bean Validation, JML where useful, guards | Zod, Valibot, or assertions | assertions or error-return contracts | not applicable |
| `property_verification` | proptest or quickcheck | Hypothesis | jqwik | jqwik or equivalent | fast-check | fuzz or property harness | not applicable |
| `architecture` | workspace policy | import-linter | ArchUnit | ArchUnit | dependency-cruiser or ts-arch | include and dependency graph checks | not applicable |
| `complexity` | Clippy profile | Ruff or radon | Checkstyle or PMD | Checkstyle or PMD | lint complexity rules | clang-tidy or lizard | not applicable |
| `mutation` | cargo-mutants | mutmut or Cosmic Ray | PIT | PIT Maven | StrykerJS | Mull where practical | not applicable |
| `diff_coverage` | llvm-cov adapter | coverage plus diff-cover | JaCoCo plus diff-cover | JaCoCo plus diff-cover | coverage plus diff-cover | llvm-cov or gcovr plus diff-cover | not applicable |
| `sast` | Semgrep or code scanning | Bandit or Semgrep | SpotBugs, Semgrep, or code scanning | SpotBugs, Semgrep, or code scanning | Semgrep or code scanning | static analyzer, Semgrep, or code scanning | not applicable |
| `secret_scan` | Gitleaks | Gitleaks | Gitleaks | Gitleaks | Gitleaks | Gitleaks | Gitleaks or detect-private-key |
| `dependency_policy` | cargo audit or cargo deny | pip-audit, uv audit, or OSV | OSV or dependency-check | OSV or dependency-check | npm audit or OSV | package-manager audit or software bill of materials scan | workflow, action, and infrastructure policy |
| `accessibility` | not applicable | not applicable | not applicable | not applicable | JSX lint and rendered-route checks | not applicable | not applicable |
| `docs_policy` | docs profile | docs profile | docs profile | docs profile | docs profile | docs profile | Vale, markdown policy, links, and ADR coverage |
| `traceability` | Ground Control traceability where configured | Ground Control traceability where configured | Ground Control traceability where configured | Ground Control traceability where configured | Ground Control traceability where configured | Ground Control traceability where configured | Ground Control traceability where configured |
| `policy` | repo policy | repo policy | repo policy | repo policy | repo policy | repo policy | repo policy |
| `remote_status` | required remote checks | required remote checks | required remote checks | required remote checks | required remote checks | required remote checks | required remote checks |

Each pack must ship a self-test fixture that proves install, manifest
validation, one passing gate, one failing gate, and one provider-missing or
not-applicable case. A pack that cannot self-test cannot ship as a supported
pack.

## Consequences

The workflow can require capabilities such as mutation, diff coverage,
accessibility, dependency policy, or required remote status checks without
becoming tied to one language or provider.

Repositories keep control over concrete tools and thresholds, but those
choices become explicit, versioned, and auditable through `.ground-control.yaml`,
`.gc/gates.yaml`, and `.gc/workflow-lock.json`.

Multi-language repositories are first-class. The same capability is green only
when every applicable scoped gate passes for the current diff.

Code-light repositories are not forced through build, type, mutation, or
coverage gates. They still run documentation, policy, traceability, dependency,
and secret controls where configured.

The compatibility adapter preserves existing consumers while the pack registry
rolls out. The adapter is intentionally visible telemetry so legacy command
mode does not look like a fully installed gate-pack configuration.

The manifest and pack system add release and maintenance work. Schemas,
installer logic, trust policy, pack fixtures, and threshold ratchets must be
tested as platform surfaces, not left as repository-local conventions.

## Amendments

**2026-06-06 (issue #1075 Phase 6).** The release pipeline is implemented in
`workflow/tools/materialize-pack-registry.mjs`. It emits
`workflow/releases/gc-engine-<version>.tgz`,
`workflow/releases/gc-gate-pack-<id>-<version>.tgz`, and
`workflow/gate-catalog.json`. Catalog `sha256` fields cover the release
artifacts that the installer vendors; `source_sha256` fields record source-tree
drift metadata. `gc_install_workflow_assets` verifies engine and pack artifact
checksums, vendors exact versions, writes the stricter workflow lockfile, and
supports explicit `mode: install` or `mode: upgrade`. Signature and provenance
verification remains marked TODO in catalog and lock metadata per ADR-062.

The pack self-test contract is CI-backed by `.github/workflows/workflow-platform.yml`.
Each of the seven supported packs has a matrix entry with the expected
toolchain setup, and unexpected self-test skips fail that entry. The same
workflow runs `workflow/tools/verify-workflow-release.mjs` to validate catalog
checksums.

## References

- ADR-027: Agent-Neutral Implement Workflow Packaging.
- ADR-036: Per-Step Model Routing, Durable-Record Tool Surfaces, and Step
  Telemetry.
- ADR-057: Language-neutral assurance ladder and classifier.
- ADR-059: The engineering contract.
- ADR-061: Governable phase-marker state machine.
- ADR-062: Portable /implement engine, gate-pack registry, and consumer
  adoption model.

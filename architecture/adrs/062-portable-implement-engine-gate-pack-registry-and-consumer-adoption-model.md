# ADR-062: Portable /implement engine, gate-pack registry, and consumer adoption model

## Status

accepted

## Date

2026-06-06

## Context

The `/implement` redesign must serve many repositories. Some are Java Virtual
Machine (JVM) applications, some are Rust, Python, TypeScript, C or C++, some
are documentation-first, and some combine several ecosystems in one
repository. Ground Control is one consumer and dogfood host, but its own gates
must not define the platform.

The first Phase 0 shape mixed two concerns:

- a portable `/implement` engine that owns phase markers, durable records,
  configuration boundaries, gate execution, telemetry, and fallback semantics;
- a Ground Control consumer adoption pass for one JVM, frontend, and Model
  Context Protocol (MCP) repository.

Those concerns must be separated. The engine must name capabilities and
structured workflow states. Gate packs must bind capabilities to ecosystem
tools. Consuming repositories must select packs and thresholds through
`.ground-control.yaml`.

Physical distribution is part of the platform contract. A mutable symlink to a
host checkout is convenient for dogfood development, but it is not safe for
many repositories. Consumers need immutable, signed, versioned assets and a
lockfile that records exactly what was installed.

## Decision

The `/implement` platform has three roles:

| Role | Responsibility |
|------|----------------|
| Engine | Canonical workflow prose and MCP tools. Owns the state machine, durable records, `.ground-control.yaml` context boundary, manifest validation, gate execution, telemetry, reviewer fallback semantics, and marker writing. |
| Packs | Versioned ecosystem bundles. Each pack binds capabilities to tools, templates, installer actions, default thresholds, self-tests, and classifier patterns. |
| Consumers | Repositories that select engine and pack versions, scopes, profiles, and threshold ratchets in `.ground-control.yaml`. |

The engine standardizes this capability vocabulary:

| Capability | Meaning |
|------------|---------|
| `format` | Deterministic formatting check. |
| `lint` | Style and non-semantic lint rules not otherwise classified. |
| `build` | Compile, package, or build succeeds. |
| `type_safety` | Static type, nullness, or schema checks. |
| `unit_tests` | Fast behavioral test suite. |
| `integration_tests` | Slower system or integration test suite. |
| `contract_boundary` | L1 boundary validation, preconditions, postconditions, schemas, or runtime guards plus tests. |
| `property_verification` | L2 property-based or equivalent state-space checks. |
| `architecture` | Layering, dependency, Architecture Decision Record (ADR), package, and cross-cutting conventions as code. |
| `complexity` | Size, cyclomatic or cognitive complexity, NPath, fanout, and god-class or god-method checks. |
| `mutation` | Mutation testing on changed or critical code. |
| `diff_coverage` | Coverage on changed lines or branches. |
| `sast` | Static application security testing (SAST). |
| `secret_scan` | Secret and private-key detection. |
| `dependency_policy` | Vulnerability, license, lockfile, and supply chain policy. |
| `accessibility` | Static and runtime accessibility gates. |
| `docs_policy` | Markdown, prose, link, ADR, and documentation coverage gates. |
| `traceability` | Ground Control requirement and artifact reconciliation. |
| `policy` | Repository-native structural policy. |
| `remote_status` | Required remote checks after publication, provider-neutral. |

`remote_status` replaces provider-specific remote phases as the platform
concept. Continuous integration (CI), code scanning, coverage services, and
quality dashboards are providers. They can contribute remote status records,
but they are not engine phases.

The gate manifest is `.gc/gates.yaml` unless `.ground-control.yaml` declares a
different repo-relative path. ADR-058 defines the detailed schema. Platform
rules for the manifest are:

- use an array of gate entries because one capability can have multiple scoped
  providers;
- reject unknown keys at every level;
- require globally unique gate ids;
- require every `capability` to be in the engine vocabulary;
- containment-check all path-valued fields;
- type thresholds by metric with `min`, `max`, `break`, `severity`, or
  `policy`;
- let the MCP server evaluate `applies_when` against the current diff;
- execute commands only through the MCP gate runner;
- record pack id, pack version, manifest hash, command hash, diff hash, exit
  code, duration, threshold result, and artifacts for every gate.

The engine exposes one local gate runner, `gc_run_gates`. It resolves
`.ground-control.yaml` through `gc_get_repo_ground_control_context`, loads and
validates the gate manifest and workflow lockfile, computes the changed files
and diff hash, selects applicable gates, executes provider commands with
bounded timeouts and a sanitized environment, evaluates typed thresholds, emits
a structured envelope, and writes `gates_green` only when all applicable
blocking local gates pass.

The marker semantics are:

- `gates_green` binds to issue, repository, base ref, head ref, manifest hash,
  diff hash, selected pack versions, and the gate-result envelope id;
- `remote_gates_green` binds to issue, pull request, head SHA, manifest hash,
  diff hash, required-status-set hash, and provider result ids;
- a marker is stale when the diff hash, manifest hash, pack versions, head
  SHA, or required remote status set no longer match;
- degraded provider-missing results may advance only when the manifest marks
  the gate non-blocking or declares reviewer fallback for that capability;
- marker writes happen inside MCP tools after re-verification, never from
  agent prose.

Gate packs are immutable versioned bundles with this shape:

```text
workflow/engine/
  engine.yaml
  skills/implement/**
  skills/quickfix/**
  mcp/**
  schemas/**
  evals/**
  selftest/**

workflow/packs/
  rust-cargo/
    pack.yaml
    capabilities.yaml
    templates/**
    installer.*
    selftest/**
  python/
  jvm-gradle/
  jvm-maven/
  node-ts/
  cpp-cmake/
  docs-generic/
```

Each release produces:

- `gc-engine-<version>.tgz`;
- `gc-gate-pack-<id>-<version>.tgz`;
- `gate-catalog.json`;
- Secure Hash Algorithm 256-bit (SHA-256) checksums;
- provenance and signature metadata.

Consumers store resolved artifacts under:

```text
.gc/vendor/ground-control/engine/<version>/
.gc/vendor/ground-control/packs/<pack-id>/<version>/
.gc/workflow-lock.json
.gc/gates.yaml
```

`.gc/workflow-lock.json` records exact engine and pack versions, checksums,
source URLs, compatibility ranges, signer identity, trust-policy result, and
install timestamp. The lockfile is the common shape for every transport.

The default transport is signed GitHub release artifacts plus SHA-256
checksums. A Ground Control pack-registry extension and Open Container
Initiative (OCI) artifacts are valid alternatives when they use the same
lockfile, compatibility, checksum, signature, and trusted-signer policy. A
mutable symlink to a host checkout is dogfood-only and must not be the
platform distribution model.

Consumers declare semver constraints, pack scopes, profiles, and overrides in
`.ground-control.yaml`:

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
    - id: docs-generic
      version: "^1.0.0"
      scope: docs/
  gate_overrides:
    backend.mutation.threshold.min: 60
    frontend.diff_coverage.threshold.min: 90
```

Threshold ownership has three tiers:

- platform minimums: a repository cannot silently configure below them;
- pack recommendations: default thresholds selected by a pack profile;
- repository ratchets: a repository may raise thresholds, or baseline
  pre-existing violations with an expiry, but it may not lower below the
  platform minimum.

Generated manifests carry both pack tiers in `thresholds.platform_minimum` and
`thresholds.recommendation`; `threshold` is the active enforced threshold. A
`gate_overrides` operator override such as `.threshold.min` is valid only when
that active threshold already has a metric or the same override set supplies
`.threshold.metric`.

`repo-setup` installs and upgrades workflow assets through the MCP installer
`gc_install_workflow_assets`. The installer resolves semver constraints,
verifies checksums and signatures, vendors exact artifacts, writes
`.gc/gates.yaml`, writes `.gc/workflow-lock.json`, updates
`.ground-control.yaml`, runs each selected pack's self-test, and leaves a
normal repository change for review.

Each pack must self-test. A supported pack's fixture must prove install,
manifest validation, at least one passing gate, at least one failing gate, and
the expected provider-missing or not-applicable behavior. A pack that cannot
self-test cannot ship as supported.

Multi-language aggregation is normal. The manifest can carry several scoped
packs, and `gc_run_gates` aggregates all applicable blocking gates. The same
capability may appear several times. A capability is green only if every
applicable scoped gate for the current diff is green.

Code-light repositories use `docs-generic`. They must not be asked for build,
type-safety, mutation, contract, property, architecture, complexity, or
accessibility gates unless they explicitly select a code pack for a path.
`docs-generic` still supports `docs_policy`, `secret_scan`,
`dependency_policy`, `policy`, and `traceability`.

Provider-missing behavior is explicit. If a capability has no deterministic
provider, the result records `provider_missing`, the selected pack, whether the
gate was blocking, the fallback, and telemetry such as
`reviewer_fallback_used`. A reviewer lens can block by verdict, but it cannot
claim that a missing deterministic provider passed.

Existing `.ground-control.yaml` files with only `completion_command`,
`test_command`, or `lint_command` remain compatible until packs are installed.
The compatibility adapter synthesizes temporary `policy`, `unit_tests`, and
`lint` gates from those commands, records legacy-mode telemetry, and does not
claim pack coverage.

The initial supported pack families are:

- `rust-cargo`
- `python`
- `jvm-gradle`
- `jvm-maven`
- `node-ts`
- `cpp-cmake`
- `docs-generic`

ADR-058 is the detail ADR for this registry, including capability examples,
manifest examples, bundle fields, and installer rules.

## Consequences

The `/implement` engine becomes portable. It can enforce a stable capability
vocabulary and phase-marker model without importing any one consumer's tool
chain into the platform.

Gate packs become products. They need release artifacts, signatures,
checksums, compatibility metadata, self-tests, upgrade behavior, and
maintained classifier patterns.

Consumers get explicit control over pack selection, scopes, profiles, and
ratchets while staying inside `.ground-control.yaml` as the only configuration
boundary.

Remote checks become provider-neutral. Required status discovery, manifest
declarations, and `remote_gates_green` records define the gate; individual
remote providers are adapters.

Legacy command-only consumers keep working while the pack registry rolls out,
but legacy mode is visible telemetry and not a substitute for versioned pack
adoption.

The design adds operational burden. The platform must maintain engine
releases, pack releases, trusted-signer policy, lockfile validation,
installer tests, pack fixtures, and upgrade tests before broad rollout.

## Amendments

**2026-06-06 (issue #1075 Phase 6).** Phase 6 implements the maintainability
surface for this ADR. The release materializer builds immutable engine and
gate-pack `.tgz` artifacts under `workflow/releases/` and writes a catalog with
real SHA-256 checksums. `gc_install_workflow_assets` resolves semver
constraints, verifies engine and pack checksums, vendors exact versions under
`.gc/vendor/ground-control/`, writes a lockfile with required checksum,
source, signer, trust-policy, and install timestamp fields, updates
`.ground-control.yaml`, and runs pack self-tests. Signature and provenance
verification remain explicit TODO metadata; checksum and lockfile enforcement
are live.

The versioned workflow eval suite lives under
`workflow/evals/engine-behavior/v1/` and runs through
`workflow/tools/eval-workflow-engine.mjs`. It covers review convergence,
completion-gate prerequisites, remote-quality substance, context loading,
traceability staleness, lockfile shape, and legacy command compatibility with
deterministic pass/fail checks. `.github/workflows/workflow-platform.yml` runs
release validation, the eval suite, and the seven-pack self-test matrix on
changes to `skills/`, `mcp/`, or `workflow/`.

## References

- ADR-021: Gated Agentic Development Loop.
- ADR-027: Agent-Neutral Implement Workflow Packaging.
- ADR-029: Issue-Thread Gate Model.
- ADR-036: Per-Step Model Routing, Durable-Record Tool Surfaces, and Step
  Telemetry.
- ADR-057: Language-neutral assurance ladder and classifier.
- ADR-058: Gate manifest, runner contract, and gate-pack bundles.
- ADR-061: Governable phase-marker state machine.

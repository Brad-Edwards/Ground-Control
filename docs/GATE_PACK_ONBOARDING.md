# Gate Pack Onboarding

This guide is the canonical onboarding path for a repository that adopts the
portable `/implement` gate-pack platform.

## Platform Model

The platform has three roles. The engine owns the `/implement` state machine,
Model Context Protocol (MCP) tools, gate manifest validation, gate execution,
telemetry, and phase-marker writes. Packs bind engine capabilities to
language-specific tools, templates, classifiers, default thresholds, and
self-tests. Consumers are repositories that select engine and pack versions,
scopes, profiles, baselines, and ratchets in committed repository config. The
engine source lives in `mcp/ground-control/`, engine release metadata lives in
`workflow/engine/`, pack sources live in `workflow/packs/`, release tooling
lives in `workflow/tools/`, and the pack registry lives in
`workflow/gate-catalog.json`.

## Host And Non-Host Repositories

Ground Control is the host. It contains the engine source in
`mcp/ground-control/` and the pack sources in `workflow/packs/`, so it never
vendors the engine or packs under `.gc/vendor/`. `gc_install_workflow_assets`
detects the host by the presence of `mcp/ground-control/lib.js`, still verifies
release checksums, writes the manifest, lockfile, and pack config, and returns
`vendoring.status: "skipped"`.

Every other repository is a consumer. A consumer has no engine source. It gets
the engine and packs either by installing versioned artifacts into
`.gc/vendor/ground-control/` or by running gates through the shared Ground
Control host MCP server. The default installer path vendors exact release
artifacts under `.gc/vendor/ground-control/engine/<version>/` and
`.gc/vendor/ground-control/packs/<pack-id>/<version>/`.

Commit these files in a consumer repository:

- `.ground-control.yaml`, including `workflow.engine`, `workflow.gate_manifest`,
  `workflow.packs[]`, and any `workflow.gate_overrides`.
- `.gc/gates.yaml`.
- `.gc/workflow-lock.json`.
- `.gc/gate-packs/<pack-id>/` files copied from pack templates.
- Tool baselines, ratchet files, and build or continuous integration (CI)
  wiring that the gates use.
- Ecosystem lockfiles such as `package-lock.json`, `pnpm-lock.yaml`,
  `Cargo.lock`, `gradle.lockfile`, or Maven lock extensions when the project
  uses them.

Do not commit these generated artifacts:

- `.gc/vendor/`, which is gitignored and re-materialized by install or by using
  the shared host MCP server.
- `workflow/releases/*.tgz`, which are host-side release artifacts built by the
  platform materializer and ignored by Git.

## Onboard A Consumer Repository

1. Pick packs by language and path scope.

   Use `rust-cargo` for Rust Cargo projects, `python` for Python projects,
   `jvm-gradle` for Gradle Java Virtual Machine (JVM) projects, `jvm-maven`
   for Maven JVM projects, `node-ts` for TypeScript or JavaScript projects,
   `cpp-cmake` for C or C++ CMake projects, and `docs-generic` for
   documentation-focused or code-light repositories. Multi-language
   repositories declare one scoped pack per ecosystem, for example
   `backend`, `frontend`, and `docs`.

1. Run the workflow-asset installer once per pack.

   In a repo setup workflow, the gate-pack step is a call to
   `gc_install_workflow_assets`. The MCP input is:

   ```json
   {
     "repo_path": "/absolute/path/to/consumer",
     "pack_id": "node-ts",
     "version": "^1.0.0",
     "engine_version": "^1.0.0",
     "scope": "frontend",
     "profile": "react-vite",
     "mode": "install",
     "install_dependencies": true,
     "run_selftest": true
   }
   ```

   The local command-line wrapper accepts the same fields as flags:

   ```bash
   node /path/to/Ground-Control/workflow/tools/install-workflow-assets.mjs \
     --repo /absolute/path/to/consumer \
     --pack node-ts \
     --version '^1.0.0' \
     --engine-version '^1.0.0' \
     --scope frontend \
     --profile react-vite \
     --mode install
   ```

   Use `--selftest false` or `--install-dependencies false` only when the
   onboarding record explains why. Use `--mode upgrade` or `--upgrade` when
   refreshing an existing pack. The current installer handles one pack per
   invocation, so repeat the call for each scoped pack.

1. Review what the installer writes.

   The installer merges `workflow.engine`, `workflow.gate_manifest`, and
   `workflow.packs[]` into `.ground-control.yaml`; writes or merges
   `.gc/gates.yaml`; writes `.gc/workflow-lock.json`; copies pack templates
   into `.gc/gate-packs/<pack-id>/`; optionally installs pack-declared
   development dependencies for supported Node and Python projects; and runs
   the selected pack self-test. Consumer installs also write `.gc/vendor/`.
   Host installs skip `.gc/vendor/`.

1. Baseline existing violations without weakening new-code gates.

   Pre-existing violations belong in explicit baselines, suppressions, or
   ratchet files committed with the gate configuration. The gate stays blocking
   for new findings. Do not lower platform minimum thresholds to make adoption
   pass. If a pack recommendation is too strict for existing code, keep the
   platform minimum as the active threshold, record the current findings, and
   raise the repo ratchet after the backlog is reduced.

1. Wire gate execution into the workflow.

   `/implement` uses `gc_run_gates` after a fresh `impl_green` marker exists.
   The MCP input is:

   ```json
   {
     "repo_path": "/absolute/path/to/consumer",
     "issue_number": 1234,
     "base_ref": "origin/dev",
     "head_ref": "HEAD",
     "phase": "local",
     "capabilities": ["format", "lint", "unit_tests", "policy"]
   }
   ```

   Omit `capabilities` to let the engine select every applicable local gate.
   The tool resolves `.ground-control.yaml`, validates `.gc/gates.yaml` and
   `.gc/workflow-lock.json`, computes the diff, selects gates by capability and
   path, runs commands with bounded timeouts, evaluates thresholds, writes
   telemetry, and writes `gates_green` only when every applicable blocking
   local gate passes.

   For a local dry run that does not post markers, use the wrapper in the host
   checkout:

   ```bash
   node /path/to/Ground-Control/workflow/tools/run-gates.mjs \
     --repo /absolute/path/to/consumer \
     --issue 1234 \
     --base origin/dev \
     --head HEAD \
     --capabilities format,lint,unit_tests,policy
   ```

   The wrapper also accepts `--changed-files` or the `GC_GATE_CHANGED_FILES`
   environment variable for a synthetic changed-file set.

1. Wire CI and remote status.

   CI must run the build, test, analysis, and policy commands that the manifest
   expects, or expose equivalent required statuses for `remote_status` gates.
   The committed CI wiring is part of the consumer adoption change. Remote
   quality checks stay provider-specific, but the engine treats them through
   provider-neutral `remote_status` gates.

1. Verify adoption.

   A complete adoption has green local gates, committed baselines for accepted
   existing violations, a valid `.gc/workflow-lock.json`, pack templates under
   `.gc/gate-packs/`, and CI wiring that produces the required statuses. The
   Ground Control adoption note at
   [`docs/GATE_PACK_ADOPTION.md`](GATE_PACK_ADOPTION.md) is the worked example
   for this repository.

## What `git pull` Brings

`git pull` brings committed configuration and evidence: `.ground-control.yaml`,
`.gc/gates.yaml`, `.gc/workflow-lock.json`, `.gc/gate-packs/`, baselines,
ratchet files, and CI or build wiring. It also brings committed ecosystem
lockfiles, so build dependencies resolve on the first build through the
project's normal package manager.

`git pull` does not bring gitignored generated artifacts. It does not bring
`.gc/vendor/` in a consumer repository, and it does not bring
`workflow/releases/*.tgz` in the host repository. After cloning or pulling a
consumer checkout that needs local vendored assets, run the installer for each
declared pack to re-materialize `.gc/vendor/` from the committed manifest,
lockfile, and catalog. A consumer that runs through the shared host MCP server
does not need a local engine source checkout.

Ground Control is different because it is the host. A pull brings the engine
source in `mcp/ground-control/` and the pack sources in `workflow/packs/`.
Running the installer in this repository skips `.gc/vendor/`.

## Pack Table

The pack registry is [`workflow/gate-catalog.json`](../workflow/gate-catalog.json).
Pack source and capability bindings live under [`workflow/packs/`](../workflow/packs/).

| Pack | Use when | Capability bindings and tools |
|------|----------|-------------------------------|
| `rust-cargo` | Rust Cargo workspace or crate | `format` cargo-fmt; `lint` cargo-clippy; `build` cargo-build; `type_safety` cargo-check; `unit_tests` and `integration_tests` cargo-test; `property_verification` proptest or quickcheck; `architecture` cargo workspace policy; `complexity` clippy complexity; `mutation` cargo-mutants; `diff_coverage` cargo-llvm-cov and diff-cover; `sast` Semgrep; `secret_scan` Gitleaks; `dependency_policy` cargo-audit and cargo-deny; `docs_policy`, `policy`, and `remote_status`. |
| `python` | Python application or package | `format` ruff format; `lint` ruff check; `build` python build; `type_safety` pyright or mypy; `unit_tests` and `integration_tests` pytest; `property_verification` Hypothesis; `architecture` import-linter; `complexity` Ruff C901; `mutation` mutmut; `diff_coverage` coverage and diff-cover; `sast` Bandit; `secret_scan` Gitleaks; `dependency_policy` pip-audit or uv audit; `docs_policy`, `policy`, and `remote_status`. |
| `jvm-gradle` | Gradle JVM project | `format` Spotless; `lint` Checkstyle and PMD; `build` Gradle build; `type_safety` compile tasks; `unit_tests` Gradle test; `integration_tests` Gradle integration test; `property_verification` jqwik; `architecture` ArchUnit; `complexity` Checkstyle and PMD complexity; `mutation` PIT; `diff_coverage` JaCoCo and diff-cover; `sast` SpotBugs and Semgrep; `secret_scan` Gitleaks; `dependency_policy` OSV Gradle; `docs_policy`, `policy`, and `remote_status`. |
| `jvm-maven` | Maven JVM project | `format` Spotless; `lint` Checkstyle and PMD; `build` Maven verify; `type_safety` Maven compiler; `unit_tests` Surefire; `integration_tests` Failsafe; `property_verification` jqwik; `architecture` ArchUnit; `complexity` Checkstyle and PMD complexity; `mutation` PIT Maven; `diff_coverage` JaCoCo and diff-cover; `sast` SpotBugs; `secret_scan` Gitleaks; `dependency_policy` Dependency-Check Maven; `docs_policy`, `policy`, and `remote_status`. |
| `node-ts` | TypeScript or JavaScript project | `format` package format scripts; `lint` Biome or ESLint; `build` package build; `type_safety` TypeScript; `unit_tests` Node test runner or package test; `integration_tests` Playwright or package script; `property_verification` fast-check; `architecture` dependency-cruiser; `complexity` Biome or ESLint complexity; `mutation` StrykerJS; `diff_coverage` Vitest coverage and diff-cover; `sast` Semgrep; `secret_scan` Gitleaks; `dependency_policy` npm audit or OSV; `accessibility` JSX a11y and Playwright axe; `docs_policy`, `policy`, and `remote_status`. |
| `cpp-cmake` | C or C++ CMake project | `format` clang-format; `lint` clang-tidy; `build` CMake build; `type_safety` compiler warnings as errors and clang-tidy; `unit_tests` and `integration_tests` CTest; `property_verification` fuzz or property harness; `architecture` include dependency policy; `complexity` lizard; `mutation` Mull; `diff_coverage` gcovr and diff-cover; `sast` clang static analyzer; `secret_scan` Gitleaks; `dependency_policy` software bill of materials scan; `docs_policy`, `policy`, and `remote_status`. |
| `docs-generic` | Documentation-focused or code-light repository | `format` pre-commit docs format; `lint` Markdown policy; `sast` Semgrep config checks; `secret_scan` Gitleaks or private-key detection; `dependency_policy` workflow policy; `docs_policy` Vale Markdown policy; `policy` repo or docs policy; `remote_status` required statuses. Code build, type-safety, tests, mutation, coverage, architecture, complexity, contract, property, and accessibility gates are not applicable by default. |

`contract_boundary` and `traceability` are provider-missing or repository-owned
for code packs by default. A reviewer fallback may record that deterministic
coverage is missing, but it does not claim the provider passed.

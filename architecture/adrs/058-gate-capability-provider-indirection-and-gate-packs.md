# ADR-058: Gate capability-to-provider indirection and gate packs

## Status

accepted

## Date

2026-06-06

## Context

The `/implement` workflow already has a small provider-indirection pattern:
repositories define `completion_command`, `test_command`, and `lint_command`,
and the workflow runs those commands instead of hard-coding one ecosystem.
The redesigned workflow needs the same indirection for assurance, mutation
testing, diff coverage, static analysis, accessibility, architecture rules,
and documentation gates.

The canonical workflow must stay language-neutral. Concrete providers differ by
repository: Java uses Gradle, OpenJML, jqwik, PIT, ArchUnit, SpotBugs, and
SonarCloud; TypeScript uses `tsc`, Biome or ESLint, fast-check,
dependency-cruiser, Stryker, and axe; Python uses mypy or pyright, Ruff,
Hypothesis, import-linter, mutmut, and Pydantic or icontract. The workflow must
name the capability and let the repo bind it.

ADR-027 requires `.ground-control.yaml` plus
`gc_get_repo_ground_control_context` to remain the configuration boundary.
Any gate manifest must be resolved through that boundary and not parsed ad hoc
by agent prose.

## Decision

Ground Control uses a gate manifest at `.gc/gates.yaml`. The manifest maps a
capability to its provider command, threshold, blocking behavior, scope, and
applicability. A repository adopts the default `.gc/gates.yaml` path or an
explicit repo-relative path through `.ground-control.yaml`; the Ground Control
context tool resolves and validates the manifest. Agents do not parse the
manifest directly.

The manifest contract is:

```yaml
gates:
  capability_name:
    command: "repo-native command or make target"
    threshold: 90
    blocking: true
    applies_when: "glob, language, or changed-surface predicate"
    scope: "changed | touched | repo | package"
```

`command` is optional only for capabilities enforced by a Model Context
Protocol (MCP) refusal. `threshold`, `applies_when`, and `scope` are optional
when a capability does not use them. Unknown keys are rejected. Path-valued
fields must be repo-relative and must pass containment checks.

The workflow names these capabilities, not concrete tools:

- `format`
- `type_safety`
- `unit_tests`
- `integration_tests`
- `architecture`
- `complexity`
- `contract_boundary`
- `mutation`
- `diff_coverage`
- `static_application_security_testing`
- `secret_scan`
- `dependency_policy`
- `accessibility`
- `documentation`
- `traceability`
- `policy`

Every blocking gate must be one of two forms:

- a repo-native command that exits non-zero on failure; or
- an MCP tool refusal with a structured envelope.

Agent-only hooks, driver-local scripts, and free-form reviewer prose are not
gate providers. Hooks may mirror a gate for faster local feedback, but the
source of enforcement must live in agent-neutral layers: `bin/policy`,
Gradle or ecosystem build tasks, continuous integration (CI), or MCP tools.

Gate packs supply vetted defaults and scaffolding for common ecosystems. The
initial pack families are:

- `java-gradle`: Gradle `check`, Checkstyle or PMD complexity, NullAway,
  find-sec-bugs, OpenJML, jqwik, PIT, ArchUnit, JaCoCo, diff-cover, Semgrep,
  and SonarCloud new-code coverage;
- `ts-react`: `tsc`, Biome or ESLint, `exactOptionalPropertyTypes`,
  dependency-cruiser, Vitest, fast-check, Stryker, axe or Playwright
  accessibility checks, and Semgrep;
- `python`: mypy or pyright, Ruff, import-linter, Hypothesis, mutmut,
  Pydantic, icontract or deal, Bandit or Semgrep, and diff-cover;
- `node-mcp`: TypeScript or JavaScript type checks, tool-schema tests,
  dependency policy, Semgrep, contract tests, and MCP wrapper parity checks;
- `docs-iac`: Vale, markdown policy checks, Terraform format and validation,
  Checkov, Trivy, and documentation coverage checks.

Packs are adopted through the existing `repo-setup` skill and the repository
`packs/` distribution surface. Adoption copies or generates provider configs
and manifest entries, but the workflow still consumes the result through
`.ground-control.yaml` and the Ground Control context tool.

When a capability is unavailable, the manifest records that fact. The workflow
then degrades to the matching large language model (LLM) reviewer lens and
flags the degradation in telemetry. A reviewer lens can add findings and can
block through its verdict envelope, but it cannot grant a deterministic pass
for the missing capability. The telemetry signal identifies the next pack gap
to close.

## Consequences

The workflow can require capabilities such as mutation score, diff coverage, or
accessibility without becoming a Java, TypeScript, or Python workflow.

Repositories keep control over concrete tools and thresholds. The same
capability can bind to PIT in Java, Stryker in TypeScript, mutmut in Python,
and no provider in documentation-only repositories.

The existing `completion_command`, `test_command`, and `lint_command` pattern
generalizes to the full gate matrix. Future capabilities add manifest entries
and pack defaults instead of new skill lanes.

Gate availability becomes visible. Missing providers are telemetry events,
not silent passes, so the gate packs can improve over time.

The manifest introduces another repo-policy artifact that must stay in sync
with `.ground-control.yaml`, the context parser, and workflow documentation.
ADR-027 still governs the configuration boundary.

## References

- ADR-027: Agent-Neutral Implement Workflow Packaging.
- ADR-036: Per-Step Model Routing, Durable-Record Tool Surfaces, and Step Telemetry.
- ADR-057: Language-neutral assurance ladder and classifier.
- ADR-059: The engineering contract.

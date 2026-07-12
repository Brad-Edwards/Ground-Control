# CLD Architecture Registry Preflight

Issue: #1295
Requirement: GC-CLD-2

This is architecture guardrail guidance for the CLD-managed architecture
registry. It is not an implementation plan.

## Boundary

GC-CLD-2 creates the repository-side data contract for CLD structural
boundaries. The registry describes modules, allowed dependency edges,
contract-bearing boundary identifiers, ownership, lock level, risk score, and
gate-selection metadata as committed data.

Keep these concepts separate:

- The CLD architecture registry is the structural contract authority for
  implementation boundaries. It is not workflow prose, an ADR table, or a
  prompt instruction.
- `.ground-control.yaml` remains the agent-neutral workflow context contract
  per ADR-027. Do not move the registry into that file or make agents parse it
  directly as an extra workflow schema.
- `architecture/registry/protected-paths.json` is the authority model for
  design-authority approval and protected selectors. The architecture registry
  must feed or align with that gate, not bypass it.
- `architecture/registry/mutation-boundaries.json` is the current mutation
  gate registry. It may reference CLD boundary ids after GC-CLD-2, but it
  should not remain a competing source of lock-level truth.
- `BoundaryModelSnapshot` and the architecture-model aggregate are GRC model
  outputs. The CLD registry may project into them, but it is not itself the
  graph-native DFD aggregate, a trust-boundary snapshot, or an asset-topology
  model.
- A module, a dependency edge, and a contract-bearing boundary are distinct
  registry concepts. A package path is only a selector for one of those
  concepts, not the concept identity.

Locked or guarded registry changes are design-authority events when they add,
remove, lower, raise, rename, re-own, re-score, or materially alter a protected
boundary, allowed edge, selector, or gate-selection rule. The policy decision
must use the base branch's authority model when one exists, matching the
protected-path gate's current anti-self-approval design.

## Incumbents To Reuse

- Registry home: `architecture/registry/` and its README.
- Design-authority gate: `protected-paths.json`,
  `run_protected_path_authority_check`, CODEOWNERS validation,
  scope-bound `gc:design-authority-approval` markers, and the existing
  base-ref authority fallback rules in `tools/policy/checks.py`.
- Registry validation patterns: strict `schema_version`, unknown-key rejection,
  unique ids, repo-relative selector containment, exact-path or trailing-`/**`
  glob support, bounded enum values, and deterministic violation messages.
- Path and owner helpers: `selector_escapes_repo`,
  `selector_has_supported_glob`, `selector_matches_path`, and
  `parse_codeowners`.
- Backend enforcement: `ArchitectureTest` ArchUnit rules and the existing
  `api/ -> domain <- infrastructure` package contract. Registry-driven rules
  should consume or assert against the data instead of adding another
  hard-coded allowlist.
- Frontend and MCP enforcement: existing npm script surfaces, MCP ESLint, MCP
  node:test suites, and shared `lib.js` helper style. If a Node-side boundary
  linter is added, keep one parser for the registry and reuse it across
  frontend and MCP checks.
- GRC projection targets: `BoundaryModelService`, `BoundaryModelSnapshot`, the
  architecture-model aggregate, and ADR-058's derivation-backed engine.

## Cross-Cutting Layers

- Parser and schema validation: registry reads must fail closed on invalid
  JSON, unsupported `schema_version`, unknown fields, duplicate ids, missing
  edge endpoints, invalid lock levels, unbounded risk scores, invalid owner
  tokens, and unsafe selectors.
- Secret handling: registry data must contain repo-relative paths, ids,
  owners, risk metadata, and gate selectors only. It must not contain
  credentials, tokens, environment values, raw command output, raw source
  bodies, or copied issue-thread payloads.
- OS-level exposure: policy and lint tools should run in process over committed
  files, or invoke fixed argv command plans like the mutation runner. Do not
  construct shell commands from registry fields, and do not place secrets or
  user-supplied registry values in process argv or logs.
- Auth surface: no REST surface is required just to satisfy the registry data
  contract. If a backend API exposes registry facts later, it must stay under
  `/api/v1/**`, use `ApiPathMatrix`, route through services, and return errors
  through `GlobalExceptionHandler` and `ErrorResponse`.
- MCP and GitHub side effects: approval posting remains an MCP-server
  responsibility. Local policy tools may verify markers from supplied PR
  comments or CI-fetched comments, but they must not create GitHub comments.
- Logging and observability: log low-cardinality counts and ids such as
  registry version, boundary count, edge count, selected gate count, and
  violation count. Do not log full registry payloads or source snippets.
- Configuration: no new environment binding is needed for the registry. If an
  operator knob becomes necessary, use the existing `@ConfigurationProperties`
  pattern on the backend or explicit package-script configuration on Node
  tooling; do not add ad hoc env parsing.
- Error shape: backend errors use `GroundControlException` subclasses and
  `ErrorResponse`; policy errors use the existing `Violation` contract. Do not
  introduce a feature-local exception envelope.

## Extensibility Seams

Keep the registry parameterized where the next change is obvious:

- `schema_version` for the registry format.
- Stable `module.id`, `edge.id`, and `boundary.id` values, independent of
  labels or path selector changes.
- Edge metadata separate from module metadata, so an allowed dependency can
  carry its own rationale, lock level, or gate requirement when needed.
- Risk score model name or version, so scores are comparable after the scoring
  model changes.
- Gate-selection entries keyed by adapter kind, such as backend ArchUnit,
  frontend boundary lint, MCP import lint, policy authority, or mutation gate,
  not by free-form shell commands.
- Selector type, path list, and optional language or package-root fields, so
  later Java, TypeScript, MCP, workflow, and plugin boundaries can use the
  same registry artifact.
- Projection metadata that maps a CLD boundary to architecture-model or
  boundary-model identities without making those graph entities the registry's
  storage model.

Do not hardcode Ground Control's current package layout as the universal CLD
ontology. The registry can declare this repo's modules and edges; the schema
should support other repositories without renaming its core concepts.

## Gotchas And Anti-Patterns

- Do not create parallel backend, frontend, MCP, and policy schemas for the
  same registry data.
- Do not keep lock level authoritative in both the architecture registry and
  mutation registry.
- Do not treat a dependency edge as allowed because it compiles. If it is not
  in the registry, the relevant layer's checker should fail.
- Do not implement design-authority approval as prompt text, PR body prose, or
  unstructured review comments. The machine marker is the durable approval
  contract.
- Do not let a PR rewrite protected selectors, owners, or lock levels and use
  the rewritten head version to approve itself.
- Do not model CLD boundaries as GRC trust boundaries unless the registry
  explicitly declares that projection.
- Do not add a database aggregate, REST controller, or MCP write tool merely to
  read committed registry data.
- Do not add a generic architecture abstraction below real reuse pressure.
  Three direct checker adapters are preferable to a premature framework.

## Non-Goals

- No implementation of the registry schema or checkers in this note.
- No new ArchUnit, ESLint, MCP, CI, branch-protection, or policy behavior.
- No migration of existing mutation boundaries.
- No GRC architecture-model projection, threat enumeration, control mapping,
  or drift computation.
- No GitHub issue-thread posting or design-authority marker rendering changes.
- No runtime analysis, DAST, deployment inspection, or provider credential
  collection.

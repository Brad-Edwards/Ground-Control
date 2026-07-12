# Spec-Authority Separation Preflight

Issue #1294 adds the CLD protected-path and role-split gate. This note is
architecture preflight guidance only. It does not implement the policy check,
create a durable marker, change CODEOWNERS, or define an implementation plan.

## Architecture Boundary

ADR-087 owns the CLD role vocabulary: design authority, implementer, verifier,
contract package, oracle battery, protected path, and lock level. Issue #1294
should make those roles mechanically enforceable for this repository; it should
not create a second workflow taxonomy.

The gate is repo policy and workflow infrastructure, not a product runtime
aggregate. It should live in the existing policy/CI/GitHub-review surfaces and
reuse the existing durable issue-thread marker family. Do not add backend
controllers, persistence, Temporal state, frontend UI, or Ground Control domain
entities for the first version.

Protected paths are architecture and verification authority surfaces. At
minimum they include:

- `contracts/**`
- oracle-battery suites and corpus fixtures selected by the CLD registry
- `architecture/registry/**`
- `architecture/policies/**`
- `tools/policy/**`
- `bin/policy`
- CI and branch-protection wiring when it changes the gate itself
- workflow prompts/checklists that control CLD review behavior

The implementation-lane rule should be fail closed: a diff that changes
implementation files and protected paths must carry a design-authority approval
record, and a pure implementation diff must not modify protected paths at all.
The approval is a visible design event, not a comment convention hidden in PR
body prose.

## Incumbents To Reuse

- CLD method authority: ADR-087 and the reviewed research packet under
  `docs/research/contract-locked-development/`.
- Contract surface: ADR-082, `contracts/`, `make contracts-check`,
  `tools/contracts/check-breaking-changes.mjs`,
  `run_contract_surface_check`, `run_contract_invariant_enforcement_check`,
  and `run_authz_matrix_sync_check`.
- Mutation and battery-strength surface:
  `architecture/registry/mutation-boundaries.json`,
  `architecture/registry/mutation-baseline.md`,
  `tools/mutation/run_boundary_mutation.py`, and
  `run_mutation_gate_contract`.
- Repo policy style: small static `run_*` checks in `tools/policy/checks.py`
  with direct unit tests under `tools/tests/`.
- CI style: `.github/workflows/ci.yml` calls `bin/policy` and required contexts
  are mirrored in `.github/branch-protection-baseline.json`.
- Review routing: `.github/CODEOWNERS` for review requests, with the durable
  approval record as the authoritative audit signal.
- Durable workflow records: ADR-029 issue-thread markers rendered by the
  ADR-036 family of deterministic MCP helpers; privileged GitHub writes belong
  in `mcp/ground-control/lib.js`.
- Identity future seam: ADR-085 / GC-P024 later binds design authority to real
  roles and grants. Until then, the marker plus CODEOWNERS routing is the
  enforceable bridge.

## Cross-Cutting Layers

- **Diff classification:** changed paths must be resolved from the PR merge
  base with rename and deletion awareness. Use repo-relative paths only. Do
  not classify by filename suffix alone; protected-path selectors must be
  data-driven and easy to expand.
- **Policy validation:** protected-path config needs strict bounded shapes:
  stable ids, repo-contained selectors, explicit categories, and closed
  approval modes. Unknown keys, invalid globs, absolute paths, parent traversal,
  and empty selector lists should fail policy tests.
- **Approval-record validation:** an approval record must be schema-versioned,
  issue/PR scoped, actor-attributed, bounded, and resilient to malformed older
  comments. A random PR body checkbox, branch name, commit message, or local
  file must not satisfy the gate.
- **GitHub side effects:** if a new approval marker is posted, reuse the MCP
  server's argv-based GitHub posting, marker escaping, body-size caps, reserved
  marker rejection, and sensitive-content checks. Agents should not post the
  durable marker directly.
- **Secret handling:** protected-path and battery checks must not read local
  secret files, print environments, include tokens in argv, or upload raw
  command transcripts. Battery artifacts may include snippets and file paths;
  publish bounded reports and summaries only.
- **OS/process exposure:** policy and mutation tools should use fixed argv,
  no shell interpolation of changed paths, repo-contained selectors, timeouts,
  and deterministic exit codes. Caller-supplied path lists are inputs to
  validation, not command fragments.
- **Error shape:** CLI/policy failures should have stable violation codes,
  category, matched protected paths, and the missing approval condition. Do not
  dump full diffs, raw GitHub responses, stack traces, or tool transcripts into
  CI annotations.
- **Runtime error envelope:** no backend API change is expected. If scope later
  expands into REST, use Bean Validation, `GroundControlException` subclasses,
  `GlobalExceptionHandler`, and `ErrorResponse`; do not invent a CLD-specific
  envelope.
- **Auth and audit:** no product authorization change is required in this
  slice. If future tooling exposes approval actions through Ground Control,
  route through `ApiPathMatrix`, Spring Security, `ActorFilter` /
  `ActorHolder`, and audited domain services instead of caller-supplied actors.

## Gate Semantics

The protected-path gate should distinguish four cases:

- implementation-only diff: protected paths absent; normal gates apply;
- design-only diff: protected paths may change, with CODEOWNERS and ordinary
  policy checks; no implementation-lane bypass is implied;
- mixed diff with valid design-authority marker: allowed, auditable, and
  visibly exceptional;
- mixed diff without marker: CI failure.

Battery weakening is not a separate moral category. It is a protected-path
change with extra semantics. Deleted tests, skipped tests, lowered mutation
thresholds, lowered pinned corpus counts, removed invariant enforcement,
weakened negative suites, changed golden outputs, and narrowed mutant targets
should be treated as first-class design events. The first version can detect
the concrete weakening shapes present in the current repo, but the data model
should leave room for more battery types without rewriting the gate.

Hash-pinning or equivalent freezing should pin the approved battery surface,
not every implementation file. The stable unit is a contract/battery package:
contract artifacts, invariant inventory, oracle-battery files, corpus files,
registered mutation target selectors, thresholds, and baseline metadata. The
pin should be reproducible from committed content and include deletion/rename
signals.

## Extensibility

The extension seam belongs in registry/config data:

- protected-path category id;
- repo-relative selectors;
- whether mixed implementation changes are allowed with approval;
- expected CODEOWNERS owner group or review route;
- optional weakening detectors for that category;
- optional freeze hash inputs and version.

Adding a new CLD boundary, oracle battery, corpus type, or policy path should
add data and focused detector logic, not a new CI job or copied path classifier.
When GC-P024 lands, the same approval marker should be able to bind to real
role grants without changing the gate's protected-path model.

## Gotchas And Anti-Patterns

- Do not put the authority marker only in PR body prose. PR bodies are
  editable summaries; ADR-029 makes the issue/PR thread marker the durable
  workflow record.
- Do not treat CODEOWNERS approval alone as the machine-readable marker.
  CODEOWNERS routes review; the marker records the design event and the
  accepted protected-path impact.
- Do not allow an implementer to satisfy the marker by editing a file in the
  same protected path set the gate is supposed to protect.
- Do not conflate mutation threshold increases with weakening. Lowering
  thresholds, disabling tools, reducing targets, or deleting tests is the
  weakening path; strengthening should stay visible but should not be blocked
  as gaming.
- Do not hide policy selectors in CI YAML, Gradle, npm, or shell scripts.
  Policy data should be parseable by tests and visible to reviewers.
- Do not add prompt-only anti-gaming rules to skills without a mechanical
  counterpart. Review prompts may carry residual-channel checklists, but CI and
  policy remain the trust boundary.
- Do not add broad regexes that accidentally classify documentation-only
  changes as implementation changes. Keep implementation/protected categories
  explicit and test representative diffs.
- Do not rely on Testcontainers integration tests for coverage of policy
  tooling. `tools/tests` unit coverage is the right first surface.
- Do not weaken existing ADR-029, ADR-031, ADR-036, ADR-082, or ADR-087 gates
  to make the new approval path easier.

## Non-Goals

- No product UI for design-authority approval in this slice.
- No database-backed role model or `GateAuthorityGrant` implementation; ADR-085
  remains the future role-grant seam.
- No new backend REST API, controller, repository, service, or aggregate.
- No replacement for CODEOWNERS, branch protection, `make policy`, mutation
  testing, contract drift, breaking-change checks, or invariant enforcement.
- No full CLD workflow productization or `/design` lane; issue #1298 owns that.
- No held-out oracle service, external scanner, DAST/runtime check, or secret
  collection.

## Design Vocabulary That Applies

- **Pattern: Derivation-backed GRC engine** - not directly changed. The design
  should follow the same derivation/program-ADR pattern: facts in data,
  mechanical checks, no prompt-only enforcement.
- **Canonical helper: gh api argv-based posting in `mcp/ground-control/lib.js`**
  - durable approval markers must use the MCP/GitHub write boundary.
- **Boundary contract: api/ -> domain/ <- infrastructure/** - not expected to
  be touched. If backend scope appears, controllers route through services,
  domain stays web-free, and errors use the standard envelope.
- **Binding ADRs:** ADR-027 (`.ground-control.yaml` plus MCP repo-context
  contract), ADR-029 (issue-thread durable record), ADR-031 (structured review
  results with MCP-owned writes), ADR-036 (deterministic renderer/tool family),
  ADR-082 (contract surface), ADR-085 (future role grants), and ADR-087 (CLD
  separated powers and protected paths).
- **Anti-recommendations:** do not introduce abstractions below three real
  call sites; do not hand-roll error envelopes; prefer focused policy unit
  tests over heavy integration tests for this gate; do not add prompt text the
  tools cannot enforce; do not add obvious comments; do not perform privileged
  GitHub side effects outside the MCP boundary.

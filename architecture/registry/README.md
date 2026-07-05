# Architecture Registry

This directory holds committed architecture-registry data consumed by repo
policy and CI. It is intentionally small until the productized registry lands.

## Mutation Boundaries

`mutation-boundaries.json` declares CLD mutation-test contracts. Each enabled
boundary names:

- stable boundary id, name, lock level, and repo-relative path selectors;
- mutation tool adapter (`pitest` or `stryker`);
- minimum mutation score threshold and time budget;
- current baseline score with mutant counts and tool version;
- tool-specific scoped targets.

The CI mutation job uses `tools/mutation/run_boundary_mutation.py` to map
changed files to these boundaries. If no changed path matches an enabled
boundary, the job exits successfully with an explicit no-op message. If the
registry itself changes, every enabled boundary runs.

## Module Graph (GC-CLD-2)

`module-graph.json` is the single CLD structural contract: the module graph
that every boundary checker reads. It is the one source consumed by the backend
(ArchUnit), the frontend, and the MCP surfaces.

Each `modules[]` entry declares:

- stable `id`, human `name`, and `surface` (`backend` | `frontend` | `mcp`);
- `owner` (`@login`), `lock_level` (`locked` | `guarded` | `fluid`), and
  `risk_score` (0-5) - the structural contract data ADR-087 §3 requires as data,
  not prose;
- repo-relative `selectors[]` (exact paths, trailing `/**` directory globs, or a
  filename glob such as `gc-*.js`);
- `package` (backend only) - the ArchUnit package pattern (for example `..domain..`);
- `projection` - the architecture-model element mapping (`element_kind`,
  `stable_key`) so the registry can later project into the GC-GRC-005
  architecture-model graph without a parallel model.

`allowed_edges[]` declares the legal dependency edges between modules. Edges are
enforced as **negative space among registered modules**: a cross-module edge not
listed here fails CI in the layer that introduced it. Imports to unregistered
targets (framework/stdlib/`node_modules`, unmatched paths) are unconstrained.

Enforcement reads this one file from two places:

- **Backend** - `RegistryBoundaryArchitectureTest` (ArchUnit) asserts every
  backend module pair whose edge is absent has no dependency.
- **Frontend + MCP** - `tools/policy/checks.py::run_module_graph_boundary_check`
  scans ESM/TS imports and fails disallowed cross-module edges.

**Lock-level authority.** Lock levels for the boundaries declared here are
authoritative in this file only; `mutation-boundaries.json` keeps its own
`lock_level` for its own (disjoint) mutation boundary ids and is not a competing
source of truth. Because `module-graph.json` sits under the
design-authority-protected `architecture/registry/**` selector
(`protected-paths.json`), a lock-level or boundary change is a design-authority
event with machine-decidable exit criteria: when it ships alongside
implementation paths, the issue #1294 gate
(`run_protected_path_authority_check`) requires the
`gc:design-authority-approval` marker; a registry-only change is gated by the
CODEOWNERS design-authority review that branch protection enforces.
`run_module_graph_boundary_check` also asserts this protection stays wired
(`module-graph-not-design-authority-protected`), so the registry can never
silently fall out of the separation-of-powers gate.

### Known seed debt

The frontend seed encodes the current legal import graph, which includes a
`frontend-contexts` ↔ `frontend-components` cycle. It is recorded as an allowed
edge so CI is green on the existing tree; tightening it is a future
design-authority change to this registry, not a silent block.

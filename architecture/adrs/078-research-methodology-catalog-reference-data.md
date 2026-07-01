# ADR-078: Research Methodology Catalog as Backend Reference Data

## Status

Accepted

## Date

2026-06-30

## Context

`GC-RSCH-F005`/`GC-RSCH-F006` require a run to select a review methodology from a
catalog and to read that method's required primary methodology sources before the
`METHODOLOGY_REQUIREMENTS` artifact can complete. Issue #1005 shipped the
run-scoped selection/source-coverage state and the completion gate, but it left
the required-source set as caller-supplied data: `selectMethodology` accepted a
`requiredSourceRefs` list (plus a free-form `methodLabel`, `profileVersion`, and
`catalogVersion`) on the REST/MCP write boundary.

That shape has two problems. First, it lets the agent declare its own required
sources, so the coverage gate only checks that the agent read the sources it
chose to name - a self-graded exam. Second, the method profile then has no single
authoritative definition: the skill-side lookup
(`skills/lit-review/methodology/catalog.yaml`) names one set of sources per
method while every run could name another, and nothing keeps the two aligned.

ADR-077 deliberately scoped issue #1005 to run-scoped versioning and named "no
backend catalog loader, prompt store, or policy runner" as a non-goal, and the
issue-#1005 preflight note repeated "No backend catalog loader" as a non-goal for
that first slice. Those non-goals were correct for the first slice - they kept it
from sprawling into a generic behavior engine - but they also left the
required-source set ungrounded. This ADR closes that gap with the smallest
durable structure: backend-owned reference data, not an engine.

## Decision

### 1. The methodology catalog is backend-owned reference data

The canonical method profiles live in a versioned, validated-on-load resource at
`backend/src/main/resources/research/methodology-catalog.yaml`. Each entry is a
method profile: a stable `key`, a `label`, a profile `version`, and a non-empty
list of required primary sources (each a provider-neutral `ref` plus a bounded
`title`). The file carries a top-level `catalog_version`.

A `MethodologyCatalog` component loads the resource once at startup, validates it
fail-closed, and exposes the profiles as immutable in-memory reference data. The
catalog refuses to start the application when the resource is missing or
unparseable, when `catalog_version` is blank, when there are zero methods, on a
duplicate method key, on a method with a blank key/label or zero required
sources, or on a source with a blank `ref`. A blank or zero-source catalog is a
build error, never a vacuous coverage gate at runtime.

This is reference data, not the generic behavior engine ADR-077 rejected: it
parses one bounded schema and exposes typed lookups. It is not a
`ResearchBehaviorArtifact.execute(Map)`, a prompt store, or a plugin loader.

### 2. The selection gate derives the required-source set from the catalog

`selectMethodology` accepts only a `methodKey`. The service resolves the key
against the catalog (unknown key → `DomainValidationException`,
`research_run_methodology_unknown_method`) and derives the rest of the selection
from the resolved profile: `methodLabel`, `profileVersion`, and `catalogVersion`
are set from the profile, and each of the profile's required sources is
snapshotted as an immutable `required=true` source row in `ATTEMPTED` state. The
caller can no longer declare its own required sources, label, or versions.

The run-scoped snapshot keeps ADR-077's replayability property: a later catalog
edit does not rewrite an active or completed run's required-source set. Selecting
the same method again is idempotent while the snapshot still matches the catalog;
selecting a different method (or a profile whose required-source set has since
changed) supersedes the prior selection and re-snapshots.

### 3. The skill catalog is a mirror under a drift check

The skill-side lookup remains the file the phase-1 lit-review skill reads to
ground its method choice, but the backend resource is the source of truth. A
repo-native policy check (`run_methodology_catalog_drift` in
`tools/policy/checks.py`, run by `make policy`) fails when the two catalogs
disagree on the set of method keys or on the set of source identifiers per method
(skill `primary_sources[].zotero_key` == backend `required_sources[].ref`). The
two files cannot drift without failing CI.

### 4. The catalog is readable through the existing research surface

`GET /api/v1/research-runs/methodology/catalog` returns every method profile with
its required sources (global reference data; no project/run scope). The MCP
`gc_research_run` tool exposes it as the `list_methodology_catalog` action and the
`select_methodology` action now requires only `{id, method_key}`.

## Consequences

### Positive

- The coverage gate is grounded: required sources come from the authoritative
  catalog, not from caller-declared data, so a run cannot self-select a trivial
  required set.
- The method profile has one definition; the skill mirror is mechanically kept in
  sync.
- A malformed or vacuous catalog fails the build, never the runtime gate.
- The change rides the existing REST/MCP/policy layers; no new engine, store, or
  auth path is introduced.

### Negative

- Adding or changing a method now means editing the backend resource (and the
  skill mirror) and bumping the affected `version`/`catalog_version`, rather than
  passing different refs per call.
- The catalog is in-memory reference data loaded at startup; changing it requires
  a redeploy, not a runtime write.

### Risks

- If a future method needs run-specific required sources beyond the profile, that
  must be modeled explicitly rather than by reopening the caller-supplied path
  this ADR closes.

## Amends

- **ADR-077** - supersedes the "no backend catalog loader" clause of its
  Non-Goals for the methodology catalog specifically. The other ADR-077 non-goals
  (no generic workflow/behavior engine, no prompt marketplace, no raw-content
  storage) stand; this ADR adds a bounded, validated reference-data loader only.
- **architecture/notes/research-methodology-requirements-preflight.md** -
  supersedes its "No backend catalog loader" non-goal for the same reason.

## Related Requirements

- `GC-RSCH-F005` - methodology catalog selection and rejected alternatives.
- `GC-RSCH-F006` - required primary methodology sources read before method
  requirements.
- `GC-RSCH-N015` - maintainability / behavior versioning.

## Related Issues

- #1005 - Research methodology catalog and primary-source tracking.

## Related ADRs

- ADR-055 - Research Workflow Skills and Citation MCP.
- ADR-064 - Research Run Lifecycle and Stage Gating.
- ADR-072 - Research REST and MCP Tool Surface.
- ADR-073 - Research Extensibility and Adapter Boundary.
- ADR-077 - Research Behavior Versioning and Regression Tests (amended by this
  ADR).

# ADR-022: Content Pack Distribution Architecture

## Status

Accepted

## Date

2026-04-05

## Context

Ground Control now has a general plugin architecture requirement (GC-P005) and new draft requirements for requirements packs (GC-P014), control packs (GC-P015), and pack registry / trust semantics (GC-P016). Those requirements capture the need, but without an architectural decision the likely implementation path remains fragmented.

Today the system supports:

- Ad hoc content import paths such as StrictDoc and ReqIF
- Domain-specific seeding in limited cases such as methodology profiles
- First-class requirement and control entities, but no portable packaging model for distributing curated content between repositories or organizations

That is not enough for reusable repo bootstrapping. A portable content model needs more than "import some records":

- A pack must have stable identity, versioning, provenance, and compatibility semantics
- Pack installation must be idempotent and auditable
- Local projects must be able to tailor imported content without copying the entire upstream baseline
- Trust policy must distinguish curated packs from arbitrary external imports

If requirements packs and control packs are treated as one-off import formats, every content type will reinvent installation, upgrade, provenance, and override behavior separately.

## Decision

Adopt a three-layer content pack architecture for installable reusable content.

### 1. Shared Pack Substrate

Ground Control will treat installable packs as first-class distribution units with common metadata and lifecycle semantics:

- Pack identity and publisher
- Semantic version
- Compatibility constraints
- Dependency declarations
- Provenance metadata
- Integrity material such as checksums or signatures
- Auditable installation records

This shared substrate is the architectural realization of GC-P016 and is the foundation for any installable content pack type.

### 2. Typed Content Packs

Content packs remain type-specific above the shared substrate rather than being flattened into one generic blob format.

**Requirements packs** package:

- documents
- sections
- requirements
- requirement relations
- pack metadata

This is the architectural realization of GC-P014.

**Control packs** package:

- control definitions
- control metadata
- framework mappings
- implementation guidance
- expected evidence patterns
- pack metadata

This is the architectural realization of GC-P015 and extends the GC-I001 control catalog model.

Separate pack types are intentional. Requirements and controls have different structure, lifecycle, and downstream analysis semantics even though they share installation and trust concerns.

### 3. Install / Upgrade / Tailor Model

Pack installation is not modeled as a raw import. Instead, Ground Control will use pack-aware operations with these semantics:

- **Idempotent install**: reapplying the same pack version does not duplicate content
- **Version-aware upgrade**: moving between pack versions preserves source identity and install history
- **Provenance preservation**: installed content retains source pack and version metadata
- **Local tailoring**: projects may extend or override installed content without forking the originating pack wholesale

Local tailoring is constrained to project-local overlays, not mutation of the upstream pack definition. This preserves upgradeability and makes divergence explicit.

### 4. Plugin Boundary

The plugin architecture (GC-P005) provides the extension boundary for new pack handlers, registry backends, validators, and install-time policy hooks. Pack distribution is therefore built on the plugin substrate, but content packs are a higher-level abstraction than generic plugins.

In other words:

- Plugins extend the platform's executable behavior
- Content packs distribute reusable domain content through the platform

These are related but not interchangeable concerns.

### 5. Control Pack Guardrails

GC-P015 must reuse the existing control-domain model where the semantics already match.

- `Control` remains the authoritative project-local runtime aggregate for installed control content. Control pack installation should materialize normal control records rather than inventing a parallel "installed control" API or duplicate control DTO/entity hierarchy.
- Existing `Control` fields cover the core control definition surface already implemented under GC-I001: title, description, objective, control function, owner, implementation scope, methodology factors, effectiveness, category, and source. Do not duplicate those semantics in pack-specific JSON blobs when the existing control model is sufficient.
- Framework mappings that are naturally link-shaped should reuse `ControlLink` with `MAPS_TO` and the existing internal/external target rules. Do not introduce a second unrelated mapping mechanism for cases already covered by the link model.
- Expected evidence patterns are templates or expectations, not observed evidence. They must not be persisted as real `EVIDENCED_BY` links, observations, findings, or other runtime evidence records before concrete evidence exists.
- Plugin registrations are executable extension metadata. Installed control packs are content-installation state and provenance, not `RegisteredPlugin` rows. Pack handlers, validators, registry backends, and policy hooks may be plugins; pack instances are not.
- Generic import audit records are not a substitute for pack lifecycle state. Version-aware install, upgrade, deprecation, and provenance need pack-specific durable state rather than overloading ad hoc import records.
- Local tailoring must stay explicit and minimal. Tailoring is a project-local overlay on installed content, not mutation of the upstream pack definition and not a wholesale cloned fork of the originating catalog by default.
- Pack-driven changes that create or update controls or control links must stay inside the existing project-scoping, Envers audit, exception-mapping, logging, and graph-projection conventions already used elsewhere in the platform.

### 6. Registry / Resolution / Trust Guardrails

GC-P016 must keep discovery, policy, and application as separate concerns even when they participate in one install workflow.

- **Registry catalog metadata** describes candidate artifacts and publishers. It is not the same thing as installed pack state, dynamic plugin registration state, or an install audit record.
- **Resolution** selects an exact artifact version and dependency closure before any mutation occurs. Compatibility checks, dependency solving, and semantic-version comparison belong in one shared resolver path, not in each pack handler or controller.
- **Trust evaluation** runs after resolution and before any content materialization or dynamic plugin registration. The policy decision must be computed server-side from resolved provenance, integrity material, and configured policy; clients must not be allowed to assert their own trust outcome.
- **Auditable install records** are first-class durable state. Structured logs and Envers history are complementary, but they are not a substitute for a record that captures the requested identity, resolved source, resolved version, verified digest or signature material, policy outcome, and resulting install or rejection outcome.
- **First-class trust and resolution fields** must not be hidden in generic metadata blobs when they drive policy or compatibility decisions. Extensible maps remain appropriate for vendor-specific annotations, but identity, dependency, compatibility, provenance, and integrity semantics need one shared contract across pack types.
- **Shared orchestration, typed materialization** remains the boundary. The shared registry and trust substrate may call type-specific installers such as control-pack materialization, but it must not collapse pack-specific aggregates into one opaque generic pack record.

## Consequences

**Positive:**

- Requirements packs and control packs share one trust and installation model instead of duplicating infrastructure
- Portable curated content becomes a first-class capability rather than a side effect of import formats
- Local repo or organization customization remains compatible with upstream pack evolution
- Provenance and auditability become intrinsic to reusable content distribution

**Negative:**

- Adds architectural surface area before the first concrete pack type is implemented
- Requires explicit decisions about override precedence, conflict handling, and upgrade reconciliation

**Risks:**

- If local overrides are too unconstrained, upgrades will become unsafe and pack lineage will blur
- If the shared substrate is over-generalized, pack handlers may collapse into an opaque import mechanism with weak domain semantics
- Trust policy that is too weak turns packs into a supply chain liability; trust policy that is too rigid makes curated content difficult to adopt

## Related Requirements

- GC-P005 Plugin Architecture
- GC-P014 Requirements Pack Distribution and Installation
- GC-P015 Control Pack Distribution and Installation
- GC-P016 Pack Registry, Resolution, and Trust Model
- GC-I001 Control Catalog

# ADR-023: Plugin Architecture

## Status

Accepted

## Date

2026-04-08

## Context

Ground Control has three mature but ad-hoc pluggable adapter patterns:

- **VerifierAdapter** (GC-F005): port interface for verification tools, following ADR-014
- **EmbeddingProvider**: port interface with conditional bean activation for embedding backends
- **GraphProjectionContributor**: registry-collected interface for graph node/edge contribution

Each pattern independently reinvents discovery, registration, and lifecycle semantics. ADR-022 identifies the need for a formal extension boundary to support content pack distribution (GC-P014, GC-P015, GC-P016), specifically for pack handlers, registry backends, validators, and install-time policy hooks.

Without a shared plugin substrate, each new extension type will continue to reinvent infrastructure, and there will be no unified way to discover, inspect, or manage extensions at runtime.

## Decision

Introduce a general-purpose plugin architecture with dual-source discovery:

### 1. Plugin Interface

A `Plugin` interface in the domain layer defines the extension contract:

- `descriptor()` returns `PluginDescriptor` with metadata (name, version, description, type, capabilities, metadata map)
- Default no-op lifecycle methods: `initialize()`, `start()`, `stop()`
- `isAvailable()` reports readiness

Lifecycle methods are default no-ops because most plugins are stateless. Plugins that need lifecycle management (for example, connection pool initialization) override specific methods.

### 2. Typed Plugin Categories

`PluginType` enum defines the categories established by ADR-022 plus existing adapter categories:

- `PACK_HANDLER`, `REGISTRY_BACKEND`, `VALIDATOR`, `POLICY_HOOK` (ADR-022)
- `VERIFIER`, `EMBEDDING_PROVIDER`, `GRAPH_CONTRIBUTOR` (existing patterns)
- `CUSTOM` (future unforeseen extensions)

### 3. Dual-Source Plugin Registry

The `PluginRegistry` service manages two sources of plugins:

**Built-in plugins** are classpath-discovered Spring beans implementing `Plugin`. They are collected via `List<Plugin>` constructor injection and initialized at application startup via `@PostConstruct`. This follows the established `GraphProjectionRegistryService` pattern.

**Dynamic plugins** are registered at runtime (for example, when a content pack is installed) and persisted in a `registered_plugin` database table. They survive application restarts because their registrations are stored in PostgreSQL. The registry loads them from the database alongside classpath plugins.

`PluginRegistry` is a runtime inventory and metadata persistence service, not a package registry, dependency solver, or trust-policy engine. Any future plugin installation workflow introduced for GC-P016 must resolve artifacts, evaluate compatibility and trust policy, and persist an auditable policy decision before it invokes dynamic registration.

### 4. Existing Adapters Not Retrofitted

The three existing adapter interfaces (`VerifierAdapter`, `EmbeddingProvider`, `GraphProjectionContributor`) are not forced to implement `Plugin`. They serve domain-specific contracts with different method signatures. Conflating them with the generic plugin interface would add coupling without behavioral benefit.

Future extension points built on the plugin substrate will implement `Plugin` directly. Existing adapters may optionally adopt it later if a unifying need arises.

## Consequences

**Positive:**

- Unified extension boundary for content pack distribution and future extension types
- Runtime introspection of all registered plugins via REST API and MCP tools
- Dynamic plugin registration survives restarts without redeployment
- Consistent lifecycle management across all plugin types

**Negative:**

- Adds a database table and migration for dynamic plugin persistence
- Two sources of truth (classpath + database) require careful duplicate-name detection

**Risks:**

- If built-in and dynamic plugins are not properly isolated, name collisions could cause startup failures
- Dynamic plugin registration without code execution is metadata-only; actual behavior execution requires compiled code on the classpath or a future dynamic loading mechanism

## Related Requirements

- GC-P005 Plugin Architecture
- GC-F005 Pluggable Verifier Adapter Interface
- GC-P014 Requirements Pack Distribution and Installation
- GC-P015 Control Pack Distribution and Installation
- GC-P016 Pack Registry, Resolution, and Trust Model

## Related ADRs

- ADR-014 Pluggable Verification Architecture
- ADR-022 Content Pack Distribution Architecture

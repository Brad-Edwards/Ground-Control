# ADR-016: Project Scoping

## Status

Accepted

## Date

2026-03-14

## Context

Ground Control currently operates as a single-namespace system - all requirements, relations, traceability links, and analysis results exist in one flat pool. This works while the only managed project is Ground Control itself (dogfooding), but the system's value proposition requires managing requirements for multiple independent software projects from a single instance.

Without project scoping:

1. **Namespace collision** - Two projects could define `REQ-001` with different meanings. UIDs are currently globally unique, but this forces artificial prefixing conventions (for example, `GC-A001` vs `ACME-A001`) with no system enforcement.
2. **Polluted analysis** - Cycle detection, orphan analysis, coverage gaps, and cross-wave validation return results spanning all projects. An orphan in Project A is not relevant when reviewing Project B.
3. **Traceability noise** - Traceability matrices mix artifacts from unrelated codebases, making audit views unusable for compliance reviews scoped to a single product.
4. **MCP tool confusion** - AI agents working on one project's requirements see and can modify requirements from all projects. No guardrails against cross-project contamination.

The requirement GC-A013 captures the functional need. This ADR records the architectural decisions for implementing it.

### Constraints

- Backward compatible: existing requirements (Ground Control's own) must migrate cleanly into a default project
- No multi-tenancy (that's GC-P006, wave 5) - project scoping is data partitioning within a single tenant, not access control between tenants
- MCP tools must support project context without breaking existing tool signatures

## Decision

### 1. Project Entity

Introduce a `Project` JPA entity in a new domain package `domain/projects/`:

```
domain/projects/
    model/
        Project.java          # @Entity + JML contracts
    service/
        ProjectService.java
    repository/
        ProjectRepository.java
```

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | UUID | PK, generated | |
| identifier | String(50) | Unique, not null | Short slug: `ground-control`, `acme-api` |
| name | String(255) | Not null | Human-readable: "Ground Control" |
| description | TEXT | Default "" | |
| createdAt | Instant | Not null, `@PrePersist` | |
| updatedAt | Instant | Not null, `@PreUpdate` | |

`identifier` is the API-facing key (used in URLs, MCP tool parameters, filtering). `name` is the display label.

### 2. Requirement Belongs to Project

Add a mandatory `@ManyToOne` from `Requirement` to `Project`. Every requirement belongs to exactly one project. The foreign key is `project_id`, not null.

A Flyway migration will:
1. Create the `project` table
2. Insert a default project (`identifier: ground-control`, `name: Ground Control`)
3. Add `project_id` column to `requirement` with the default project's UUID
4. Add a not-null constraint on `project_id`

### 3. Same-Project Constraint on Relations

`RequirementRelation` already has `source` and `target` foreign keys. Add a service-layer validation: both source and target must belong to the same project. This prevents nonsensical cross-project dependencies.

**Not** enforced at the database level (would require a complex check constraint or trigger). Enforced in `RequirementService.createRelation()` with a `DomainValidationException`.

### 4. Project-Scoped Operations

All listing, filtering, analysis, and traceability operations default to single-project scope:

- **REST API**: Add `project` query parameter (accepts project identifier) to list/filter/analysis endpoints. Required for multi-project instances, optional when only one project exists.
- **MCP tools**: Add optional `project` parameter to tools that list or analyze requirements. When omitted, use a configured default project or return an error if multiple projects exist.
- **Service layer**: `RequirementService.list()`, `AnalysisService.*()`, and `TraceabilityService.*()` accept a project filter. UID uniqueness is scoped to project (two projects can have `REQ-001`).

### 5. UID Uniqueness Scope Change

Currently, `uid` has a global unique constraint. Change to a composite unique constraint on `(project_id, uid)`. This allows different projects to use their own UID schemes independently.

## Consequences

### Positive

- Ground Control becomes usable for managing multiple real projects, not just itself
- Analysis results are meaningful - scoped to the project under review
- MCP agents get project context, reducing the risk of cross-project mistakes
- Clean migration path: existing data moves to a default project with no breaking changes
- UID schemes are project-local - no need for global prefixing conventions

### Negative

- Every query path gains a project filter - more parameters to thread through the stack
- MCP tools need a project context mechanism (parameter or configuration), adding UX friction for agents
- Testing surface increases - all operations need project-scoped and cross-project-rejection test cases

### Risks

| Risk | Mitigation |
|------|-----------|
| Performance: adding project_id to every query | Index on (project_id, status) and (project_id, uid). Project table is tiny. |
| MCP tool ergonomics: requiring project on every call is verbose | Support a default project configuration. When only one project exists, it's implicit. |
| Migration: existing data without project_id | Flyway migration inserts default project first, then backfills all existing rows. Single transaction. |

## Related ADRs

- [ADR-011](011-requirements-data-model.md) - Requirements data model (project_id extends this model)
- [ADR-013](013-java-spring-boot-rewrite.md) - Java/Spring Boot backend
- [ADR-017](017-interactive-web-application.md) - Web UI (will consume project-scoped API)

## Related Requirements

- GC-A013 (Project Scoping)

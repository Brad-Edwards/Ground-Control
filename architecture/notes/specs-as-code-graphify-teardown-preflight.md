# Specs-as-Code and Context-Graph Teardown Preflight

Issue: #1500
Requirement: none

This note sets boundaries for replacing Ground Control's context-graph machinery with repository-owned specifications and a disposable Graphify index. It is not an implementation plan.

## Decision Boundaries

Git is the authoritative store for requirements, use cases, and ADRs. A requirement file must carry the existing project-local UID and a small, versioned frontmatter contract (`id`, `status`, and `wave` where applicable). The initial lint must validate that contract deterministically; Graphify's EXTRACTED, INFERRED, and AMBIGUOUS edges are comprehension aids, never coverage or release proof. The requirement contract must not be silently repurposed as a use-case schema: this repository currently has no first-class use-case aggregate or file convention, so the teardown must first identify the existing use-case artifacts and establish one minimal, separately versioned file convention only if those artifacts need migration.

Graphify is an optional, rebuildable local index over code and those files. It must have no write authority over specifications, no runtime dependency for the product or workflow, no credentials in repository config, and a failure mode that leaves ordinary repository navigation and CI usable. Its hook setup must be opt-in and must not overwrite user-managed Git hooks; reuse the managed-hook safety rules if this repository chooses to install it at all.

The one-time exporter is the migration boundary, not a new long-lived API or cross-repository deployment mechanism. It accepts one Ground Control project and a maintainer-controlled staging output directory, emits one safe folder per requirement, and preserves the UID. The workflow must not select an output root in, locate, clone, open, or write another repository; a maintainer copies the staged folders out of band. Reuse `AnalysisService.getRequirementsExportData(...)`, `RequirementExportRecord`, and `RequirementsExportData` (or extend that read-only serialization path) before considering SQL. The current model has no first-class use-case aggregate or requirement-to-use-case database linkage: do not invent one for this export. If a real linked use-case record is found in a later authorized migration, add it to this same read-only serialization boundary. Do not make the folder layout depend on requirement titles; validate/normalize every path component and reject output that escapes the supplied root.

The old relational requirements database remains readable only for the one-time export until a separately authorized data-retention decision is made. Do not conflate deleting the graph projection with deleting retained domain aggregates, nor conflate specs-as-code with importing Graphify edges into a new persistence model.

## Teardown Scope and Ordering Guardrails

Inventory and remove the complete graph slice as one coherent change: domain graph contracts/contributors, AGE infrastructure and configuration, graph REST routes and DTOs, MCP registrations/wrappers, frontend route/components, tests, OpenAPI/API documentation, Flyway forward migrations for active graph artifacts, and operational references (compose image, restore/backup and deployment assertions). A forward-only migration must remove live database objects safely; never rewrite applied Flyway history.

The graph is not the only consumer of Envers. Deleting its contributors must not delete audit tables or revision infrastructure used by retained aggregates. Likewise, workflow telemetry and review gates must be assessed independently: retain only a gate with observable evidence that it rejects output and causes a real repair. If retained, it must stop requiring requirement status changes, traceability reconciliation, graph reads, or graph-derived audit projection.

Before attributing latency savings to this deletion, measure the current path at the MCP boundary, backend request, and database call. AGE is disabled by default, so an end-to-end MCP/Spring/Postgres cost may dominate. The pilot and cost map decide whether Graphify is adopted and which workflow calls are removed; neither result licenses rebuilding a bespoke graph-query service.

## Cross-Cutting Contracts

- Backend boundaries remain `api/ -> domain/ <- infrastructure`. Any temporary exporter endpoint/runner routes through a project-scoped service and repository; it must not expose infrastructure/JDBC details through the API.
- Existing external paths continue through `ApiPathMatrix`, bearer/session security, `IpAllowlistFilter`, `ActorFilter`/MDC, Bean Validation, `GlobalExceptionHandler`, and `ErrorResponse`. New export access must be explicitly classified admin versus project-scoped; it must not accidentally inherit a permissive route.
- Backend configuration belongs in validated `@ConfigurationProperties` and env templates/schema. Removing `groundcontrol.age` also removes its stale environment/config documentation; no ad-hoc `@Value` or unvalidated Graphify token/property is permitted.
- MCP input schemas remain Zod-shaped and API write contracts remain in sync. Do not retain an MCP tool that recreates direct database access, direct Graphify querying, or a duplicate requirement schema. Privileged GitHub writes stay in the MCP server; this issue adds no cross-repository writes.
- Use the existing `GroundControlException` subclasses and structured SLF4J logging. Export diagnostics may log project identity, counts, and output root; never requirement bodies, bearer tokens, database URLs, or untrusted paths. Errors must not disclose filesystem layout or SQL/constraint detail.
- The CLI's failure path is also an observability boundary: do not interpolate raw exception messages into logs or a process-facing error. Map expected input/path failures to a stable diagnostic, retain the causal exception for protected operator logs only where the logging policy permits it, and keep raw SQL/filesystem detail out of both `ErrorResponse` and normal export output.
- Test deletion and replacement across all owning contracts: `@WebMvcTest` slices for changed REST shapes, domain tests for exporter serialization and path containment, MCP Zod/contract tests, frontend tests, ArchUnit, Flyway migration smoke, compose/deploy/restore assertions, and `make policy`.

## Extensibility

The only intentional seam is a project identifier plus explicit output root for the throwaway exporter. It permits another project export without a repository-mapping registry. The specs convention must be documented once and linted once, so future fields are a versioned frontmatter evolution rather than parser-specific optional keys. Graphify integration stays an adapter at the developer-tooling boundary: a future indexer can replace it without migrating specifications, application data, or workflow state.

## Anti-Patterns and Non-Goals

- Do not add a replacement graph database, graph REST/MCP surface, inferred edge persistence, bespoke search service, or duplicate coverage system.
- Do not treat Graphify traversal as deterministic traceability, authorization, audit evidence, or a reason to keep Envers graph projection.
- Do not duplicate requirement/use-case frontmatter in DB entities, OpenAPI DTOs, MCP schemas, and a second parser. During migration the database is the exporter input and Git is the future record; choose one writer at a time.
- Do not remove retained controls, control tests, evidence, findings, assets, risks, threat models, or their mappings merely because they projected into the graph. Do not expand this run into another repository or automated distribution of exporter output.
- Do not delete unrelated audit/security/telemetry behavior solely because it shares an Envers revision, workflow-run, or MCP module with graph code.
- Do not add unenforceable prompt instructions to `skills/implement`; delete or change the tool/policy contract that enforces the obsolete ceremony.

# ADR-094: Graphify as the code + docs comprehension index

- **Status:** Accepted
- **Date:** 2026-08-02
- **Issue:** #1500
- **Relates to:** ADR-093 (Requirements as Specs-as-Code)
- **Supersedes:** none (it replaces the context-graph comprehension role; the graph teardown that supersedes ADR-005/032/062/070/084 is executed in the same issue)

## Context

ADR-093 makes Git the record for requirements (specs-as-code), joining ADRs and code already in the
repo. The context-graph machinery that used to be sold as "give agents context" is being torn out
(issue #1500) because agents never traversed it for comprehension. But the underlying goal remains:
a coding agent should be able to reason from use case to implementation to test without linear
grepping. That comprehension surface has to come from somewhere.

## Decision

Adopt [Graphify](https://github.com/Graphify-Labs/graphify) as a **disposable, opt-in
developer-tooling index** over the source tree and the specs-as-code files, not as product
machinery.

1. **Cache, never the record.** Git is authoritative. Graphify's `graph.json` is regenerated on
   demand and git-ignored (`graphify-out/`). Dropping Graphify loses only a convenience index.
2. **Deterministic by default.** Ground Control builds it `--code-only` (tree-sitter AST, no LLM,
   no credentials). Inferred edges are a comprehension aid, never coverage/authorization/audit/
   release proof; the frontmatter policy lint remains the deterministic guarantee.
3. **No runtime or CI dependency.** Graphify is never imported by the product, never installed by
   CI, and holds no credentials in repository config. Its git-hook rebuild is opt-in and installs
   local hooks only, never overwriting managed hooks.
4. **Agent direction over machinery.** Agents are pointed at Graphify (query/path/explain, or its
   MCP server) for codebase comprehension via `docs/GRAPHIFY.md` and `CLAUDE.md`, rather than a
   bespoke graph-query service being rebuilt in Ground Control.

## Consequences

**Positive**

- Restores use-case → implementation → test comprehension for agents without any product-side graph
  database, MCP surface, or Postgres/AGE dependency.
- A future indexer can replace Graphify without migrating any data; the record is the Git files.

**Negative / risks**

- Graphify is a young external tool. Treating it strictly as a rebuildable projection (never the
  record, never a runtime dependency) neutralizes the supply chain and durability risk.
- Inferred edges can mislead if trusted as proof; the guardrail is that they are comprehension-only
  and the deterministic checks live in `make policy`, not in Graphify.

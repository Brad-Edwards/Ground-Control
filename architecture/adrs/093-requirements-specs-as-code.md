# ADR-093: Requirements as specs-as-code

- **Status:** Accepted
- **Date:** 2026-08-02
- **Issue:** #1500
- **Relates to:** ADR-011 (Requirements Data Model), ADR-084 (Context-Graph Concept Authority)
- **Supersedes:** none (the context-graph teardown that will supersede ADR-084 is a follow-on PR under #1500)

## Context

Ground Control's stated purpose is to give coding agents full context from use case to
implementation so they drift less and defect less. In practice the context-graph machinery
became process for its own sake: the agents (its intended consumers) do not traverse it to
understand the codebase, they only occasionally look a requirement up. Meanwhile the workflow
forces every run through graph/traceability ceremony. The requirement artifacts themselves are
wanted; the machinery around them is not.

Requirements live in the relational store and are surfaced through a graph projection and an MCP
tool surface. ADRs already live as files in `architecture/adrs/`. Nothing structural stops
requirements from living beside the code they govern in the same way, where they diff in the same
pull request and are readable by any tool, including a future code-plus-docs comprehension index
(Graphify), without a database round-trip.

This is the first, additive increment of #1500. The full teardown of the graph/AGE vertical slice
is a separate coherent change and a separate PR.

## Decision

1. **Git becomes the record for requirements.** Each requirement is a version-controlled file at
   `docs/requirements/<UID>/requirement.md`, one folder per requirement, carrying a small,
   versioned YAML frontmatter contract (`id`, `title`, `status`, `type`, `priority`, optional
   `wave`, timestamps). The contract is documented once in `docs/requirements/README.md`.

2. **The contract is enforced deterministically.** A repo-native policy check
   (`run_requirement_specs_frontmatter_check`, `make policy`) validates the frontmatter. Comprehension
   indexes such as Graphify produce inferred edges that are useful for navigation but are never
   coverage, authorization, audit, or release proof; the lint is the deterministic source for any
   retained guarantee.

3. **A one-time exporter is the migration boundary, not a new API.** It reuses the existing
   read/serialization path (`AnalysisService.getRequirementsExportData`, `RequirementExportRecord`,
   `RequirementsExportData`) rather than issuing its own SQL, accepts one project and one explicit
   output root, writes one path-safe folder per requirement, and never locates, clones, or writes
   another repository. Distribution of another project's requirements into that project's repo is a
   manual maintainer copy.

4. **The relational store stays authoritative during migration.** The database remains the export
   input and the running system's record until a separately authorized data-retention decision is
   made. We do not, in this ADR, delete retained aggregates, remove the graph, or import inferred
   edges into a new persistence model. There is one writer at a time.

## Consequences

**Positive**

- Requirements and the code they govern diff together, which directly attacks drift.
- Agents read requirements as files natively, with no MCP/graph round-trip to obtain context.
- The specs are a durable, tool-neutral substrate; a future indexer can be adopted or dropped
  without migrating any data.

**Negative / risks**

- The initial migration commits ~450 generated files. They are generated output validated by the
  lint, not hand-authored.
- During migration there are two representations (database and files). This is bounded by keeping
  the database the sole writer until the retention decision; the files are the forward record.
- The exporter's frontmatter output and the policy lint must evolve together; the versioned
  contract and a single documented schema keep them in step.

## Amendments

**2026-09-03 (issue #1541, delivery and completion authority).** A requirement
status or traceability change becomes authoritative only by being reviewed and merged in
the delivery pull request. The workflow must not edit and commit requirement files after
that merge. Pre-merge readiness may describe the pull-request head as proposed state;
post-merge completion must re-read every in-scope requirement from the linked pull
request's immutable target-branch merge revision and verify the exact UID path,
frontmatter id, expected lifecycle status, and required traceability before publishing a
final report or closing the issue. Active checkout content and caller-supplied report
status are not authority. The canonical reader remains
`mcp/ground-control/lib/requirement-files.js`, parameterized by immutable revision rather
than forked into a second parser. Requirement-free runs perform no requirement assertion.

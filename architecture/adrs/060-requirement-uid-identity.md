# ADR-060: Requirement UID identity

## Status

accepted

## Date

2026-06-23

## Context

Three related gaps in requirement UID handling were identified across issues #532,
#1052, and #574.

**Server-side UID allocation (#532).** Callers had to generate UIDs client-side,
which allowed races when multiple agents ran against the same project. The MCP
tool `gc_requirement create` needed a way to let the server allocate the next
available UID without the caller knowing the current high-water mark.

**Project-scoped reverse lookup (#1052).** `TraceabilityService.findByArtifact`
was project-unscoped. `GITHUB_ISSUE` links store only the raw issue number as the
artifact identifier, so the same number can appear in multiple projects. A
project-blind lookup returned or flagged links from unrelated projects, causing
false positives in the orphaned-issue-link reconciliation audit.

**Legible uniqueness (#574).** Several entities carry DB-enforced uniqueness
expressed as functional or partial indexes that JPA `@UniqueConstraint` cannot
represent. This left the uniqueness contract invisible at the entity level.

## Decision

**Server-side UID allocation (#532).** `gc_requirement create` now accepts
`uid_prefix` as an alternative to `uid`; exactly one must be supplied. When
`uid_prefix` is present, `RequirementUidAllocator` validates the prefix against
`^[A-Z][A-Z0-9]*(-[A-Z0-9]+)*$`, acquires `pg_advisory_xact_lock` for the
`(project, prefix)` pair, and reads the current maximum numeric suffix via the
`findMaxUidSuffix` native SQL query (archived rows are included so their UIDs
remain permanently reserved). The allocator increments the suffix and writes the
new UID atomically within the transaction.

**Project-scoped reverse lookup (#1052).** `TraceabilityService.findByArtifact`
accepts an optional `projectId` and routes to a JPQL query scoped to that project
when present, falling back to the existing project-unscoped query for callers that
cannot supply one. The `GET /api/v1/requirements/traceability/by-artifact`
endpoint accepts an optional `?project=` query parameter. The MCP tool
`gc_get_traceability_by_artifact` and the `checkOrphanedIssueLinks` reconciliation
helper both forward the project when available. Cross-project issue-number
collisions no longer return or flag another project's links.

**Legible uniqueness (#574).** Entities whose DB-enforced uniqueness is
inexpressible as JPA `@UniqueConstraint` (functional indexes such as
`LOWER(uid)`, partial indexes with `WHERE archivedAt IS NULL`) now carry a
documenting Javadoc paragraph naming the migration that owns the constraint.
Affected entities: `Requirement` (functional `LOWER()` index on `uid`),
`SectionContent` and `ControlLink` (partial `WHERE` indexes). The convention
applies only to genuinely inexpressible cases; `@UniqueConstraint` remains the
primary documentation where the index is expressible.

## Consequences

- Agents no longer need to compute or guess the next free UID. Supplying
  `uid_prefix` is the recommended pattern; callers that know the exact UID they
  want may still supply `uid` directly.
- Archived requirements permanently reserve their UID suffixes; recycling a
  previously used number is not possible within a prefix.
- The `?project=` parameter on the reverse-lookup endpoint is optional for
  backward compatibility, but callers that deal with `GITHUB_ISSUE` artifacts
  must supply it to get correct results when the same issue number appears in
  multiple projects.
- The Javadoc convention applies to new entities with inexpressible constraints
  going forward; retrofitting every existing entity is out of scope for this ADR.

Non-goals: owner/repo#number re-encoding in UIDs, a global UID registry, or
GRC-wide UID allocation. Those remain future work.

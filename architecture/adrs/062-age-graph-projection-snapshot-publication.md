# ADR-062: AGE Graph Projection Snapshot Publication

## Status

accepted

## Date

2026-06-25

## Context

Issue #252 identifies a reliability gap in the Apache AGE graph read model:
`POST /api/v1/admin/graph/materialize` rebuilds the live graph by deleting every
node and recreating nodes and edges row by row. Graph readers can observe an empty
or partially rebuilt projection, and a failed refresh can leave the live graph in a
corrupt intermediate state.

ADR-005 already chooses Apache AGE as the graph store. ADR-032 already makes the
AGE adapter the SQL/Cypher construction boundary. Neither ADR defines ownership of
the projection lifecycle or how a rebuilt graph becomes visible to readers.

The repository already has the important schema boundary: relational aggregates are
the source of truth, `GraphProjectionContributor` implementations emit the canonical
domain projection, and `AgeGraphService` is only an infrastructure adapter for AGE.
The fix must preserve that boundary instead of making requirement writes, control
writes, or other aggregate services directly maintain AGE state.

## Decision

Ground Control uses a versioned snapshot publication model for the AGE graph
projection.

Materialization builds a new, inactive graph snapshot from
`GraphProjectionRegistryService`. The active snapshot that readers query is never
destructively rebuilt in place. After a refresh has successfully written and
validated the new snapshot, publication is a single database transaction that
advances an active-snapshot pointer. A failed refresh leaves the previous active
snapshot untouched. Old snapshots are cleaned up only after they are no longer
active; cleanup is best effort and not part of the reader path.

Two lifecycle edges are explicit. First, an upgrade/bootstrap path: the snapshot
pointer table starts empty, but an existing deployment can already have the
configured base graph populated by the pre-this-ADR in-place materializer, so reads
fall back to the configured base graph only while no snapshot row exists. Once the
first snapshot is published the pointer always wins and the base graph is never read
again. Second, cleanup is bounded by both a retained-count and a
minimum-age grace period: a superseded snapshot is dropped only once it is beyond the
retained count AND was retired more than the grace ago. The count bound alone is
insufficient because a reader can resolve a snapshot and then rapid publications can
push it beyond the count and drop it mid-read; repeatable-read protects the metadata
lookup but not the AGE graph object's lifetime. The grace is measured from retirement
(the publication instant of the successor snapshot), not from the snapshot's own
publication, because a snapshot can be active for a long time and then be superseded
while a reader is using it. The grace must exceed the maximum read duration, which is
itself bounded by the graph traversal and projection-size caps.

The relational model remains the source of truth. Graph nodes and edges continue to
come from the existing `GraphProjection`, `GraphNode`, `GraphEdge`,
`GraphEntityType`, `GraphIds`, and `GraphProjectionContributor` contracts. The
snapshot metadata may record publication state, counts, timestamps, actor, and
scope, but it must not become a duplicate graph schema.

Projection capture must use a consistent relational snapshot. The current
class-level transaction in `AgeGraphService` is useful because AGE setup
(`LOAD 'age'`, `SET search_path`) is connection-local, but default read-committed
semantics are not enough for a multi-query rebuild. The publisher must either use a
repeatable-read transaction, or another explicit consistency mechanism, so one
materialization does not mix unrelated committed states from different moments.

Projection refresh is a graph concern, not a requirements concern. The admin
endpoint, a future scheduler, and any future durable eventing worker should call the
same graph projection publisher. Do not add AGE write side effects to
`RequirementService`, `ControlService`, `AssetService`, or other aggregate services.
The existing in-process Spring events are acceptable for synchronous domain
reactions, but they are not a durable graph projection pipeline. If asynchronous
projection becomes necessary, introduce a durable outbox/worker that still publishes
versioned snapshots through the same boundary.

AGE SQL/Cypher construction stays inside the AGE infrastructure adapter per ADR-032.
Dynamic graph or snapshot identifiers must come from configuration, generated
snapshot names, enums, or allowlists and must be validated before execution. Request
DTO validation, domain validation, traversal caps, and the approved AGE property-key
registry remain defense in depth, not replacements for adapter-side validation and
JDBC parameter binding.

The operational trigger remains privileged. HTTP refresh entry points stay under
`/api/v1/admin/**` so both bearer and browser security chains apply the shared
`ROLE_ADMIN` policy from `ApiPathMatrix`; browser-session mutations also keep the
existing CSRF protections. Refresh outcomes and publication decisions use the
standard `ErrorResponse` / `GlobalExceptionHandler` envelope and structured SLF4J
logging. Do not expose raw SQL, Cypher, stack traces, or user-controlled graph
properties in API error bodies.

Configuration for new graph publication knobs, such as retention count, scheduled
refresh enablement, or snapshot naming limits, must be bound through validated
`@ConfigurationProperties` instead of adding more ad hoc `@Value` fields.

The extensibility seam is refresh scope. The initial production fix may publish one
global all-project snapshot, matching the current materialization behavior, but the
metadata and publisher contract should make that scope explicit so a later
project-scoped refresh or scheduled refresh can reuse the same publication model.

## Consequences

### Positive

- Readers observe either the previous complete snapshot or the newly published
  complete snapshot, never a destructive rebuild in progress.
- Aggregate services stay focused on relational source-of-truth writes and do not
  import AGE or duplicate graph schema rules.
- Future scheduled or durable-event refresh triggers can reuse the same publisher
  without changing the reader contract.
- ADR-032's query-construction boundary remains intact for snapshot names and graph
  metadata, not just node and edge properties.

### Negative

- The implementation needs projection metadata and snapshot cleanup logic rather
  than a single `DETACH DELETE` followed by `CREATE` calls.
- A full all-project snapshot can still be expensive; project-scoped refresh is a
  later optimization, not required for the initial atomicity fix.
- Concurrent refresh requests need an explicit serialization mechanism so two
  publishers cannot race the active-snapshot pointer.

### Risks

- Treating an in-process event listener as "async projection" would keep the system
  non-durable and can still lose graph updates on process failure.
- Building the snapshot under read-committed isolation can publish a graph assembled
  from multiple database moments.
- Deleting old snapshots before the active pointer swap is visible can recreate the
  empty-graph failure mode this ADR is meant to remove.
- Adding project-scoped snapshot state without a project-scoped reader contract can
  make cross-project graph reads ambiguous. Scope must be explicit in both metadata
  and reader resolution.

### Out of scope

- Replacing Apache AGE with Neo4j or another graph database.
- Introducing user-supplied Cypher or graph query language support.
- Replacing the existing graph projection contributor model.
- Building a general event bus or outbox solely for this issue.
- Making graph projection a GRC derivation engine or changing ADR-057/ADR-058
  screening behavior.

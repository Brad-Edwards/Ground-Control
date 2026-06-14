# ADR-005: Apache AGE for Graph Database Capabilities

## Status

Accepted

## Date

2026-03-08

## Context

Ground Control's requirements and architecture management features need graph database capabilities to model dependency relationships, traceability links, and constraint propagation. These relationships are inherently graph-structured and awkward to express with recursive CTEs or junction tables.

Two options were evaluated:

**Option A: Neo4j (separate graph database)**
- Mature, full Cypher support, rich ecosystem
- Adds a separate data store to deploy, backup, and maintain
- Data lives in two places - relational facts in PostgreSQL, graph relationships in Neo4j
- Synchronization between the two becomes a source of bugs
- Neo4j Community Edition is GPL-licensed; Enterprise requires commercial license

**Option B: Apache AGE (PostgreSQL extension)**
- Adds openCypher query support directly to PostgreSQL
- Graph data lives in the same database as relational data - one backup, one transaction boundary
- No additional infrastructure to deploy or maintain
- Known reliability issues (documented below)

### AGE Reliability Concerns

AGE is not mature software. Known issues from the apache/age issue tracker:

- **Server crashes**: Several open issues where malformed queries or edge cases cause PostgreSQL segfaults (NULL arguments, complex MERGE on empty graphs, label cache corruption)
- **Incomplete openCypher**: Missing `MERGE ... ON CREATE SET`, `datetime()`, `EXISTS { subquery }`, `RETURN` after `DELETE`
- **pg_upgrade broken**: AGE uses `reg*` types that reference OIDs not preserved by pg_upgrade. Major PostgreSQL version upgrades require dump/restore
- **Extension interference**: When loaded in `shared_preload_libraries`, AGE can interfere with other extensions in different databases on the same cluster
- **No managed cloud support**: Not available on AWS RDS or Google Cloud SQL. Azure has limited support. Self-managed only
- **Docker image gaps**: No ARM64 on `latest` tag, occasional CVEs in base image

### Why AGE Despite the Issues

Our near-term graph workload is narrow and low-volume: modeling requirement dependencies, ADR relationships, and architecture traceability. We are not running arbitrary user-supplied Cypher, complex MERGE patterns, or high-throughput graph traversals. The known crash triggers are avoidable for our use case.

The operational simplicity of one database outweighs the risks at this stage. If AGE proves unreliable as complexity grows, migrating graph data to Neo4j is straightforward - the Cypher queries transfer directly.

## Decision

Use Apache AGE as a PostgreSQL extension for graph capabilities. Run the `apache/age` Docker image (based on PostgreSQL 16) instead of the stock `postgres:16` image in development.

Mitigations:
- Pin a specific AGE image tag, do not use `latest`
- Avoid known crash-trigger patterns (NULL agtype arguments, complex MERGE on empty graphs)
- Use dump/restore for major PostgreSQL version upgrades instead of pg_upgrade
- Keep graph queries simple and tested - no user-supplied Cypher
- Monitor the apache/age issue tracker for fixes to the crash bugs

## Consequences

### Positive

- Single database for relational and graph data - one backup, one connection, one transaction
- No additional infrastructure to deploy
- Cypher queries alongside SQL in the same application
- Migration path to Neo4j exists if we outgrow AGE

### Negative

- pg_upgrade not usable - major version upgrades require dump/restore
- Smaller ecosystem and community than Neo4j
- Must avoid known crash-trigger patterns
- Cannot use managed PostgreSQL services (RDS, Cloud SQL) without losing graph features

### Risks

- AGE crashes in production under an untested query pattern. Mitigated by keeping queries simple, tested, and avoiding known triggers.
- AGE project stalls or is abandoned. Mitigated by the data being standard PostgreSQL tables - worst case, we drop the extension and rewrite queries as SQL.
- Need for graph features exceeds AGE's capabilities. Mitigated by Cypher compatibility - queries port to Neo4j with minimal changes.

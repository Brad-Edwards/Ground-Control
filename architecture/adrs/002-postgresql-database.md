# ADR-002: PostgreSQL as Primary Database

## Status

Accepted

## Date

2026-03-08

## Revision

2026-03-09 - Updated to reflect ADR-013 (Java/Spring Boot rewrite). Decision unchanged.

## Context

Ground Control needs a relational database. PostgreSQL is the primary data store, accessed via Hibernate (Spring Data JPA) and the PostgreSQL JDBC driver. Apache AGE extends PostgreSQL with graph capabilities (ADR-005).

## Decision

- PostgreSQL as the sole primary database
- PostgreSQL JDBC driver via Hibernate / Spring Data JPA for ORM
- Flyway for schema migrations
- Redis for caching (Spring Cache)

## Consequences

### Positive

- PostgreSQL's JSONB, full-text search, and advanced indexing cover foreseeable needs without additional datastores
- Apache AGE adds graph capabilities in the same database (ADR-005)
- Mature tooling for backups, replication, and monitoring

### Negative

- PostgreSQL-specific features (if used) reduce portability to other databases

### Risks

- None significant - PostgreSQL is the standard choice for Spring Boot projects

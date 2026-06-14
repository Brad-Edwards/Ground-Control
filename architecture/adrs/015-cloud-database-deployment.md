# ADR-015: Cloud Database Deployment

## Status

Withdrawn

## Date

2026-03-12 (accepted) / 2026-03-13 (withdrawn)

## Context

Ground Control's requirements data lives in a local Docker volume (PostgreSQL via Docker Compose). This data is the source of truth for the system's requirements graph - requirements, relations, traceability links, GitHub issue sync records, and Envers audit history.

The original concern was data durability: local Docker volumes are ephemeral. A `docker compose down -v`, a machine rebuild, or a disk failure destroys the data.

The proposed solution was AWS RDS PostgreSQL 16 in the `catalyst-dev` account.

## Why Withdrawn

**RDS violates ADR-005.** ADR-005 chose Apache AGE specifically so graph and relational data share one database, one transaction boundary. RDS does not support AGE as an extension. The original ADR-015 accepted this as a trade-off ("all analysis works via JPA"), but that contradicts the core rationale of ADR-005 - if we accept running without AGE in production, we've effectively abandoned the decision to use AGE.

The correct path when cloud deployment is needed is self-managed PostgreSQL with AGE on compute we control (EC2, ECS, or similar), not a managed service that drops a committed capability.

## Decision

Withdraw the RDS decision. Development defaults to local Docker Compose with the `apache/age` image. Data durability is handled by:

- **Named Docker volume** (`gc-postgres-data`) - survives container rebuilds, `docker compose down`, image updates
- **Reproducible data** - requirements from StrictDoc import, GitHub data from sync. Both operations are idempotent.
- **Standard backup** - `pg_dump` for manual snapshots when needed

The RDS infrastructure has been destroyed. Terraform modules and environment config remain in the repository for reference but are not deployed.

## Consequences

### Positive

- AGE available in all environments (no feature gap between local and "production")
- No cloud cost (~$14/mo eliminated)
- No AWS credential / SSM / security group management overhead
- Simpler CI pipeline (no terraform job, no OIDC, no path detection)
- No network latency to database

### Negative

- Data lives on developer machine only - no off-machine durability until cloud deployment is revisited
- No automated backups (manual `pg_dump` only)

### When to revisit

When the application needs to run outside the developer machine (multi-user, demo, staging), deploy self-managed PostgreSQL+AGE on infrastructure that supports the extension.

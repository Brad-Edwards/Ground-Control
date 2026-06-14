# Design Specifications

Pre-pivot design artifacts are archived in `archive/docs/`. See:

- `archive/docs/requirements/project.sdoc` - product requirements (replaces PRD)
- `archive/docs/api/API_SPEC.md` - target API design
- `archive/docs/architecture/DATA_MODEL.md` - target data model
- `archive/docs/user-stories/USER_STORIES.md` - user stories
- `archive/docs/user-stories/USE_CASES.md` - use case descriptions

For architecture decisions, see [ADRs](../adrs/).

## Design Notes Index

| Document | Phase | Description |
|----------|-------|-------------|
| [Phase 1: Requirements Management](../notes/phase1-requirements-design.md) | 1 | Data model, app structure, key patterns for the requirements management system |
| [Phase 2: Cloud Database](../notes/phase2-cloud-database-design.md) | 2 | RDS deployment, Terraform structure, security model, developer workflow |
| [Architecture Model Artifacts](../notes/architecture-model-artifacts.md) | 5 | Guardrails for modeling C4 diagrams, architecture tests, and fitness functions as traceable artifacts without introducing a parallel artifact domain |
| [Security Scanner CI Preflight](../notes/security-scanner-ci-preflight.md) | CI | Guardrails for adding advisory Trivy and OSV-scanner jobs without duplicating policy, security, or runtime abstractions |
| [Mixed Entity Graph Traversal Preflight](../notes/mixed-entity-graph-traversal-preflight.md) | 4 | Guardrails for mixed-entity graph traversal, path finding, subgraph extraction, and visualization |

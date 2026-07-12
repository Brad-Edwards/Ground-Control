### Removed - Contract-Locked Development enforcement gates

Dropped the CLD enforcement machinery as premature optimization: the
mutation-testing CI gate and runner (`tools/mutation/`), the protected-path
authority gate, the architecture-registry boundary gate with its
`RegistryBoundaryArchitectureTest`, the `architecture/registry/` data, the
oracle-battery scaffolds, and the `gc_post_design_authority_approval` MCP tool.
ADR-087 is withdrawn and the CLD wave issues (#1296 through #1299) are closed.
The reviewer anti-gaming prompt checklist is retained. Model-tier optimization is
deferred until the Temporal `/implement` pipeline emits real per-stage telemetry.

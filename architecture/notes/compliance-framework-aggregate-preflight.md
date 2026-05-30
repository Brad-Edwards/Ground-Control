# Compliance Framework Mapping Aggregate Preflight

Wave 5 (issue #744). Architecture preflight for the GC-I002 / GC-I005 /
GC-I007 / GC-L011 cluster—promotes the historical
`AuditLinkTargetType.FRAMEWORK` string path into a first-class
`ComplianceFrameworkMapping` aggregate and ships the corresponding
compliance-posture and cross-framework gap analyses through the consolidated
`gc_analyze` MCP surface.

Cross-reference: this note is paired with
`architecture/notes/mcp-grc-entity-crud-preflight.md`, which carved the MCP
parity for the aggregate out into GC-L011 and recorded the link-target
promotion path the backend must take (`GraphTargetResolverService`, graph
projection contributor, REST DTOs, MCP enum mirror, adapter tests). The MCP
parity ships in the same change as the backend aggregate per the
cross-cutting decision recorded in the wave architecture brief.

## Boundary Decisions

1. **Single polymorphic aggregate.** `ComplianceFrameworkMapping` carries a
   polymorphic source endpoint (Requirement OR Control via paired nullable
   FKs plus a DB XOR `CHECK ck_cfm_endpoint_xor`). GC-I002 covers
   requirement-to-framework mappings and GC-I005 covers control-to-framework
   mappings; one row shape handles both so cross-framework gap analysis
   walks a single table. The service layer adds
   `validateExactlyOneSourceEndpoint` to fail fast on invalid combinations
   before reaching the constraint.

2. **Typed framework identifier as the seed enum, free-form string only for
   genuine externals.** `ComplianceFrameworkIdentifier` enumerates the
   currently supported frameworks (`SOC2`, `SOX`, `ISO_27001`, `NIST_CSF`,
   `PCI_DSS`). A separate `frameworkIdentifier` `VARCHAR(200)` column is
   reserved for industry- or customer-specific frameworks that do not yet
   justify a first-class enum constant. Adding a new framework here is the
   preferred path; the external string is the escape hatch and triggers a
   `limitations` entry on every analysis response that surfaces it.

3. **Per-mapping coverage qualifier.** `CoverageLevel`
   (`FULL` / `PARTIAL` / `COMPENSATING`) lives on each mapping row per
   GC-I005's per-mapping metadata clause. Element-level coverage on
   compliance-posture analyses is computed by reducing the constituent
   mappings: any `FULL` wins; otherwise any `PARTIAL`; otherwise
   `COMPENSATING`.

4. **Gap severity is derived, not stored.** `GapSeverity` is a result-shape
   enum on the cross-framework gap analysis, not a column on the aggregate.
   The current derivation is coverage-shape only:
   - `FULL` + no compensating → `NONE`
   - `FULL` + compensating → `LOW`
   - `PARTIAL` + compensating → `MEDIUM`
   - `PARTIAL` only → `HIGH`
   - `COMPENSATING` only → `LOW`
   - no mappings (future): `CRITICAL`.

   Evidence-freshness propagation (stale-evidence `HIGH`, missing-evidence
   `CRITICAL`) is intentionally out of scope for the initial release; the
   gap result envelope carries a `limitations` array that today flags every
   external-identifier row and is the natural place to surface freshness
   carve-outs in a follow-up.

5. **Graph projection contributor + GraphTargetResolverService updates ship
   in the same change.** Per the `mcp-grc-entity-crud-preflight` ledger,
   promoting framework mappings to first-class status without updating the
   link-target enums, `GraphTargetResolverService`, and the graph
   projection contributor leaves the graph view inconsistent. This change
   adds `COMPLIANCE_FRAMEWORK_MAPPING` to `GraphEntityType`,
   `validateComplianceFrameworkMappingTarget` to `GraphTargetResolverService`
   (callable by future link sources), and
   `ComplianceFrameworkMappingGraphProjectionContributor` emitting nodes and
   `MAPS_REQUIREMENT` / `MAPS_CONTROL_TO_FRAMEWORK` edges.

6. **`AuditLinkTargetType.FRAMEWORK` is `@Deprecated` but preserved.** The
   audit-link target enum value is marked deprecated for new callers but
   the resolver path remains an `externalTarget` so legacy audit links keep
   working. A follow-up ADR will carve out the deprecation; a migration
   will then rewrite extant FRAMEWORK rows to point at the aggregate. This
   PR does NOT auto-migrate existing rows.

7. **gc_analyze extension follows the locked protocol.** Two new kinds
   (`compliance_posture`, `cross_framework_gap`) dispatch through
   `mcp/ground-control/lib.js::request()` to fixed
   `/api/v1/analysis/grc/{compliance-posture,framework-gap}` paths. No
   caller-supplied URLs, headers, or tokens. Adapter tests in
   `gc-analyze.test.js` lock the URL + camelCase param shape.

8. **MCP CRUD parity per ADR-035.** The new named tool
   `gc_compliance_framework_mapping` exposes `create` / `update` / `delete`
   actions only; reads (`list`, `get`) route through `gc_query` against
   the `/api/v1/compliance-framework-mappings` prefix, which is added to
   `GC_QUERY_PATH_ALLOWLIST` (mirrored in `README.md` and ADR-035 per the
   drift-catch test in `gc-query.test.js`).

9. **Enum mirror per ADR-034.** Three new enums
   (`ComplianceFrameworkIdentifier`, `CoverageLevel`, `GapSeverity`) are
   mirrored in `mcp/ground-control/lib.js`, `frontend/src/types/api.ts`,
   and `tools/policy/checks.py::ENUM_CONTRACT_INVENTORY`. Declaration order
   matches the backend Java enums exactly; the policy-check post-condition
   enforces parity.

## Non-Goals

- No promotion of existing `AuditLinkTargetType.FRAMEWORK` rows into the
  new aggregate (deferred to a follow-up ADR + migration).
- No `gc_analyze` evidence-freshness propagation into gap severity
  (deferred—current severity is coverage-shape only).
- No first-class "framework element catalog" (for example seeded `SOC2.CC1.1..CC9.x`
  rows). Elements are still author-supplied strings on each mapping; future
  work can promote them to a typed registry per framework.
- No change to ADR-026 (REST access control), ADR-032 (AGE query boundary),
  ADR-034 (enum-mirror contract), ADR-035 (MCP tool catalog), or ADR-045
  (evidence derivation).

## Security Notes

- Compliance gap data reveals tenant readiness posture; every CRUD and
  analysis endpoint resolves project at the boundary (`ProjectService
  .resolveProjectId` / `requireProjectId`) before delegation.
- The aggregate is `@Audited` (Hibernate Envers) with `ActorHolder`
  provenance for every mutation.
- External `frameworkIdentifier` strings are bounded
  (`@Size(max = 200)`), control-character-stripped at the service layer,
  and sanitized again before they land in `limitations` strings to guard
  against log injection.
- `CompliancePostureService.sanitizeForLog` is the single sanitizer used
  by both posture and gap services so freshness/severity additions inherit
  the same guard.

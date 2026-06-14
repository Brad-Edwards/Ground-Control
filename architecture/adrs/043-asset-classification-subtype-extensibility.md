# ADR-043: Asset classification and subtype extensibility

- Status: Accepted
- Date: 2026-05-16
- Driver: GC-M011 (issue #722)

## Context

GC-M011 requires the asset model to support operational asset kinds (service,
application, infrastructure resource, workload, identity, data asset,
endpoint, integration, vendor dependency) without collapsing them into a flat
record, plus shared core attributes, subtype-specific metadata, and **schema
layering where needed**.

The existing `OperationalAsset` aggregate already carries shared core
attributes (uid, name, description, owner, `steward`, environment, criticality,
business context, scope designation - GC-M012 introduced the last six) and
classifies via the `AssetType` enum
(`APPLICATION / SERVICE / SYSTEM / DATABASE / NETWORK / HOST / CONTAINER /
IDENTITY / DATA_STORE / ENDPOINT / INTEGRATION / WORKLOAD / THIRD_PARTY /
BOUNDARY / OTHER`). That enum maps the operational kinds the requirement
names, so the *classification* clause is already covered: `service →
SERVICE`, `application → APPLICATION`, `infrastructure resource → SYSTEM /
DATABASE / NETWORK / HOST / CONTAINER`, `workload → WORKLOAD`, `identity →
IDENTITY`, `data asset → DATA_STORE`, `endpoint → ENDPOINT`, `integration →
INTEGRATION`, `vendor dependency → THIRD_PARTY`.

What's missing:

1. A way to express **narrower kinds under `AssetType`** without proliferating
   enum values or per-vendor parallel aggregates (the codex architecture
   preflight for #722 explicitly rejected both - `AssetType` is "the
   high-level class, not the place for every cloud, endpoint, identity, data,
   or vendor product variant," and parallel `ServiceAsset` / `IdentityAsset`
   records would need their own future ADR).
2. A **subtype-specific metadata bag** so a project can attach the fields
   that only make sense for a particular subtype (cloud account id, AWS
   region, identity client id, vendor SKU, etc.) without those fields
   bleeding into the core asset contract.
3. A **schema layering mechanism** so projects that need to formalize a
   subtype's metadata contract can register a schema and have new / updated
   assets validated against it.

The preflight also constrained the shape of any schema-validation work to a
single reusable component behind `AssetService`, one error-detail shape, and
no asset-specific JSON-Schema dependency - the existing repo helpers
(`StringObjectMapConverter`, `DomainValidationException`,
`GlobalExceptionHandler`, the `ApiPathMatrix`) own validation and persistence
already.

## Decision

### 1. Subtype discriminator on `OperationalAsset`

Add a nullable `subtype VARCHAR(100)` column. It is free-form,
project-defined, and stores a narrower classification under the asset's
`AssetType`. Example: an asset with `AssetType=IDENTITY` can carry
`subtype="user_account"` or `subtype="service_principal"` to distinguish
human identities from machine ones without forcing two `AssetType` values.

`AssetType` remains the **queryable**, **typed**, **stable-vocabulary**
top-level facet. `subtype` is the **flexible**, **project-defined**,
**catalog-able** narrower facet.

### 2. Subtype-specific metadata bag

Add a nullable `metadata TEXT` column persisted via
`JacksonTextCollectionConverters.StringObjectMapConverter` (the same
mechanism `MethodologyProfile.inputSchema/outputSchema` uses). The column
type is `Map<String, Object>` at the entity level; the validator (below)
enforces scalar-only values and universal bounds.

Universal bounds (apply regardless of registered schema):

- `≤ 50` keys per asset
- `≤ 100` characters per key
- `≤ 4096` characters per string value
- Values must be `String`, `Number`, `Boolean`, or `null`. Nested objects
  and arrays are intentionally rejected - they invite schema drift and make
  the metadata bag opaque to graph / search projection.

Metadata replacement on update is atomic - a non-null `metadata` field in
`UpdateAssetCommand` replaces the entire map. Partial-key merges are
intentionally not supported; the cost of an ambiguous merge semantic
(reset vs. patch vs. delete-by-null-value) outweighs the convenience. A
paired `clearMetadata` boolean lets callers null the field entirely; the
clear flag wins over an assign in the same payload (mirrors GC-M012's
clear-flag convention on the other nullable metadata fields).

### 3. Subtype-schema registry

A new aggregate `AssetSubtypeSchema` keyed
`(project_id, asset_type, subtype, schema_version)`:

- `status: ACTIVE | DEPRECATED`. Exactly one ACTIVE row may exist per
  `(project, assetType, subtype)`; registering a second ACTIVE row auto-
  deprecates the prior ACTIVE row (enforced in `AssetService.registerSubtype
  Schema`).
- `schema_body: Map<String, Object>` - the schema body. The repo defines a
  minimal field-shape language for it (see below).
- `description: TEXT` - optional narrative.

CRUD lives behind `AssetService` (`registerSubtypeSchema`,
`updateSubtypeSchema`, `deprecateSubtypeSchema`, `getSubtypeSchema`,
`getActiveSubtypeSchema`, `listSubtypeSchemas`). A new
`AssetSubtypeSchemaController` exposes the wire surface at
`/api/v1/assets/subtype-schemas` - inside the existing `/api/v1/assets/**`
path matrix, so security stays under ADR-026 / ADR-037 (no controller-local
auth, no new role enums).

### 4. Schema language (repo-implemented, no external dependency)

A schema body has the shape:

```jsonc
{
  "fields": {
    "<fieldKey>": {
      "type": "STRING" | "INTEGER" | "NUMBER" | "BOOLEAN" | "ENUM",
      "required": true | false,         // default false
      "maxLength": <int>,                // STRING only
      "minimum": <number>,               // INTEGER / NUMBER only
      "maximum": <number>,               // INTEGER / NUMBER only
      "values": ["A", "B", ...]          // ENUM only (case-sensitive string list)
    }
  },
  "allowAdditional": true | false        // default false
}
```

- `INTEGER` rejects fractional values; `NUMBER` accepts both integer and
  decimal forms.
- `ENUM` matches case-sensitive string equality against `values`.
- `allowAdditional: false` (the default) rejects metadata keys not declared
  in `fields`. `allowAdditional: true` lets callers attach extra keys, still
  subject to the universal bounds. The conservative default fits the
  contract semantics: an explicit schema is an explicit shape.

### 5. `AssetSubtypeValidator` (single component behind `AssetService`)

One Spring `@Component` owns both universal bounds and schema enforcement.
Called from `AssetService.create` and `AssetService.update` after the asset
state is composed. On a subtype-bearing asset whose
`(project, assetType, subtype)` has an ACTIVE schema, the validator
enforces the schema. Otherwise it enforces only universal bounds.

Errors raise `DomainValidationException` with stable error code
`asset_metadata_invalid` and a structured detail map naming `reason`,
`field`, `expectedType`, `actual`, `limit`, etc. The detail map is
deterministic so MCP / frontend clients can present field-level errors
without parsing prose.

### 6. Graph projection

`AssetGraphProjectionContributor` adds `subtype` to the
`OPERATIONAL_ASSET` node property map alongside the existing
`assetType / owner / steward / environment / criticality / business
Context / scopeDesignation` properties. `metadata` is **intentionally not
projected** - it is free-form, high-cardinality, and may carry per-asset
detail that does not belong on a graph node visible to every projection
consumer. Graph search by subtype joins on a stable, low-cardinality
discriminator; graph search by metadata is out of scope.

## Alternatives considered

- **One enum value per concrete subtype on `AssetType`.** Rejected by the
  preflight: `AssetType` is the high-level class, not a vendor / cloud /
  endpoint variant catalog.
- **A parallel aggregate per subtype kind** (`ServiceAsset`,
  `IdentityAsset`, `VendorDependencyAsset`, etc.). Rejected by the
  preflight unless a future ADR proves the subtype is a distinct aggregate
  with independent lifecycle and invariants. Most subtypes share the
  asset's relations, links, observations, and external IDs; splitting the
  aggregate would force duplication across every join surface.
- **A free-form `metadata` bag with no schema mechanism.** Satisfies clauses
  3 and 4 but leaves clause 5 (`schema layering where needed`) unmet. The
  requirement is "shall support," not "may support" - the model must offer
  the seam.
- **Embedding the schema on the asset row itself** (each asset carries its
  own `subtypeSchema`). Defeats the point of schema layering - schemas are
  meant to apply across all assets of the same subtype, not co-vary per
  record.
- **Pulling in an external JSON-Schema library**
  (`networknt/json-schema-validator`, `everit-org/json-schema`). Rejected
  for scope: the validator needs five field types and three bounds (`max
  Length`, `minimum`, `maximum`) to satisfy GC-M011's likely use cases. A
  full JSON-Schema engine adds a new dependency surface, schema-language
  surface, and error-shape surface for marginal benefit at this stage. If
  a future requirement needs richer validation (custom keywords, `$ref`
  composition, conditional schemas, format validators), introducing the
  library then is the right ADR; pre-emptive adoption now is not.

## Consequences

- `AssetType` semantics are unchanged. Existing callers do not need to
  reclassify any asset.
- The migration adds two nullable columns plus a new table; rollback is
  straightforward (drop columns, drop table). No data migration is
  required - existing rows keep `subtype = NULL` and `metadata = NULL` and
  remain valid under the bounds-only validation path.
- MCP `gc_asset` gains `subtype`, `metadata`, the four matching clear
  flags, and six subtype-schema registry actions (`subtype_schema_create`,
  `subtype_schema_update`, `subtype_schema_deprecate`, `subtype_schema_
  get`, `subtype_schema_get_active`, `subtype_schema_list`). Folding into
  the existing tool keeps the catalog flat (ADR-035).
- Schema authors define their own field-shape contracts using the minimal
  language above. The validator enforces structural shape; semantic
  validation (for example, "this region must be a known AWS region") remains a
  caller concern.
- Audit history follows existing Envers patterns. Both new asset columns
  ride the `operational_asset_audit` table (V073 / V074); the new
  `asset_subtype_schema` aggregate gets its own `_audit` table (V075 /
  V076).
- The `architecture/notes/asset-classification-subtype-extensibility-
  preflight.md` codex note remains as historical preflight context;
  this ADR supersedes it as the durable design record.

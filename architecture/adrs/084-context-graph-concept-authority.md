# ADR-084: Context-Graph Concept Authority and Time Semantics

## Status

Accepted

## Date

2026-07-03

## Context

The living context graph is Ground Control's thesis, and it has no
ontology of its own. The census in
`docs/research/context-graph-ontology/assessment.md` (issue #1301) found:
26 node kinds with three never emitted; nine per-aggregate link enums plus
~25 literal edge strings hard-coded in projection contributors; the same
concept minted independently up to six times (`ASSOCIATED`,
`EVIDENCED_BY`, `OBSERVED_IN`, ...) with nothing declaring whether the
meanings coincide; traceability links - the founding value proposition -
not projected into the graph at all; five time mechanisms with no declared
as-of spine; and no machine-readable ontology artifact anywhere.

Milestones 17 and 18 are about to add workflow entities, contracts,
invariants, and the architecture registry to this vocabulary. Without a
governing decision, they will add them the way every prior wave did:
per-aggregate, unregistered, drift-prone.

Separately, the ACES SDL project (`../aces-sdl`) has implemented a
concept-authority stack solving exactly this problem class for its own
domain: concept families published as data with provenance and mandatory
extension rules, artifact bindings that tie local strings to canonical
concepts, controlled vocabularies with governed extension, a closed effect
vocabulary for external ontologies (`annotates | aligns | refines |
constrains`), a content-hashed schema publication manifest, and a typed
declared-vs-observed reconciliation model with realization provenance. It
is cyber-range domain-specialized, carries no requirements/GRC/
traceability families, and its semantic-integrity umbrella (SEM-200) is
still DRAFT. The assessment weighs adoption, independence, and alignment;
this ADR records the decision.

## Decision

Adopt the concept-authority **pattern** with Ground-Control-native
content, publish the ontology as data under the ADR-082 contracts
surface, align with ACES through a versioned crosswalk under a closed
effect vocabulary, and declare Envers revisions the canonical time spine.
No runtime dependency on ACES is introduced in either direction.

### 1. Three-layer semantic governance

- **Concept authority layer**: what a concept means, declared once.
  Ground Control's native concept families cover the engineering-process
  domain: requirements-and-traceability, architecture-and-boundaries,
  threats-risks-and-controls, evidence-and-observation,
  workflow-and-process, contracts-and-invariants (milestone 18), and
  research. Families borrowed from established sources carry provenance
  `adopted` or `adapted` (as ACES does with UCO/STIX); Ground Control
  originals are `native` and must declare extension scope, relation
  rules, and non-ambiguity constraints.
- **Artifact binding layer**: every vocabulary-bearing artifact - the
  `GraphEntityType` enum, each link enum, each contributor's edge
  strings, MCP tool enums, console filters - binds its local strings to
  canonical concepts. A local string with no binding is a policy failure.
- **External authority layer**: what an external ontology may do to
  native meaning is limited to the closed effect vocabulary
  `annotates | aligns | refines | constrains`. This governs the ACES
  crosswalk and any future alignment (UCO, OSCAL, SPDX, and kin) alike.

### 2. The ontology is data under the contracts surface

`contracts/ontology/` (a sibling of the ADR-082 artifact set) holds:

- `gc-concept-families-v1.json` - the family catalog with provenance and
  per-family extension rules;
- `gc-controlled-vocabularies-v1.json` - edge and classification
  vocabularies with owners; shared concepts (`EVIDENCED_BY`,
  `ASSOCIATED`, `OBSERVED_IN`, ...) are declared once here and bound per
  aggregate, replacing independent minting;
- `gc-artifact-bindings-v1.json` - the binding of each code-level
  vocabulary surface (enum or contributor literal set) to families and
  vocabulary terms;
- `crosswalks/aces-concept-families-v1.json` - the ACES alignment (see
  §4).

Enforcement is inventory-driven in the ADR-034 style: a policy check
fails any `GraphEntityType` value, link-enum value, or contributor edge
string that is not bound in the artifacts, and fails any binding whose
code-level surface no longer exists. Adding vocabulary is a one-row
registration, not a new checker. Schema evolution follows ADR-082's
declared-breaking-change gate; the artifacts are versioned in-name.

The initial binding gate (issue #1307) has the following guardrails:

- A binding is identified by **surface plus local value**, never by the
  local string alone. Repeated spellings such as `IMPLEMENTS` or
  `EVIDENCED_BY` may bind to one controlled term only after their direction,
  endpoint roles, and domain meaning are shown to coincide. Multiple local
  bindings may point to that one term; the binding artifact must not repeat
  the term's definition or mint an artifact-local synonym.
- The code inventory is discovered independently of the binding rows and the
  two sets are compared in both directions. Discovery covers
  `GraphEntityType`, every edge/link/relation enum surface, and every
  `GraphProjectionContributor` implementation regardless of package. A
  contributor edge expression that the static checker cannot resolve to a
  literal, a declared constant, or a bound enum surface is a policy failure,
  not an ignored value. Endpoint-target enums and incidental uppercase
  strings (property keys, identifiers, and edge IDs) are not edge terms.
- Artifact structure is fail-closed: version/provenance/owner vocabularies are
  closed; family, term, surface, and binding identities are unique; every
  reference resolves; native families carry non-empty extension scope,
  relation rules, and non-ambiguity constraints; malformed JSON and unknown
  surface kinds fail policy. Repository paths used to locate a surface are
  normalized, repository-relative, and constrained to the declared source
  roots.
- These files are static governance contracts, not runtime application
  configuration. Backend aggregates remain authoritative and contributors
  continue to emit their existing strings. The policy runner reads tracked
  source and contract files in-process, performs no network or subprocess
  lookup for ontology data, and reports stable policy violations rather than
  introducing an application exception or error-envelope hierarchy.

The consolidation map is therefore the set of surface-qualified bindings that
point at a shared controlled term. It is not a fourth registry and does not
authorize an emission rename. Any later emission change must preserve graph
consumer compatibility and land only after the old and new surfaces can be
validated against the controlled vocabulary.

### 3. Repairs mandated by the census

1. **Traceability enters the graph.** `TraceabilityLink` edges
   (`IMPLEMENTS`, `TESTS`, `DOCUMENTS`, `CONSTRAINS`, `VERIFIES`) are
   projected into the AGE graph with their artifact endpoints (external
   artifacts as identifier-addressed nodes per the existing external-target
   policy). The traceability spine is context-graph content, full stop.
2. **Dead vocabulary is resolved.** `CONTROL_LINK` and `AUDIT_LINK` are
   removed from `GraphEntityType` (links are edges); `RISK_APPETITE_PROFILE`
   either gains its contributor or leaves the enum. A governed ontology
   carries no unreachable terms.
3. **Thesis-relevant projections are decided deliberately**: workflow
   runs and work items (milestone 17), derivation/boundary snapshots
   (GC-GRC-005 family), and contracts/invariants (milestone 18) join the
   graph through registered families, not ad hoc contributors.

### 4. The ACES relationship: align now, converge later, couple never (yet)

- **Pattern adoption is immediate** (this ADR): the layering, the
  families-as-data shape, the binding discipline, and the effect
  vocabulary are taken from the proven ACES implementation - the same
  discipline, portfolio-native.
- **Crosswalk, not dependency.** Where a Ground Control family genuinely
  shares a concept with an ACES family - assets, observables/evidence,
  provenance, and tasks-runs-studies against workflow runs - the crosswalk
  artifact records one native/external family pair per row. The effect is
  directional: it states the external family's effect on Ground Control's
  native meaning, so `refines` means the external family is narrower than the
  named Ground Control family. Each row retains a rationale or limitation;
  grouped arrays and positional effect lists are not a valid substitute.
- **Release-backed validation, not live upstream lookup.** An external pin names
  the released distribution, release version, artifact path inside that
  distribution, catalog schema version, and SHA-256 of the artifact's raw
  bytes. The exact catalog bytes used for review are retained as an immutable,
  repo-relative reference snapshot under
  `contracts/ontology/external/aces-sdl/<release>/`; they are not imported into
  the crosswalk or copied into policy constants. The existing ontology policy
  gate reads the local and reference catalogs in-process, rejects unsafe paths
  and malformed or duplicate-key JSON, recomputes the raw-byte hash, and checks
  family references plus the closed effect vocabulary. It performs no branch
  lookup, package import, dependency installation, network request, or
  subprocess call. A release change therefore adds a new snapshot and updates
  the crosswalk pin in one reviewed change; it does not mutate an older pinned
  snapshot or require a parallel policy runner.
- **Time is deliberately omitted from crosswalk v1.** Ground Control has no
  time concept family against which ACES `time-and-apparatus` can be related.
  The Envers revision spine in §5 is a temporal invariant, not an implicit
  concept family and not authority to map an unrelated family. Crosswalk v1
  records this as an omission. A later time-family alignment requires a Ground
  Control time family and an ADR-084 amendment before a crosswalk row can be
  added.
- **Companion-spec extraction is the stated trajectory, with explicit
  gates.** When ACES's SEM-200 umbrella reaches ACTIVE and both sides
  operate their family sets, the shared metamodel - surface/boundary,
  concept family, artifact binding, provenance classes
  (declared/derived/observed), typed reconciliation deltas
  (`CREATE/UPDATE/DELETE/UNCHANGED` against provenance-bound snapshots),
  evidence versus derived measures, and time - is extracted into a
  companion specification both products consume as domain packs. Because
  both sides use the same artifact shape from the start, extraction is a
  merge of catalogs, not a rewrite.
- **Convergence requirements use the existing requirements graph.** ACES-side
  requirements remain DRAFT while SEM-200 is DRAFT, state the `SEM-200 ACTIVE`
  gate in their normative text, and use the existing requirement-relation
  model to preserve the dependency where applicable. Ground Control's
  project-scoped requirement service, server-side UID allocation, validation,
  audit, and standard error handling remain authoritative; a GitHub issue,
  crosswalk note, or locally invented identifier is not a substitute for those
  requirement records.
- **Full adoption of ACES SDL as Ground Control's ontology substrate is
  rejected for now** (assessment §3, option B): the semantic core is
  DRAFT, the needed domain families do not exist there, and coupling a
  shipping product's ontology to a pre-1.0 roadmap fails the
  no-regression rule. This rejection is revisitable at the companion-spec
  gate, not before.

### 5. Time semantics: the Envers spine

- The **canonical as-of coordinate is the Envers revision number**
  (already a total order over ~80 audited entities with actor
  attribution). Baselines already pin to it; that pattern generalizes.
- ADR-062 graph snapshots record the revision they were materialized
  from in snapshot metadata, tying graph-time to domain-time.
- Every `asOf` parameter on read surfaces resolves to one defined
  semantics: the state reconstructable at the greatest revision whose
  timestamp is at or before the instant. Per-service divergence from
  this definition is a defect.
- ADR-045's evidence supersession chains and the research provenance
  ledger are unchanged; they are event-history semantics layered on the
  same spine, not competing spines.

## Consequences

### Positive

- One machine-checked answer to "what does this node/edge mean," with
  drift converted from a code-review hope into a policy failure.
- The traceability spine becomes graph content, aligning the product's
  query surface with its founding claim.
- Milestones 17/18 add vocabulary through registration instead of
  accretion, and the CLD architecture registry (#1295) lands as a family
  rather than a fork.
- Portfolio-level semantic convergence with ACES becomes a mechanical
  merge gated on evidence, not a bet placed today.

### Negative

- Registration is friction: new vocabulary requires artifact rows and
  bindings before code merges.
- Three JSON artifacts plus a crosswalk are new maintenance surface;
  the inventory-driven checks bound but do not remove it.
- The crosswalk tracks a moving target while ACES's semantic core
  matures; it needs re-validation on ACES releases (policy-checkable,
  but real work).

### Risks

| Risk | Mitigation |
|------|------------|
| The ontology artifacts rot into documentation | The binding check fails unbound code vocabulary in CI, both directions - same teeth as ADR-034. |
| Crosswalk asserts equivalences that quietly stop being true | Crosswalk rows carry the ACES artifact version and content hash; a hash change fails validation until re-reviewed. |
| Companion-spec trajectory becomes coupling by stealth | The gates are explicit (SEM-200 ACTIVE, both family sets operating); until then the only shared thing is the artifact shape. |
| Edge consolidation breaks existing graph consumers | Consolidation maps old strings to vocabulary terms in the bindings artifact first; emission changes follow with the drift gate green. |

## Non-Goals

- Adopting OWL/SHACL/RDF or any W3C-stack representation; this is
  governance-as-data over the existing relational-plus-projection
  architecture (ADR-005/032/062 unchanged).
- Changing the source-of-truth model: relational aggregates remain
  authoritative; the graph remains a published projection.
- Modeling ACES scenarios in Ground Control or vice versa.
- Deciding graph-substrate fitness (milestone 20 owns that).

## Related Requirements

- GC-GRC-005 (architecture model aggregate), GC-GRC-031 (code-keyed
  reverse lookup), GC-O014 (contract surface hosts the artifacts)

## Related ADRs

- ADR-005, ADR-011, ADR-019/020, ADR-032, ADR-045, ADR-062, ADR-070
  (the existing graph decisions this consolidates over)
- ADR-034 (inventory-driven enforcement pattern)
- ADR-082 (contracts surface; artifact hosting and evolution gates)
- ADR-058 (derivation/drift machinery whose vocabulary converges on the
  reconciliation deltas)
- ACES ADR-012 (shared concept authority; the adopted pattern's source,
  in `../aces-sdl`)

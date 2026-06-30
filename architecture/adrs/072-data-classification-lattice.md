# ADR-072: Data Classification Lattice

## Status

Accepted

## Date

2026-06-29

## Context

`GC-GRC-006` requires a project-scoped data-sensitivity label taxonomy forming
an information-flow lattice, so that "does this change leak sensitive data?"
becomes a checkable property rather than a generative LLM judgment: label the
data, and a lattice-violating flow is the finding. The requirement has four
clauses: a shipped, project-overridable default taxonomy; label attachment to
architecture-model elements; a permitted-flow policy whose violations are
derivable by construction; and server-side storage of policy and assignments
with the architecture model.

Adjacent decisions already cover the substrate this builds on. ADR-058 owns the
continuous secure-by-design GRC target (derive facts, compute impact/gap/stale
sets, deterministic results, no DAST or runtime). GC-GRC-005 owns the
architecture-model aggregate: versioned snapshots, stable elements, and
per-snapshot element state that already carries a `dataClassificationKey` and
`DATA_FLOW` source/target stable keys. GC-GRC-004 owns the boundary model, the
structural precedent for building a versioned snapshot plus detail rows and gaps
from facts plus declared inputs. ADR-026 and ADR-037 own the REST authorization
matrix shared by both security chains; GC-T005 set the precedent that org-wide
policy writes are admin-only. ADR-027 keeps `.ground-control.yaml` out of
application services; config reaches the backend as request bodies, not file
reads.

The failure modes this decision exists to prevent: hardcoding the default labels
as the only Java enum (foreclosing per-project customization), inferring policy
from list order, treating the four sensitive labels as a simple severity ramp,
conflating sensitivity with trust boundaries or asset criticality, letting
unlabeled sensitive flows pass silently, storing queryable policy only in JSON
metadata or `.ground-control.yaml`, and returning prose or LLM judgments instead
of deterministic findings.

## Decision

### 1. The lattice is authoritative, versioned, server-side policy

A project's lattice is stored as three audited, project-scoped tables under a new
`domain/dataclassification` aggregate: a `data_classification_lattice` root (one
per project; `source` is `DEFAULT` or `CUSTOM`; a `policyVersion` content
digest), `data_classification_label` rows (the taxonomy), and
`data_classification_flow_rule` rows (the permitted-flow relation). The absence
of a root row means the shipped default applies. Label assignments are not
re-stored here. They remain `ArchitectureModelElementState.dataClassificationKey`
and version with the model snapshot, satisfying clauses (b) and (d) without a
parallel store.

### 2. The permitted-flow relation is explicit, not inferred from order

Policy is an explicit relation: a `(from, to)` edge means data labeled `from`
may flow to a sink labeled `to`. The service validates soundness (known labels
only, with no dangling edges, unique label keys, and valid key syntax), auto-adds
reflexive edges, computes the transitive closure, and rejects any cycle between
distinct labels (antisymmetry). The stored relation is the closure, so the
allow/deny decision is total and deterministic for every pair, and non-linear
lattices with incomparable labels are first-class. `rank` on a label is a
display hint only.

### 3. The default lattice ships as data

The default taxonomy (`PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `PII`,
`CREDENTIALS`, `SECRETS`, `REGULATED`) and its covering relation are defined as
data in `DefaultDataClassificationLattice`, materialised through the same
factory as custom policy, never an enum that drives evaluation logic. It models
`PUBLIC ⊑ INTERNAL ⊑ CONFIDENTIAL ⊑ {PII, CREDENTIALS, SECRETS, REGULATED}` with
the four most-sensitive labels mutually incomparable: up-flow to an
equal-or-more-protected sink is permitted, while down-flow or a cross-category
flow is a violation. Projects override it via an admin write (clause a).

### 4. Evaluation is deterministic and read-only

`DataClassificationEvaluationService.evaluate` walks the `DATA_FLOW` elements of
an architecture-model snapshot, resolves source and sink labels from the stored
assignments, and reports a flow whose `(source, sink)` pair is absent from the
permitted set as a `LABEL_FLOW_NOT_PERMITTED` violation, with no LLM judgment.
Missing labels, unknown labels, and dangling endpoints are surfaced as explicit
limitations, never a silent pass. The violation is the derivable finding (clause
c); it is reproducible from stored policy plus stored assignments, so it is not
persisted as a separate result table in this iteration.

### 5. Writes are admin-only

Lattice writes (`PUT` and `DELETE /api/v1/data-classification/lattice`) require
ROLE_ADMIN in `ApiPathMatrix`. Tampering with the taxonomy or relation, or
relabeling a source, would silently suppress real leak findings (threat
GC-TM-010, risk GC-RS-010). Reads and evaluation resolve through `ProjectService`
and fall through to the authenticated rule. Envers audits every policy revision.

## Consequences

- The leak-detection class of GRC findings becomes deterministic and auditable;
  policy integrity is enforced by auth and audit, not by trust in an LLM.
- Durable `Finding` minting, auto-evaluation inside the derivation pipeline,
  stale-set computation on policy change, and frontend editing are deliberately
  left as follow-on work (GC-GRC-020 and beyond). The seam is data-driven, so
  those add policy rows, rule handlers, or response fields without changing the
  auth model, error envelope, graph identity, or architecture-model boundary.
- A future need to allow a cross-category flow (for example PII to SECRETS) is a
  policy edit, not a code change.

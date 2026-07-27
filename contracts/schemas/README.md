# Contract Schemas

JSON Schemas under this directory are committed contract artifacts for durable
records and workflow/activity payloads.

Each schema declares `x-ground-control-invariants`. Every non-`none` invariant
must name at least one enforcing test or spec file, and `make policy` rejects
missing references.

`measurement/` holds the ADR-090 production-line measurement contract: the
record shape every emitter maps onto, and the shape of the station catalogue.
The catalogue **data** lives beside it at
`contracts/measurement/gc-station-catalogue-v1.json`, following the
`contracts/ontology/` precedent that a schema and the data it governs are
separate artifacts. `run_measurement_catalogue_check` in
`tools/policy/checks.py` is what makes the catalogue authoritative rather than
descriptive: it fails when a station id the MCP layer emits, a `gc:phase`
marker value, or an ADR-036 routing stage resolves to nothing declared.

# Contract Schemas

JSON Schemas under this directory are committed contract artifacts for durable
records and workflow/activity payloads.

Each schema declares `x-ground-control-invariants`. Every non-`none` invariant
must name at least one enforcing test or spec file, and `make policy` rejects
missing references.

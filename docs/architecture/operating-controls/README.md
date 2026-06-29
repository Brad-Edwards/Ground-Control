# Operating Controls (Ground Control as durable home)

This directory is the durable home, inside Ground Control, for operating controls
whose **durable home is Ground Control** in the Autarchy operating-control routing
program. The program's master routing map lives in `penumbra-cell`
(`docs/architecture/operating-controls/`, master tracking issue
[penumbra-cell#34](https://github.com/autarchy-ai/penumbra-cell/issues/34)); that
map only *references* Ground-Control-homed controls by route. The authoritative
record for those controls lives here.

The category pages are the working map:

- [Before source](source.md)

## Control homed here

- **`compliance-requirement-traceability`** (gate: `before-source`, owner route
  `ground-control-grc`). Product behavior must stay traceable to requirement,
  ADR, issue, test, and evidence records for compliance-relevant surfaces. Its
  four sources of truth (Ground Control links, PR traceability, test evidence,
  and assessment evidence index) and the concrete Ground Control mechanisms that
  satisfy each are recorded in [source.md](source.md). Execution issue:
  [Ground-Control#1198](https://github.com/autarchy-ai/Ground-Control/issues/1198).

## Durability

`source.md` and this index are kept present and well-formed by
`tools/policy/check_operating_controls.py`, which runs in `make policy` and the CI
policy job. The check is the structural gate that prevents the durable record from
silently rotting away from the control objective and its named sources of truth.

## Evidence Boundary

This directory records the control route, required shape, and the Ground Control
mechanisms that serve as sources of truth. It does not store raw customer data,
secrets, protected evidence, support artifacts, or live assessment material;
those live in the systems the sources of truth point at (Ground Control links,
pull requests, test evidence, and the assessment evidence index).

# Continuous Compliance Monitoring—preflight note

Scope: GC-I003 (Executable Evidence Collection) and GC-I004 (Continuous
Compliance Monitoring). Companion to ADR-045 §8.

## What we are NOT building

- A new posture aggregate. Posture is a read projection over
  `compliance_drift_event` and the existing
  `ComplianceFrameworkMapping` aggregate (cluster 4 promotion). A
  "compliance score" entity would calcify a transient view into a
  rewritable row—the opposite of the append-only contract ADR-045
  enforces for evidence.
- A new abstraction for "executable evidence." `VerificationResult`
  (ADR-014) is the prover-output evidence record; `ControlTest`
  (ADR-039) is the per-execution control evidence record. Both are
  already first-class internal sources on `EvidenceArtifact`. GC-I003
  adds only two *external* source kinds for evidence that lives
  outside the system: `CI_PIPELINE_RESULT` (CI run URL / key) and
  `SECURITY_SCAN_RESULT` (scanner report key). They follow the
  existing external-mode shape—opaque identifier, never
  dereferenced, length-capped at 500 chars by
  `EvidenceSourceRefDto.@Size`.
- A polling reconciler. The compliance drift detector is event-driven
  (synchronous `@EventListener` on `ControlStateChangedEvent` +
  `EvidenceExpiryEvent`). The only scheduler is the evidence-expiry
  sweep, and it does NOT compute drift—it only publishes
  `EvidenceExpiryEvent` so the detector can decide.
- A new top-level MCP tool. The drift signal stream is read-only at
  the MCP boundary (allowlisted in `GC_QUERY_PATH_ALLOWLIST` for
  `/api/v1/compliance-drift-events`). Publishing a drift event by
  hand from an agent would contaminate the durable signal stream;
  `gc_evidence` + `gc_control` (the underlying state mutations) are
  the only ways drift signals enter.

## Append-only is the load-bearing invariant

GC-I004 wires expiration into evidence. The temptation is to add a
`status` column or a `lastExpiredAt` field that the sweep updates in
place. That would break ADR-045's append-only contract for the same
class of reason §4 already forbids:

- `expiresAt` is set ONCE at create time (or at supersede time for the
  replacement row). The sweep job NEVER writes back to
  `evidence_artifact`. `isExpiredAt(asOf)` is a method, not a
  persisted flag.
- A drift event is the durable artifact of "the system noticed this
  artifact expired". It carries its own `detectedAt`, references the
  source `evidence_artifact_id`, and is itself append-only except for
  the one-shot `acknowledgedAt`.
- Resolutions are new rows (category `RESOLUTION`), not updates to a
  prior `EVIDENCE_EXPIRED` row.

A future code path that bypasses the detector and writes a
"posture_score" cache directly is explicitly out of scope. The graph
projection + framework-mapping read path is the posture surface.

## Synchronous event contract (mirrors `ReassessmentSignalService`)

The detector uses `@EventListener`, NEVER `@TransactionalEventListener`.
The contract documented at
`domain/riskscenarios/service/ReassessmentSignalService` applies
identically here: a separate-transaction listener would let the
publishing service commit while the drift event silently disappears,
exactly the failure mode codex #863 cycle 1 surfaced for the
reassessment substrate. The cluster-wide rule in the architect's
cross-cutting notes pins this for clusters 1, 5, and 6.

The detector is therefore the single observer of these events. If a
second observer wants to react to drift (alerting, GitHub issue
surfacing), it consumes the `compliance_drift_event` table, not a
parallel listener on the source events. Otherwise the system would
have two independent "I saw this" records of the same signal and the
sanitized summary in the drift row would no longer be the
authoritative read.

## Liveness telemetry is non-optional

Continuous monitoring that silently fails is worse than no monitoring:
the dashboard says "compliant" because no new drift events arrived
while the scheduler was actually dead. The detector therefore exposes
`GET /api/v1/compliance-drift-events/liveness`:

- `sampledAt`—when the probe was answered.
- `lastDetectedAt`—most recent drift event for the project.
- `lastSweepAt`—most recent `EvidenceExpirySweepJob.sweep()`
  completion. Null when the job is disabled (the default for test/dev
  per `EvidenceExpirySweepConfig`).
- `lagSeconds`—`sampledAt - lastDetectedAt`, null when no event has
  ever been published.
- `unacknowledgedCount`—drift events with `acknowledgedAt IS NULL`.

The sweep job records `lastSweepAt` on EVERY run (success or per-event
failure), not "last successful sweep"—a job that runs but always
throws inside its listeners is still alive at the scheduler level, and
we want that distinction visible to operators. Per-artifact failures
inside `sweep()` are logged with category + counter; the sweep loop
continues so one bad artifact does not silence the rest.

## SSRF guard for CI / scanner references

`CI_PIPELINE_RESULT` and `SECURITY_SCAN_RESULT` source identifiers may
legitimately look like URLs. The server NEVER dereferences them. They
are stored opaque, presented opaque, and length-capped at 500 chars by
the DTO. If a future feature wants to render rich previews, that is a
client-side concern; the server boundary stays plain text. Any
"resolve this identifier" surface would be a separate ADR with its own
SSRF threat model.

## What lands in this cluster

Cluster `executable-evidence-continuous-monitoring`:

- `EvidenceSourceKind` adds `CI_PIPELINE_RESULT`, `SECURITY_SCAN_RESULT`
  (external-mode entries, validated by the existing switch).
- `EvidenceArtifact` gets `expiresAt` (timestamptz) and
  `validityWindowDays` (positive int) columns + audit-shadow parity
  (Flyway V131 / V132).
- `domain/compliance/` is a new package:
  - `state/ComplianceDriftCategory` + `ComplianceDriftSeverity`.
  - `model/ComplianceDriftEvent` (append-only, audited).
  - `events/EvidenceExpiryEvent`.
  - `repository/ComplianceDriftEventRepository`.
  - `service/ComplianceDriftDetectorService`.
- `infrastructure/compliance/EvidenceExpirySweepJob` and its
  `@ConditionalOnProperty` config alongside `AuditRetentionJob`.
- `api/compliance/ComplianceDriftController` exposing
  list / get / acknowledge / liveness on
  `/api/v1/compliance-drift-events`.
- `gc-query` allowlist + ADR-035 + README mention
  `/api/v1/compliance-drift-events`.
- `lib.js` enum mirrors:
  `COMPLIANCE_DRIFT_CATEGORIES`, `COMPLIANCE_DRIFT_SEVERITIES`,
  extended `EVIDENCE_SOURCE_KINDS`.
- `gc-evidence.js` adapter forwards `expires_at` /
  `validity_window_days` and accepts the new source kinds.
- `frontend/src/types/api.ts` mirrors
  `EvidenceType`, `EvidenceSourceKind`, `ComplianceDriftCategory`,
  `ComplianceDriftSeverity` as union types (ADR-034 enum policy).
- `tools/policy/checks.py::ENUM_CONTRACT_INVENTORY` grows by four
  entries so any future drift fails `make policy`.

## What this preflight explicitly defers

- Posture projection endpoint (cluster 4 framework mapping consumer).
- GitHub issue surfacing of drift events (separate ADR with ADR-029
  sanitization rules).
- Per-framework drift dashboards (UI work, post-cluster).
- Code-change impact detection (the `CODE_CHANGE_IMPACT` category is
  defined so emitters can land later without enum-policy churn, but
  this cluster ships no code-walking listener).

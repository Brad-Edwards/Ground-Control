# ADR-074: Scheduled Evidence Collection Campaigns

## Status

Accepted

## Date

2026-06-29

## Context

`GC-S005` requires the system to schedule evidence collection campaigns with a
configurable frequency (daily, weekly, monthly, quarterly), a scope (which
adapters and which evidence types), and retention policies, and to store the
results as evidence artifacts linked to the relevant controls and frameworks.
Compliance needs evidence collected at regular intervals, not just ad-hoc, so
freshness and completeness do not depend on manual intervention.

Adjacent decisions already cover the surrounding boundaries:

- ADR-045 (GC-M016) owns the first-class `EvidenceArtifact` aggregate and its
  append-only service.
- The evidence collection adapter port (`EvidenceCollectionAdapter`,
  `EvidenceCollectionRequest/Result`) already normalizes one-shot collection
  from external systems into `CreateEvidenceArtifactCommand`s.
- ADR-038 governs `@NotAudited` project scoping on audited aggregates.
- ADR-026/ADR-037 own the shared REST authorization matrix.

The missing piece is the durable recurring directive that ties a frequency, a
scope, a connection profile, target controls, and a retention horizon together,
plus the scheduler that claims due campaigns and the per-execution telemetry.

Likely failure modes without this decision: storing raw secrets on the campaign
instead of a reference; double-running a window when two scheduler ticks race;
unbounded growth of run telemetry; and inventing a second collection path
instead of reusing the existing adapter port and evidence service.

## Decision

### 1. Campaign is a first-class, project-scoped, audited aggregate

`EvidenceCampaign` (`domain/evidence/campaign`) is an `@Audited` aggregate
project-scoped through `Project` (`@NotAudited`, per ADR-038), unique on
`(project_id, uid)`. It carries the frequency, lifecycle status
(`ACTIVE` / `PAUSED`), the adapter name, the scope (`scopeType` +
`scopeCriteria` JSON), a connection profile/endpoint, a `credentialRef`, an
optional set of `targetControlIds`, an optional `retentionDays`, and the
scheduling cursor (`nextRunAt` / `lastRunAt`). The campaign never stores a raw
secret - only the `credentialRef` indirection key resolved at collection time.

### 2. Runs are immutable, un-audited telemetry pruned by retention

Each execution records an `EvidenceCampaignRun` (not Envers-audited), unique on
`(campaign_id, window_start)`, capturing the window, status
(`PENDING/RUNNING/COMPLETED/PARTIAL/FAILED`), artifact and error counts, a
length-bounded `sanitizedError`, and the produced artifact ids. Runs are aged
out per the parent campaign's `retentionDays`; campaigns with no retention keep
runs indefinitely.

### 3. Due campaigns are claimed with an optimistic cursor advance

The scheduled sweep selects `ACTIVE` campaigns whose `nextRunAt <= now`, then
claims each by a conditional update that advances `nextRunAt` past `now` (the
cursor is stepped by `frequency.advance` until it is in the future, so a campaign
that fell behind several periods coalesces into a single `[observed, now]` run
rather than emitting one overlapping window per missed period). The claim is
conditional on `nextRunAt` still equaling the observed cursor **and** the
campaign still being `ACTIVE`: two concurrent ticks both read the same due
cursor but only one conditional update affects a row (the loser skips the
window), and a campaign paused between the due-select and the claim is no longer
`ACTIVE`, so the claim is a no-op and the paused campaign is not executed - pause
is therefore an atomic lifecycle boundary. This mirrors the optimistic
write-once pattern used for evidence-artifact supersede.

### 4. Execution reuses the adapter port and evidence service

Execution invokes the campaign's named `EvidenceCollectionAdapter`, persists
each returned `CreateEvidenceArtifactCommand` through `EvidenceArtifactService`,
and links each produced artifact to every target control with an `EVIDENCED_BY`
`ControlLink`. The campaign's configured `schemaId` is forwarded to the adapter
in the request's `options` bag (the port has no typed schema-selection field) so
the adapter collects to the selected evidence schema. The collection status maps
to the run status (errors present → `PARTIAL`; adapter failure → `FAILED`). An
adapter failure is captured on the run, not propagated, so one campaign's failure
does not abort the sweep. Sweep execution runs outside an ambient transaction:
each run row, artifact persist, and control link commits independently, so a
failure on one campaign or one artifact cannot roll back another's already-recorded
evidence.

### 5. Scheduling is opt-in; campaign writes are admin-only

The scheduler (`infrastructure/campaign`) is gated behind
`groundcontrol.evidence.campaign.enabled=true` with configurable sweep and
prune cron expressions. The REST surface lives at `/api/v1/evidence-campaigns`
(create, list, get, update, pause, resume, trigger, runs). A campaign is a stored
directive to make a credentialed outbound call and ingest the result as evidence,
so every write that configures or enables that collection - create, update (can
change `connectionEndpoint`/`credentialRef`), pause/resume (gates whether the
sweep executes), and the on-demand `POST /{id}/trigger` - requires `ROLE_ADMIN`
in `ApiPathMatrix`. Admin-gating only the trigger would have left the other
writes at the generic authenticated rule, letting a non-admin create or re-point
an `ACTIVE` campaign and have the scheduled sweep perform the credentialed call.
The GET reads (list, get, runs) fall through to the authenticated rule so any
project member can query. The `gc_evidence_campaign` MCP tool exposes the write
actions; reads route through `gc_query`.

### 6. The connection endpoint is SSRF-guarded

Because the runner dereferences `connectionEndpoint` server-side with the
campaign credential attached, `EvidenceEndpointPolicy` validates it at
create/update: the scheme must be http/https, and the host is resolved and
rejected if **any** resolved address is loopback, link-local (including the
`169.254.169.254` cloud-metadata address), site-local/RFC1918, IPv6 unique-local
(`fc00::/7`), wildcard, or multicast. Resolving the host - not just rejecting IP
literals - is what stops an internal DNS name, or a public name that resolves
into private space, from smuggling a forbidden target past the check.

Because a hostname can rebind between create/update and execution, the check is
**re-applied at execution time**: `buildRequest` calls
`EvidenceEndpointPolicy.validateAndResolve` just before handing the request to the
adapter, so a host that rebound to an internal address is rejected then (the run
is recorded `FAILED`, not dispatched), and the validated literal addresses are
**pinned** into `EvidenceConnectionConfig` (`pinnedAddresses` setting). A
conforming adapter connects to a pinned address rather than re-resolving the host,
which closes the residual rebinding window between resolution and the socket; the
original host is retained only for TLS SNI / Host header. The pin is a typed port
contract, not a comment - adapters performing outbound collection must connect to
a pinned address.

### 7. Run telemetry never leaks secrets

`EvidenceCampaignRun.sanitizedError` is readable by any project member, while
adapter messages and exception text are provider- or attacker-influenced. The run
therefore stores a controlled error code/category and a redacted, length-bounded
summary (`EvidenceRunErrorRedactor` strips URL userinfo, `Bearer` tokens,
`key=value` secret pairs, and long opaque token runs); raw exception detail is
kept out of the summary entirely (only the exception category is recorded).

## Consequences

- Evidence freshness is achievable without manual intervention, with auditable
  campaign history and bounded run telemetry.
- No raw secret is ever stored, logged, or returned - only `credentialRef`.
- Concurrent schedulers cannot double-run a window.
- The feature adds no new collection path: it composes the existing adapter port
  and evidence service.

# ADR-056: Distributional Cost of Delay and WSJF Prioritization

## Status

Accepted

## Date

2026-05-30

## Context

GC-W003 requires Cost-of-Delay (CoD) estimation and WSJF (Weighted Shortest
Job First) prioritization for product backlog items. The naive form of WSJF
collapses each component (user-business value, time criticality, risk
reduction / opportunity enablement, and job duration) to a point estimate and
divides. That arithmetic produces false certainty: two items whose components
overlap heavily end up with deterministic-looking WSJF scores that imply a
ranking the underlying estimates do not support, and a small shift in any
component flips the ranking with no audit trail.

The cluster preflight identifies two additional concerns:

- A non-deterministic sampler would make WSJF non-reproducible; a reviewer
  cannot re-run the same computation to confirm a result, defeating the
  audit-trail requirement that GC-W011 adds for decision records.
- A bespoke RNG path would duplicate the seeded Monte Carlo concern that the
  FAIR-quantitative cluster also needs. A single helper reused across
  analyses keeps reproducibility semantics aligned.

## Decision

### 1. CoD components are probability distributions, not point estimates

Each of the four CoD components is modelled as a `CostOfDelayComponent` with a
{@code DistributionKind} (`POINT`, `UNIFORM`, `TRIANGULAR`), and a strictly
positive numeric domain. WSJF is computed as a distribution: paired draws of
the four components across N seeded Monte Carlo iterations, with the result
expressed as raw samples plus mean, median, p10, and p90 summary statistics.
Re-prioritization analysis compares two snapshots and emits per-item rank
deltas plus a probability-of-dominance helper for flagging statistically
indistinguishable pairs.

### 2. Sampling is deterministic by seed

All sampling routes through a `SeededMonteCarlo` helper at
`domain/grcanalysis/util/`, backed by `SplittableRandom`. Callers supply an explicit
`seed` and `iterations`; the same `(seed, iterations, component vector)`
tuple produces identical output across processes. The
seed is surfaced in the WSJF response envelope so a reviewer can re-run
locally and verify.

### 3. BacklogItem owns its CoD components; WSJF is computed on demand

The `BacklogItem` aggregate persists the four CoD components plus
status and metadata. WSJF distributions are not persisted on the item;
`WsjfAnalysisService` computes them on demand from the current
component estimates so a re-prioritization analysis always reflects the
latest inputs. Persisted snapshots, if needed for retrospective analysis,
flow through `DecisionAnalysisRecord` (ADR-057) which is the audit
substrate for "what did we decide and why."

### 4. The analysis response shape carries methodology attribution

The `WsjfDistributionResponse` envelope follows ADR-035: it carries
`analysisKind = "wsjf"`, `scale = "dimensionless"`, `units = "value-per-week"`,
an explicit `limitations` field, and the seed,
iterations, raw samples, and summary statistics. A caller seeing only the mean
cannot mistake it for a methodology-neutral score.

## Consequences

- Reviewers can re-run any WSJF computation by replaying the persisted CoD
  components with the surfaced seed and iterations.
- Two backlog items whose distributions overlap >20% surface that overlap to
  the ranking-delta consumer rather than producing arbitrary ties.
- The seeded Monte Carlo helper becomes the single reproducibility primitive
  for any future distributional analysis (FAIR LEC, MCDA outputs), avoiding
  parallel RNG implementations.

## Alternatives considered

- **Point estimates with epsilon ranking.** Rejected because it preserves
  false-certainty without producing audit-quality output.
- **Persisting WSJF distributions on BacklogItem.** Rejected because every
  CoD edit would otherwise need a transactional sample-and-store flush;
  expensive, and decouples the displayed score from the stored components.
- **A bespoke RNG.** Rejected because the FAIR cluster needs the same
  primitive; one shared helper is cheaper than two parallel ones.

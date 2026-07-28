# ADR-091: CI Verification Topology

## Status

Accepted

## Date

2026-07-28

## Context

The CI workflow ran its verification jobs as a chain. `policy` gated `build`,
`trivy`, `osv-scanner`, and `mcp-contract`; `build` gated `test`; `test` gated
`integration`, `sonar`, and `verify`. Measured over the 40 most recent
`pull_request` runs, whole-run wall clock had a median of 15.0 minutes and a p95
of 23.9 minutes, and time to first failing check had a median of 4.9 minutes and
a p95 of 21.5 minutes.

Per-job medians showed where the time went. `policy` started at t=0 and took 1.8
minutes. `build` started at +1.9 minutes and took 4.4. `test` started at +6.5 and
took 6.0. `sonar` started at +12.3 and took 7.6, making it both the longest job
and the last to finish.

None of those `needs:` edges carried data. No job downloaded an artifact from a
predecessor. Every Gradle job started from a fresh checkout and rebuilt from
source, so each edge only delayed a job that was already able to run. The
`sonar` job generated its own JaCoCo report through `./gradlew build sonar`, so
its dependency on `test` bought nothing. The `integrationTest` task declares
`shouldRunAfter(tasks.test)`, which orders tasks inside a single Gradle
invocation and says nothing about ordering separate CI jobs.

Branch protection on `main` and `dev` requires eight GitHub Actions contexts
(`build`, `test`, `integration`, `verify`, `sonar`, `policy`, `trivy`,
`osv-scanner`) plus two external app contexts, under `strict: true`. Any change
to the job graph has to preserve every one of those names.

## Decision

**Verification jobs declare no dependencies on each other.** `policy`, `build`,
`test`, `integration`, `verify`, `sonar`, `trivy`, `osv-scanner`, and
`mcp-contract` all start at t=0. Whole-run wall clock becomes the duration of
the slowest single job instead of the sum of the longest chain.

Three edges remain, each for a reason a flat graph cannot express:

- `policy-live` needs `policy`. It is the only job that sees
  `GROUND_CONTROL_API_TOKEN`, it runs on a self-hosted runner, and it is
  restricted to `main`. Sequencing it behind the repo policy gate keeps the
  secret-bearing job downstream of policy verification.
- `docker` needs every verification job. Publishing an image is the one
  irreversible side effect in the workflow.
- `smoke` needs `docker`. It exercises the image `docker` publishes.

**The `docker` gate list is exhaustive rather than transitive.** Before this
decision `docker` named four jobs and inherited the rest through the chain. A
flat graph has no transitivity to inherit, so every gate is written out.
`trivy` and `osv-scanner` join the list; they were never in the old transitive
closure, which allowed an image to be published while a security scan was red.

**Sonar runs only the work that feeds Sonar.** The `sonar` job generates
coverage with `./gradlew test jacocoTestReport` and then analyzes with
`./gradlew sonar -Dsonar.qualitygate.wait=true`. Sonar consumes two inputs,
both declared in `backend/build.gradle.kts`: `sonar.java.binaries` and
`sonar.coverage.jacoco.xmlReportPaths`. No SpotBugs or Checkstyle report path is
wired into the Sonar properties, so assembling the boot jar and re-running
static analysis inside the longest job bought nothing. The `build` job still
runs `./gradlew build -x test` and the `test` job still runs the full
`./gradlew check`, including the 80 percent JaCoCo line-coverage verification.
The two invocations are separate because the `sonar` task has no ordering
relation to the `finalizedBy` JaCoCo report, and analyzing before the report is
written would report zero coverage.

**A `fast-feedback` job reports formatting and compilation errors early, and is
never a merge gate.** It runs
`./gradlew spotlessCheck compileJava compileTestJava -Pquick` with no
dependencies. It stays out of the required-context set: a second required gate
covering a subset of an existing gate is a shadow merge authority, and the
complete suite remains the single authority.

**No test sharding.** `test` is 6.0 minutes and `integration` is 5.5 minutes at
the median, both shorter than `sonar`. Sharding either one cannot reduce wall
clock while another job is longer, and it would require merging shard coverage
back into the single JaCoCo XML that Sonar reads. Re-evaluate when a single test
lane becomes the longest job in the graph.

**No path filtering on required checks.** A workflow-level `paths` filter stops
the workflow from running, so the required context never reports and the pull
request stays blocked. Only a job-level `if` produces the `skipped` conclusion
that satisfies a required check. With eight required contexts under
`strict: true`, the failure mode is a pull request that can never merge. The
additive `fast-feedback` lane delivers early signal without touching
required-check semantics.

**Gradle caching is unchanged.** `gradle/actions/setup-gradle` caches
`caches/build-cache-1` and dependency state, defaults to read-only on
non-default branches, and keys entries by job id. Parallel jobs restore the
`dev` entry for their own job and do not contend on writes. Pre-commit hook
environments are cached separately in the `policy` job, keyed on the hash of
`.pre-commit-config.yaml` so a pinning change always misses the cache.

**Structural invariants are executable.** `tools/tests/test_ci_topology.py`
asserts that every required context has a job, that the baseline matches
`CI_STRICTNESS_REQUIRED_CONTEXTS`, that no verification job declares a
dependency, that the surviving edges match their documented sets exactly, that
`docker` names every job outside `DOCKER_GATE_EXCLUSIONS`, that the fast lane
exists and is not required, and that the `sonar` job keeps its coverage and
quality-gate inputs. These run in `make policy` and in the CI `policy` job.

**The CI gate watches every run for the head commit.** `gc_watch_ci_run`
previously watched whichever workflow run was created most recently on the
branch, which is not reliably the run carrying the required contexts. It now
groups runs by head SHA and reports success only when all of them succeed. The
contract lives in the ADR-027 2026-07-28 amendment. Without it, a readiness
record could attest to a green CI gate on the strength of a five-second title
lint.

## Consequences

Whole-run wall clock is bounded by the slowest job rather than the longest
chain. Compute cost rises: `build` and `test` compile the same sources
concurrently instead of in sequence. That duplication existed before this
decision, since `sonar` already rebuilt everything; the change moves it off the
critical path rather than adding it.

Every future check is a job with no `needs`, and the only edge to maintain is
`docker`'s gate list. Omitting a job from that list would let an image publish
past a red check, so `test_ci_topology.py` derives the expected list from the
workflow's own job set rather than from the required-context set. A required
context is not the same thing as a gate: `mcp-contract` gates the image publish
without being required for merge. A new job must therefore be added to
`docker.needs` or excused in `DOCKER_GATE_EXCLUSIONS` with a reason.
`policy-live` is excused because GitHub skips a job whose `needs` entry was
skipped, so gating the publish on a job that is skipped on every ref but `main`
would stop it publishing at all.

Adding a required context now means three coordinated edits: the job in
`ci.yml`, the context in `CI_STRICTNESS_REQUIRED_CONTEXTS`, and the context in
`.github/branch-protection-baseline.json`. The topology tests fail until all
three agree, which is what makes a phantom required context impossible to leave
behind.

`make ci-timings` reports the same metrics from workflow metadata, so the next
comparison is a measurement rather than a redesign.

## Non-Goals

This decision does not change what CI verifies. No job is renamed, removed, or
made advisory, no severity threshold moves, and no scanner becomes
non-blocking. It does not introduce a second source of CI truth outside GitHub
Actions, a reusable-workflow abstraction, or a new Gradle task. It does not add
frontend verification, which the workflow still lacks.

## Related Issues

Issue #1461.

## Related ADRs

ADR-054 owns the documentation-coverage gate that the `policy` job runs.
ADR-063 and GC-P027 own the release model that `docker` and `release-please.yml`
implement.

# CI pipeline

Reference for `.github/workflows/ci.yml`. The contract behind it is ADR-091.

## Job graph

Every verification job starts at t=0. None of them consumes another job's
artifact, so whole-run wall clock is the duration of the slowest job.

```
t=0  fast-feedback  policy  build  test  integration  verify  sonar  trivy  osv-scanner  mcp-contract
                      |                                                                       |
                      +-- policy-live (main only)                                             |
                                                                                              |
     docker (push to main/dev only) <-- policy, build, test, integration, verify, sonar, mcp-contract, trivy, osv-scanner
       |
     smoke
```

`tools/tests/test_ci_topology.py` enforces this shape. It fails if a
verification job gains a dependency, if `docker` stops naming a gate, if a
required context loses its job, or if the fast lane becomes required.

## Jobs

| Job | Required | What it verifies | Reproduce locally |
|---|---|---|---|
| `fast-feedback` | no | Spotless formatting, main and test compilation | `cd backend && ./gradlew spotlessCheck compileJava compileTestJava -Pquick` |
| `policy` | yes | Pre-commit hygiene and secret scan, policy tool tests, MCP server tests, repo policy checks, Vale prose lint | `make policy` |
| `build` | yes | Assembly, Checkstyle, SpotBugs, Spotless | `cd backend && ./gradlew build -x test && ./gradlew spotlessCheck` |
| `test` | yes | Unit tests, static analysis, 80 percent JaCoCo line coverage | `make check` |
| `integration` | yes | Testcontainers integration tests | `make integration` |
| `verify` | yes | OpenJML extended static checking | `cd backend && ./gradlew openjmlEsc` |
| `sonar` | yes | Coverage generation, SonarCloud analysis, quality gate, new-issue gate | `cd backend && ./gradlew test jacocoTestReport` then `./gradlew sonar` with `SONAR_TOKEN` set |
| `trivy` | yes | Image vulnerabilities, secrets, IaC misconfiguration | `docker build -f backend/Dockerfile -t gc-trivy:ci .` then `trivy image gc-trivy:ci` |
| `osv-scanner` | yes | Gradle lockfile CVEs, lockfile drift | `cd backend && ./gradlew dependencies` then `osv-scanner --lockfile=backend/gradle.lockfile` |
| `mcp-contract` | no | Contract artifact regeneration, OpenAPI breaking changes, MCP write-contract drift | `make mcp-openapi-contract` and `make contract-breaking` |
| `policy-live` | no | Live Ground Control ADR and policy drift | `make policy-live` with `GC_BASE_URL` set |
| `docker` | no | Image publish to GHCR | `make docker-build` |
| `smoke` | no | Image starts, Flyway migrates, health reports UP | `make smoke` |

Two more required contexts come from external apps rather than this workflow:
`SonarCloud Code Analysis` and `GitGuardian Security Checks`.

The required set lives in three places that must agree: the job in `ci.yml`,
`CI_STRICTNESS_REQUIRED_CONTEXTS` in `tools/policy/checks.py`, and
`.github/branch-protection-baseline.json`. Adding or removing a required check
means editing all three; the topology tests fail until they match.

## Fast lane and full gate

`fast-feedback` reports formatting and compilation errors before any full lane
finishes. It is advisory: it is not in the required-context set, and it never
substitutes for a required check. The complete suite is the only merge
authority.

Required checks are not path-filtered. A workflow-level `paths` filter stops the
workflow from running, so a required context never reports and the pull request
stays blocked. See ADR-091 for the full reasoning.

## Test lane ownership and sharding

`test` owns unit tests, which are every test not tagged `integration` or `age`.
`integration` owns Testcontainers tests, which are the classes extending
`BaseIntegrationTest` or otherwise tagged `integration`. `sonar` runs the unit
lane again to produce the coverage XML it analyzes. `ageTest` covers Apache AGE
tests and does not run in CI.

Neither test lane is sharded. Both are shorter than `sonar`, so splitting them
cannot reduce wall clock, and shard coverage would have to be merged back into
the single JaCoCo XML that Sonar reads. Shard when a single test lane becomes
the longest job in the graph.

## Measuring

`make ci-timings` reports median and p95 for whole-run wall clock and time to
first failing check, plus per-job duration and start offset, from the GitHub
Actions API. A job whose median start offset is far from zero is waiting on
something.

```
make ci-timings
python3 tools/ci/measure_ci_timings.py --limit 40 --event pull_request --json
python3 tools/ci/measure_ci_timings.py --branch <branch> --limit 10
```

Pass `--branch` when measuring a topology change before it reaches `dev`.
Without it the sample mixes the branch under test with historical runs of the
topology it replaces, which understates the difference.

## Recorded timings

The before sample covers the 40 most recent `pull_request` runs of `ci.yml` on
the serial graph. The after sample covers the pull request that introduced the
flat graph (issue #1461, `--branch 1461-ci-fast-feedback`), so it is 2 runs
rather than 40. Re-measure with `make ci-timings` once the flat graph has
accumulated history on `dev`.

| Metric | Before (serial graph, n=40) | After (flat graph, n=2) |
|---|---|---|
| Whole-run wall clock, median | 15.0m | 6.2m |
| Whole-run wall clock, p95 | 23.9m | 6.2m |
| Time to first failing check, median | 4.9m | 1.9m |

The one green run in the after sample finished in 6.1m, with `sonar` the last
job to complete. Under the serial graph `sonar` alone did not start until
+12.3m.

Per-job medians in the before sample, with start offset from run start:

| Job | Median | p95 | Starts at |
|---|---|---|---|
| `policy` | 1.8m | 2.0m | +0.0m |
| `trivy` | 4.2m | 6.5m | +1.9m |
| `osv-scanner` | 0.9m | 1.3m | +1.9m |
| `mcp-contract` | 3.0m | 3.8m | +1.9m |
| `build` | 4.4m | 5.4m | +1.9m |
| `test` | 6.0m | 6.9m | +6.5m |
| `integration` | 5.5m | 6.3m | +12.3m |
| `verify` | 2.0m | 2.6m | +12.3m |
| `sonar` | 7.6m | 9.0m | +12.3m |

The critical path was `policy`, `build`, `test`, `sonar`. Under the flat graph
every job starts within 6 seconds of run start, so the longest job bounds the
run. `sonar` also dropped from 7.6m to 5.9m once it stopped assembling the boot
jar and re-running static analysis it does not consume.

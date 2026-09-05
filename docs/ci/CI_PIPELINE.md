# CI pipeline

Reference for the GitHub Actions workflows in `.github/workflows/`. The contract
behind the verification topology is
[ADR-091](../../architecture/adrs/091-ci-verification-topology.md).

Ground Control is the MCP server for the `/implement` workflow over repo-local
files (issue #1500). There is no backend, database, or frontend, so there is no
compile lane, no Testcontainers lane, no coverage-producing Gradle build, and no
image publish. The verification surface is the MCP `node --test` suite, the
repo-native policy checks, Vale prose linting, SonarCloud, and two dependency and
secret scanners.

## Verification jobs

Every verification job starts at t=0 and none consumes another job's artifact, so
whole-run wall clock is the duration of the slowest job. Each job id below is the
required-status-check context name.

| Job | Workflow | Required | What it verifies | Reproduce locally |
|---|---|---|---|---|
| `policy` | `ci.yml` | yes | Pre-commit file hygiene and the gitleaks secret scan, the Python policy tool tests, the MCP `node --test` suite, MCP ESLint, `bin/policy`, and Vale on changed docs | `make policy` and `make mcp-test` |
| `sonar` | `sonarcloud.yml` | yes | JavaScript coverage through `c8`, Python coverage through `coverage.py`, SonarCloud analysis, the hosted quality gate, and the zero-open-issues gate | `npx c8 --reporter=lcovonly npm test` in `mcp/ground-control`, then `python3 tools/sonar/assert_no_new_issues.py --project-key autarchy-ai_Ground-Control` with `SONAR_TOKEN` set |
| `trivy` | `security.yml` | yes | Filesystem scan for CRITICAL and HIGH vulnerabilities and for secrets, failing the job on any fixable finding | `trivy fs --scanners vuln,secret --severity CRITICAL,HIGH --ignore-unfixed .` |
| `osv-scanner` | `security.yml` | yes | Known vulnerabilities in the Node and Python dependency manifests, configured by `osv-scanner.toml` | `osv-scanner scan source --recursive --config=osv-scanner.toml .` |

Two further required contexts are produced outside this repository's workflow
files: `SonarCloud Code Analysis`, posted by the SonarQube scan action with the
quality-gate result, and `GitGuardian Security Checks`, posted by the GitGuardian
app. `.github/branch-protection-baseline.json` records the required set for
`main` and `dev` with strict status checks and admin bypass retained.

That baseline is enforced, not just documented. `run_ci_required_context_contract`
in `tools/policy/ci_strictness.py` (GC-P030, ADR-091) checks it two ways on every
`make policy` and CI `policy` run: every context in
`CI_STRICTNESS_REQUIRED_CONTEXTS` must be produced by a job in a
pull-request-triggered workflow **that runs for that protected branch**, and the
baseline's context set must match that declaration exactly in both directions.
The branch half matters because a `pull_request` trigger filtered to one branch
never runs for the other, so a check can exist and still never report on `main`. The two hosted-app contexts above are the
only exemptions from needing a local producer, and that allowlist is shrink-only.
Adding or removing a required check therefore means editing the declaration, the
baseline, and the workflow together; the gate fails until they agree.

The `policy` job fetches PR comments in a token-bearing step and then runs
PR-head policy code without `GH_TOKEN`, passing `--pr-comments-json` and
`--pr-number` so the gate can read the PR-thread marker without exposing a token
to code from the pull request head. On push events it runs `bin/policy
--skip-pr-body` instead, because there is no PR body to check.

Vale runs only on pull requests. A push event has no base ref to diff against,
and a document reaches `main` only through a pull request, so the on-PR pass is
the authoritative prose gate.

Required checks are not path-filtered. A workflow-level `paths` filter stops the
workflow from running, so a required context never reports and the pull request
stays blocked forever. ADR-091 carries the full reasoning.

## Release and repository workflows

These are not verification gates and are not in the required-context set.

| Workflow | Trigger | What it does |
|---|---|---|
| `pr-title.yml` | pull request to `main` or `dev` | Enforces a Conventional Commit title with a single type, an optional scope, and a lowercase-leading subject. Release Please parses merged history, so the title is load-bearing. The `/implement` skill validates the same allow-list locally at Step 9. |
| `release-please.yml` | push to `main` | Maintains the `chore(main): release X.Y.Z` pull request, regenerates `CHANGELOG.md` from Conventional Commit history, and cuts the tag and GitHub Release when that pull request merges. There is no image to publish. |
| `sync-main-to-dev.yml` | after a release lands on `main` | Opens the `main` to `dev` back-merge pull request from a dedicated automation branch. A human merges it. |

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

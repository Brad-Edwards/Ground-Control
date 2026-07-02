---
name: repo-setup
description: Set up branch protection for a GitHub repo with main + dev branching strategy. Creates dev branch if needed and configures protection rules via GitHub API. Also sets up pre-commit hooks and SonarQube integration.
---

# Repo Setup

Configure branch protection, pre-commit hooks, and SonarQube for a repository.

## Branch Strategy

- **main**: Production. Only accepts PRs (from dev). No force push. No direct push.
- **dev**: Integration. Requires PRs. Owner can force push. No direct push.
- **Feature branches**: PRs target `dev`. `dev` PRs target `main`.

## Steps

### 1. Branch Protection

1. Check if `dev` branch exists. If not, create it from `main` and push.

2. Set **main** branch protection via the GitHub API:
```bash
gh api repos/{owner}/{repo}/branches/main/protection -X PUT --input - <<'EOF'
{
  "required_pull_request_reviews": {
    "required_approving_review_count": 0,
    "dismiss_stale_reviews": true
  },
  "enforce_admins": false,
  "required_status_checks": null,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
EOF
```

3. Set **dev** branch protection:
```bash
gh api repos/{owner}/{repo}/branches/dev/protection -X PUT --input - <<'EOF'
{
  "required_pull_request_reviews": {
    "required_approving_review_count": 0,
    "dismiss_stale_reviews": false
  },
  "enforce_admins": false,
  "required_status_checks": null,
  "restrictions": null,
  "allow_force_pushes": true,
  "allow_deletions": false
}
EOF
```

4. Confirm settings by reading back both branch protections.

### 2. Pre-commit Setup

Install pre-commit and generate a `.pre-commit-config.yaml` tailored to the repo.

1. Install pre-commit if not already installed:
```bash
pip install pre-commit
```

2. Analyze the repo to determine which languages, frameworks, and tools are in use (check file extensions, package files, Dockerfiles, IaC configs, etc.).

3. Generate a `.pre-commit-config.yaml` with `fail_fast: true`, organized into staged categories. Select hooks from the categories below based on what the repo actually uses. **Do not add hooks for languages/tools not present in the repo.**

#### Stage 1: Fast checks (linting, formatting, syntax)

Always include the general file checks:
```yaml
- repo: https://github.com/pre-commit/pre-commit-hooks
  rev: v6.0.0  # check for latest
  hooks:
    - id: trailing-whitespace
    - id: end-of-file-fixer
    - id: check-yaml
      args: [--unsafe]
    - id: check-json
    - id: check-added-large-files
      args: [--maxkb=500]
    - id: check-merge-conflict
    - id: detect-private-key
      exclude: ^tests/
```

Then add language-specific linting/formatting as applicable:

- **Python**: ruff (lint + format), black, isort, mypy
- **TypeScript/JavaScript**: biome, eslint, prettier (use local hooks with project's tooling, for example `bunx`, `npx`)
- **Go**: gofmt, golangci-lint
- **Rust**: rustfmt, clippy
- **Terraform/OpenTofu**: terraform_fmt, terraform_validate (via `pre-commit-terraform`)
- **Shell**: shellcheck, shfmt
- **YAML/TOML/Markdown**: yamllint, taplo, markdownlint

#### Stage 2: Security (SAST/secrets)

Add based on what's relevant:

- **Secrets detection**: gitleaks or detect-private-key (always recommended)
- **Dependency audit**: language-appropriate audit command (for example `pip audit`, `bun audit`, `npm audit`, `go vuln`)
- **IaC security**: checkov for Terraform/Dockerfile (via `bridgecrewio/checkov`)
- **Python security**: bandit
- **Container security**: hadolint for Dockerfiles

#### Stage 3: Type checking

- **Python**: mypy or pyright
- **TypeScript**: tsc --noEmit (local hook)

#### Stage 4: Tests (slowest - run last)

- Run the project's test suite as a local hook (for example `pytest`, `bun test`, `go test ./...`)
- Use `pass_filenames: false` for test runners
- Scope with appropriate `files:` patterns

4. Activate **and verify** the hooks with the repo-native installer rather than a bare `pre-commit install`:
```bash
scripts/install-hooks.sh   # or: make hooks
```

   **Do not run `pre-commit install` directly.** It cowardly refuses whenever git's `core.hooksPath` is set (any value), so under an environment-level global hooks dispatcher (for example `~/.git-hooks` with a `_chain` script) it silently produces **no** commit-time hook: the repo gets a valid `.pre-commit-config.yaml` and a green CI pre-commit job while commits run zero hooks. Commit-time activation is a **separate contract** from config presence and CI execution (ADR-079).

   `scripts/install-hooks.sh` is the durable, repo-native installer/verifier. For the current clone it:
   - writes GC-managed `pre-commit`/`pre-push` hooks into the clone-local hook path the dispatcher delegates to (`$(git rev-parse --git-dir)/hooks`), which is what `pre-commit install` cannot do while `core.hooksPath` is set;
   - **never touches global git config** and never overwrites an unmanaged or symlinked hook without `--force` (host ownership boundary);
   - **proves** git's effective dispatch actually reaches the managed hook (via `git hook run`) instead of trusting config presence, and **fails closed** with an actionable diagnostic when a non-empty `core.hooksPath` points at a dispatcher that does not delegate to the clone-local hook path;
   - finally runs `pre-commit run --all-files`.

   Because `.git/hooks/` is not versioned, every fresh clone must re-run `make hooks`; document that in the onboarded repo's CONTRIBUTING/README. It's normal for formatters to make changes on the first `pre-commit run`, so stage those changes.

### 3. SonarCloud Setup

Set up SonarCloud integration for continuous code quality analysis. Credentials are expected in environment variables.

1. Check for SonarCloud credentials in the environment:
   - `SONAR_TOKEN` - authentication token (generate at https://sonarcloud.io/account/security)
   - `SONAR_ORGANIZATION` - organization key (visible in SonarCloud project settings)

2. If credentials are missing, prompt the user to set them up. Do not hardcode credentials.

3. Create a `sonar-project.properties` file in the repo root. Analyze the repo to determine:
   - `sonar.projectKey` - derive from `{org}_{repo-name}`
   - `sonar.organization` - from env or ask user
   - `sonar.sources` - source directories (for example `src`, `lib`, `app`)
   - `sonar.tests` - test directories (for example `tests`, `test`, `__tests__`)
   - `sonar.exclusions` - common exclusions (node_modules, .venv, build artifacts, vendor, etc.)
   - Language-specific properties:
     - **Python**: `sonar.python.version`, coverage report paths
     - **TypeScript/JavaScript**: `sonar.javascript.lcov.reportPaths`
     - **Go**: `sonar.go.coverage.reportPaths`
     - **Java**: `sonar.java.binaries`

4. Add a GitHub Actions workflow (`.github/workflows/sonarcloud.yml`) if one doesn't exist:
```yaml
name: SonarCloud Analysis
on:
  push:
    branches: [main, dev]
  pull_request:
    branches: [main, dev]
jobs:
  sonarcloud:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: SonarCloud Scan
        uses: SonarSource/sonarcloud-github-action@v5
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
```

5. Remind the user to add `SONAR_TOKEN` as a GitHub repository secret.

## Key Decisions

- **0 required approvals**: Allows solo developers to merge their own PRs. GitHub prevents PR authors from approving their own PRs, so any non-zero count blocks solo workflows.
- **enforce_admins: false**: Owner can bypass in emergencies.
- **dismiss_stale_reviews: true on main**: Prevents merging outdated approvals after new pushes.
- **allow_force_pushes on dev only**: Lets owner rebase/clean up dev, but main history is immutable.
- **fail_fast: true for pre-commit**: Stops on first failure to give fast feedback.
- **Staged hook ordering**: Fast checks first (formatting/linting), then security, then type checking, then tests. This gives the quickest feedback loop.
- **Language-adaptive hooks**: The config must be tailored to the repo. Analyze the repo first and only add hooks for tools/languages actually present.
- **Local hooks for project tooling**: Use `repo: local` with the project's own toolchain (bun, npm, poetry, etc.) rather than duplicating tool installations through pre-commit's environments.

## Notes

- Requires `gh` CLI authenticated with repo admin permissions.
- Use the legacy branch protection API (`/branches/{branch}/protection`), not the rulesets API - the rulesets API has schema issues with `pull_request` rule type.
- For public repos on free plans, branch protection works. For private repos, requires GitHub Pro/Team.
- Pre-commit hook versions should be checked for latest at time of setup (check PyPI / GitHub releases).
- SonarCloud credentials must never be committed to the repo. Always use environment variables or GitHub secrets.
- SonarCloud is free for open source projects. The GitHub Action (`SonarSource/sonarcloud-github-action`) handles the scanner installation automatically - no need to configure `SONAR_HOST_URL`.

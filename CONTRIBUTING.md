# Contributing to Ground Control

## Getting Started

### Prerequisites

- Java 21 (Eclipse Temurin recommended)
- Docker Engine 24+ and Docker Compose v2
- Gradle 8.x (via included wrapper - no manual install needed)

### Local Development Setup

```bash
# 1. Clone and branch
git clone https://github.com/KeplerOps/Ground-Control.git
cd Ground-Control
git checkout -b feature/your-feature dev

# 2. Activate commit-time hooks for THIS clone (required once per clone, ADR-079)
make hooks                         # writes + verifies the pre-commit/pre-push hooks

# 3. Start PostgreSQL and Redis
cp .env.example .env
make up

# 4. Build and test
make rapid                         # Format + compile (~1s with warm daemon)
make test                          # Unit tests

# 5. Start development server
make dev                           # Spring Boot on :8000
```

### Makefile Targets

| Target | Description |
|--------|-------------|
| `make hooks` | Activate + verify commit-time pre-commit hooks for this clone (run once per clone) |
| `make rapid` | Format + compile, no tests or static analysis (~1 second warm) |
| `make test` | Run unit tests (no static analysis) |
| `make check` | Full build + tests + static analysis + coverage (CI-equivalent) |
| `make verify` | check + integration tests + OpenJML ESC |
| `make format` | Format code with Spotless |
| `make lint` | Check formatting |
| `make integration` | Integration tests (Testcontainers) |
| `make dev` | Start Spring Boot development server |
| `make up` | Start Docker Compose services (PostgreSQL, Redis) |
| `make down` | Stop Docker Compose services |
| `make clean` | Remove build artifacts |

Use `make rapid` for the inner dev loop. Use `make check` before pushing.

## Branch Strategy

- `main` - production-ready, protected
- `dev` - integration branch, all PRs target this
- `feature/*` - feature branches, branched from `dev`

## Coding Standards

Read [`docs/CODING_STANDARDS.md`](docs/CODING_STANDARDS.md) for the complete reference. Key points below.

### Java Backend

| Tool | Purpose | Command |
|------|---------|---------|
| Spotless + Palantir | Formatting | `cd backend && ./gradlew spotlessApply` |
| Error Prone | Compile-time bug detection | Runs as part of `./gradlew check` |
| SpotBugs | Static analysis | Runs as part of `./gradlew check` |
| Checkstyle | Naming/coding patterns | Runs as part of `./gradlew check` |
| JaCoCo | Test coverage | `cd backend && ./gradlew jacocoTestReport` |

- **Records for DTOs**: Use Java records for command objects and API request/response types
- **No `var` abuse**: Use `var` only when the type is obvious from the right-hand side
- **Domain layer purity**: No Spring web imports in `domain/` (enforced by ArchUnit)

### Naming Conventions (Java)

| Element | Convention | Example |
|---------|-----------|---------|
| Packages | `lowercase` | `requirements.service` |
| Classes | `PascalCase` | `RequirementService` |
| Methods | `camelCase` | `createRelation()` |
| Constants | `UPPER_SNAKE_CASE` | `MAX_RETRY_COUNT` |
| Enums | `UPPER_SNAKE_CASE` | `DEPENDS_ON` |

## Architecture Rules

The dependency rule is enforced by ArchUnit in CI:

```
api/ -> domain/ <- infrastructure/
```

- `domain/` has **zero** Spring web imports (no controllers, no HTTP)
- `api/` depends on `domain/` - never imports `infrastructure/`
- `infrastructure/` implements interfaces defined in `domain/`

Enforced by ArchUnit tests in `ArchitectureTest.java`.

## Commit Messages

- Imperative mood: `Add risk scoring engine` not `Added risk scoring engine`
- Source-changing commits add a fragment under `changelog.d/<issue>.<type>.md` (or `+<slug>.<type>.md` for issue-free entries); release-time `towncrier build` collates fragments into `CHANGELOG.md`. CI-only and docs-only changes may ship without a fragment; refactors under application source still file a fragment. Do not edit `CHANGELOG.md` directly. See [`changelog.d/README.md`](changelog.d/README.md).

## Pull Requests

- Target `dev`, not `main`
- PRs require passing CI (build + tests + static analysis + ArchUnit)
- No coverage regression
- Use the [PR template](.github/PULL_REQUEST_TEMPLATE.md)

## Testing

```
backend/src/test/java/com/keplerops/groundcontrol/
├── unit/domain/   # Domain logic only. No DB, no Spring context. JUnit 5 + Mockito.
├── integration/   # With real PostgreSQL (Testcontainers). Spring Boot test.
│                  # Tags: @Tag("integration") for standard, @Tag("age") for AGE
└── architecture/  # ArchUnit rules (layer enforcement, naming).
```

- **JUnit 5** for unit and integration tests
- **jqwik** for property-based testing (state machines, enums) - tagged `@Tag("slow")`
- **Testcontainers** for integration tests (PostgreSQL 16, Apache AGE)
- **ArchUnit** for architecture rule enforcement
- **Repo policy checks** via `make policy` for ADR/workflow parity, controller docs/MCP parity, migration companion updates, and PR body requirements
- Test names describe behavior: `archiveFromDraftFails`, not `testArchiveMethod`
- Tests are independent - no shared mutable state (except ordered E2E tests using `@TestInstance(PER_CLASS)`)

# ADR-004: Code Quality Toolchain

## Status

Superseded by [ADR-013](013-java-spring-boot-rewrite.md)

## Date

2026-03-08

## Context

The project needs automated code quality enforcement. The toolchain is already configured and running in CI and pre-commit hooks.

## Decision

- **Ruff** for linting and formatting (replaces flake8, isort, black, pyupgrade, bandit)
  - Line length: 100
  - Rule sets: pycodestyle, pyflakes, isort, pep8-naming, bugbear, annotations, bandit, pydocstyle, pyupgrade, ruff-specific
  - Google-style docstrings
- **mypy** in strict mode with django-stubs plugin
- **pre-commit** hooks (ruff, mypy, gitleaks, pytest, trailing whitespace, YAML/JSON/TOML checks)
  - fail_fast: true
- **SonarCloud** for continuous inspection (code smells, coverage tracking, duplication)
- **pytest** with pytest-cov, pytest-django, hypothesis, factory-boy
- **uv** as the Python package manager (with pip fallback in Makefile)

## Consequences

### Positive

- Ruff is fast enough to run on every commit without friction
- Strict mypy catches type errors before runtime
- SonarCloud provides trend visibility across PRs
- Pre-commit prevents broken code from entering the repo

### Negative

- Strict mypy can require verbose type annotations, especially with Django's dynamic patterns

### Risks

- None significant - all tools are industry standard

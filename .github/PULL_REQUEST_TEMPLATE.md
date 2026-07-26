## Summary

<!-- Brief description of changes -->

## Requirement UIDs

- `GC-...`

## Related Issues

<!-- Link to GitHub issues: Closes #XX -->

## ADR Impact

- ADR-...
- or: No ADR required

## Changes

-

## Test Plan

- [ ] Unit tests pass (`make test`)
- [ ] Integration tests pass if applicable (`make integration`)
- [ ] `make check` passes (Spotless, SpotBugs, Error Prone, Checkstyle, JaCoCo)
- [ ] No coverage regression

## Ground Control Checks

- [ ] Configured repository policy command passes
- [ ] `gc_evaluate_quality_gates` passes or is unchanged by this repo-only change
- [ ] `gc_run_sweep` reviewed; findings fixed or recorded with rationale

## Traceability

- IMPLEMENTS:
- TESTS:

## Checklist

- [ ] Code follows project coding standards (`docs/CODING_STANDARDS.md`)
- [ ] No business logic in API layer
- [ ] Domain layer has no framework imports
- [ ] Envers `@Audited` on new entities if applicable
- [ ] PR title is a Conventional Commit (`type(optional-scope): subject`, lowercase-leading subject) - enforced by CI (`.github/workflows/pr-title.yml`). Release Please owns `CHANGELOG.md` and the version bump from this history; do not hand-edit `CHANGELOG.md` or add a `changelog.d/` fragment.
- [ ] Architectural docs updated if stack, package structure, or key behaviors changed (`docs/architecture/ARCHITECTURE.md`, `docs/CODING_STANDARDS.md`, relevant ADRs)

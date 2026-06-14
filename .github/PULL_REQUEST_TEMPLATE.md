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

- [ ] `make policy` passes
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
- [ ] Changelog fragment added under `changelog.d/<issue>.<type>.md` (or `+<slug>.<type>.md`), or no fragment needed (CI-only / docs-only change - refactors under application source still file a fragment). See `changelog.d/README.md`.
- [ ] Architectural docs updated if stack, package structure, or key behaviors changed (`docs/architecture/ARCHITECTURE.md`, `docs/CODING_STANDARDS.md`, relevant ADRs)

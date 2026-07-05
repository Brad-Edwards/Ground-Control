# Mutation Baseline

Issue #1293 introduces the first CLD mutation gate. The baseline is committed
with the registry so thresholds are reviewable architecture data rather than
hidden Gradle, npm, Makefile, or CI constants.

| Boundary | Tool | Scope | Score | Threshold | Mutants | Time Budget |
|----------|------|-------|-------|-----------|---------|-------------|
| `backend-boundary-model` | PIT | `BoundaryModelService` with `BoundaryModelServiceTest` | 64.0% | 64% | 46 killed / 19 survived / 7 no coverage / 72 total | 15 min |
| `frontend-oracle-battery` | Stryker | `frontend/src/test/oracle-battery.ts` with `oracle-battery.test.ts` | 31.58% | 31% | 18 killed / 37 survived / 2 no coverage / 57 total | 10 min |

These first thresholds are deliberately realistic for the existing batteries:
a changed registered boundary below its threshold fails CI, while an
interior-only change produces a green no-op mutation check. Raising thresholds
is a design-authority change to this registry.

# Architecture Registry

This directory holds committed architecture-registry data consumed by repo
policy and CI. It is intentionally small until the productized registry lands.

## Mutation Boundaries

`mutation-boundaries.json` declares CLD mutation-test contracts. Each enabled
boundary names:

- stable boundary id, name, lock level, and repo-relative path selectors;
- mutation tool adapter (`pitest` or `stryker`);
- minimum mutation score threshold and time budget;
- current baseline score with mutant counts and tool version;
- tool-specific scoped targets.

The CI mutation job uses `tools/mutation/run_boundary_mutation.py` to map
changed files to these boundaries. If no changed path matches an enabled
boundary, the job exits successfully with an explicit no-op message. If the
registry itself changes, every enabled boundary runs.

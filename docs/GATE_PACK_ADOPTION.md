# Gate Pack Adoption

Ground Control dogfoods the portable `/implement` gate-pack engine through `.ground-control.yaml` and `.gc/gates.yaml`.

The installed packs are:

- `jvm-gradle` for `backend/` with the `spring` profile
- `node-ts` for `frontend/` with the `react-vite` profile
- `node-ts` for `mcp/ground-control/` with the `node-library` profile
- `docs-generic` for the repository root with the `docs` profile

## Ratchet Baselines

These baselines are pre-existing findings captured during adoption. They are explicit so new findings still fail the bound gates.

| Gate | Baseline | Blocking behavior |
|------|----------|-------------------|
| FindSecBugs via SpotBugs | 51 findings: `COMMAND_INJECTION=1`, `CRLF_INJECTION_LOGS=29`, `POTENTIAL_XML_INJECTION=11`, `REDOS=1`, `SPRING_CSRF_PROTECTION_DISABLED=2`, `SQL_INJECTION_SPRING_JDBC=5`, `UNSAFE_HASH_EQUALS=2` | Line-scoped `backend/config/spotbugs/exclusions.xml`; new findings fail `spotbugsMain` and `make check`. |
| Checkstyle complexity/size | 50 findings in `backend/config/checkstyle/suppressions.xml` | New `CyclomaticComplexity`, `MethodLength`, or `ParameterNumber` violations fail `checkstyleMain`. |
| PMD design/complexity | 46 findings in `backend/config/pmd/baseline.txt` | `pmdRatchet` compares the PMD XML report to the baseline; new findings fail `make check`. |
| ArchUnit freeze rules | 119 frozen findings under `backend/src/test/resources/archunit_store/`: package cycles 47, logger ownership 61, missing write transactions 10, missing request validation 1 | `FreezingArchRule` accepts known architecture violations and fails on new violations. |
| Frontend npm audit | 7 vulnerabilities: `critical=1`, `high=4`, `moderate=2`, `low=0` | `tools/gates/npm-audit-ratchet.mjs` fails if any severity count increases. |
| MCP npm audit | 5 vulnerabilities: `critical=0`, `high=1`, `moderate=4`, `low=0` | `tools/gates/npm-audit-ratchet.mjs` fails if any severity count increases. |

Missing local provider follow-ups remain tracked in the manifest as non-blocking/advisory where the toolchain is not installed locally: Semgrep diff SAST, Stryker mutation for the frontend, axe browser accessibility, and full diff-coverage provider integration.

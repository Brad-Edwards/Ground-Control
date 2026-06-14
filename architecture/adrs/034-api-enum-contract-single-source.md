# ADR-034: API Enum Contract Single Source of Truth

## Status

Accepted

## Date

2026-05-10

## Context

The requirement and traceability vocabularies - `RequirementType`,
`RelationType`, `ArtifactType`, `LinkType` - are expressed three times:

- **Backend**: Java enums under
  `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state/`.
  Jackson binds request/response JSON against these, so they are the *semantic*
  authority - an unknown enum string is rejected by the parser before any domain
  mutation.
- **MCP**: constant arrays in `mcp/ground-control/lib.js`, used to validate tool
  arguments before forwarding to the REST API.
- **Frontend**: union types *and* iterated constant arrays in
  `frontend/src/types/api.ts`, plus per-form literal arrays that fed dropdowns
  and filters.

Nothing tied the three together. The frontend drifted: dropdowns offered
impossible values (`PERFORMANCE`, `SECURITY`, `DATA` for requirement type;
`GITHUB_PR`, `JIRA_ISSUE`, `CONFLUENCE_PAGE`, `TEST_CASE`, `DESIGN_DOC`, `OTHER`
for artifact type; `TRACES_TO`, `DERIVED_FROM` for link type), which produced
avoidable 4xx round-trips. A partial fix (issue #433, commit `0086699`)
centralized the frontend literals into `api.ts` constants and added a
frontend test - but the test compared one hand-written list to another
hand-written list (so it only *moved* the drift), it does not run in PR CI
(the frontend test suite is not part of `ci.yml`), and it introduced the
*inverse* drift: `ArtifactType` in `api.ts` dropped `PULL_REQUEST`,
`RISK_SCENARIO`, and `CONTROL`, which the backend and MCP keep for the GitHub
PR-link, risk-scenario, and control workflows.

ADR-017 (*Interactive Web Application*) contemplates OpenAPI-generated
TypeScript types as the eventual frontend boundary. That generator does not
exist yet, and standing it up (a Gradle task to emit the spec, an
`openapi-typescript` step, a regen workflow, drift-detection on the generated
file) is a larger change than this issue warrants. A narrower mechanism - a
*source extractor* that reads the Java enum files and asserts the mirrors match
 - meets the contract today without that machinery.

The repo's `bin/policy` already runs on every pull request (the `policy` job in
`ci.yml`) and hosts comparable static post-conditions (for example the ADR-026 compose
credential-passthrough check), so it is the natural home for the gate. Adding a
new parallel policy runner is explicitly avoided (`.gc/plan-rules.md`).

## Decision

1. **The backend Java enums under `domain/requirements/state/` are the single
   source of truth.** Every such enum that is mirrored at the API boundary is in
   scope: `RequirementType`, `RelationType`, `ArtifactType`, `LinkType`,
   `Status`, `Priority`, `SyncStatus`, and `ChangeCategory`. `frontend/src/types/api.ts`
   (the `type` unions and, where the UI iterates them, `*_TYPES` constant arrays)
   and the MCP layer (`mcp/ground-control/lib.js` constants, consumed by
   `index.js` `z.enum(...)` schemas) are *mirrors*. Per-component literal arrays
   in the frontend are removed in favor of importing the `api.ts` constants; the
   one MCP-inline literal that remained (`ChangeCategory` in `index.js`) is moved
   to a `CHANGE_CATEGORIES` constant in `lib.js`. `SyncStatus` was already drifted
   (`api.ts` had `SYNCED`/`NOT_SYNCED`/`ERROR`; the backend has `SYNCED`/`STALE`/
   `BROKEN`) - this PR fixes it.

2. **`bin/policy` enforces the contract.** `tools/policy/checks.py` gains
   `run_enum_contract_check`, a static post-condition (independent of the
   changed-files set) that parses each Java enum source and asserts, per enum:
   - the `api.ts` constant array (when the UI iterates one) equals the Java
     constants, in declaration order;
   - the `api.ts` union type's literals equal the Java constants, as a set;
   - the `lib.js` constant array (when one exists) equals the Java constants, in
     declaration order.

   The parsers strip `//` and `/* ... */` comments before extracting literals or
   enum tokens, so a value commented *out* of a mirror - or one that exists only
   inside a comment - does not silently satisfy the check; and the Java parser
   reads only the constant list (up to the first `;` or `}`), so enums with
   methods/fields (for example `Status`) parse correctly. A missing source file, an
   unparseable enum/const/union, or any value mismatch is a `make policy`
   failure. The check is parameterized by `ENUM_CONTRACT_INVENTORY`: covering
   another mirrored enum is one inventory row, not new parsing logic. This is the
   seam the preflight note's extensibility guardrail calls for.

3. **`bin/policy` is the authoritative CI gate**, because the frontend
   vitest/`tsc` checks do not run in PR CI today. `frontend/src/types/enum-contract.test.ts`
   is kept as the developer-local mirror - rewritten to read the actual Java
   enum source files (so it is not the "manual list vs manual list" anti-pattern)
 - but it is a convenience, not the gate. (If a frontend PR job is later added
   to `ci.yml`, it becomes a redundant second gate, which is fine.)

4. **Request-DTO required-field alignment** rides alongside this contract but is
   not mechanically enforced here: `RequirementRequest.statement` is `@NotBlank`
   on the backend, so the create form requires it and `api.ts`'s
   `RequirementRequest.statement` is `string` (not optional). The backend Bean
   Validation annotations on `*Request.java` remain the shape authority;
   client-side `required` attributes mirror them for UX. A future mechanical
   DTO-shape check (Java records ↔ TS interfaces) could extend this ADR's
   inventory pattern; it is out of scope for #433, whose contract criterion is
   *enum* drift.

5. **No supported value is removed from the backend.** Traceability targets used
   by sync, ADR, risk, or control workflows (`PULL_REQUEST`, `RISK_SCENARIO`,
   `CONTROL`, ...) stay in all three layers even when a current UI screen does
   not surface a dedicated affordance for them. Aliases (`GITHUB_PR`) and
   identifier prefixes (`github_pr:`) are not introduced - ADR-011 scopes
   artifact identifiers by `ArtifactType`.

This ADR refines, and does not supersede, ADR-017: OpenAPI-generated frontend
types remain a valid future direction; until then the source extractor is the
contract. The architecture rationale captured during preflight lives in
`architecture/notes/requirement-enum-contract-preflight.md`.

## Consequences

### Positive

- Backend / frontend / MCP enum drift fails fast in `make policy` (and PR CI),
  in both directions (frontend missing a value, or a Java enum change not
  mirrored).
- The frontend cannot offer impossible enum states; dropdowns, filters, and the
  type-safety boundary are all derived from one place.
- Adding or changing an enum is a localized, checkable operation: change the
  Java enum, update the two mirrors, `make policy` confirms.
- No new runtime surface, endpoint, exception type, auth/audit change, or
  migration; the check reads tracked source files only - no network, tokens, or
  argv exposure - consistent with the other `bin/policy` checks.

### Negative

- The mirrors are still hand-maintained; the check catches divergence but does
  not eliminate the edit. (OpenAPI codegen would; that trade - more machinery -
  was judged not worth it for this issue.)
- Two mechanical parsers of Java enum syntax now exist (Python in `bin/policy`,
  TypeScript in `enum-contract.test.ts`). Java enum declarations are trivial to
  parse and the two run in different runtimes for different audiences, so the
  duplication is accepted.
- Ordered comparison of the constant arrays means reordering a Java enum is a
  `make policy` failure until the mirrors are reordered too. This is intentional
  (dropdown order should track the enum) but is a small extra step.

### Risks

- The parsers are regex-based. They handle the shapes that exist today (enums
  with or without a `;`-terminated constant list and method bodies; comments;
  `FOO("x")` constructor args), but an exotic future shape could still mis-parse;
  the `enum-contract-parse-error` / `enum-contract-drift` violations surface that
  rather than silently passing, and the parse-helper unit tests pin the supported
  shapes.
- A future contributor could add a new API-exposed enum under
  `domain/requirements/state/` without adding it to `ENUM_CONTRACT_INVENTORY`,
  leaving it unchecked. Mitigated by documenting the inventory as the extension
  point here and in the preflight note; not mechanically prevented.

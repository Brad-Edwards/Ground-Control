# ADR-042: BDD/Gherkin authored format for test cases

- Status: Accepted
- Date: 2026-05-17
- Driver: TC-004 (issue #671)

## Context

TC-004 introduces the BDD/Gherkin authored format on top of the `TestCase`
aggregate established by ADR-040. The requirement statement is:

> The system shall support BDD/Gherkin test case format with Given/When/Then
> syntax, Scenario and Scenario Outline support, and Examples tables for
> parameterized scenarios.

The codex architecture preflight for #671 explicitly bounded the design:

- Extend ADR-040's `TestCase`; do not create a parallel test-management model.
- Gherkin is an authored format axis, distinct from `TestCaseType`
  (`MANUAL`/`AUTOMATED`/`HYBRID`) and distinct from ADR-041's
  `TestCaseStep`.
- Preserve native Gherkin semantics: `Scenario`, `Scenario Outline`,
  `Examples`, tags, source order, parameter identity.
- Use a maintained Gherkin parser; do not regex-parse `.feature` text.
- Store source verbatim; keep parser AST classes out of JPA entities and
  public DTOs.

Two design questions follow: how do we represent the authored-format axis on
the parent aggregate, and where does the Gherkin source live in the schema?

## Decision

### Authored format axis on `TestCase`

Add `TestCaseFormat` enum with values `STEP_BASED` (the ADR-041 child-row
format) and `GHERKIN` (the new authored .feature source format). The enum
lives at
`backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/state/TestCaseFormat.java`
alongside the other `TestCase` state enums.

The `TestCase` entity gains a non-null `format` column (V076,
`VARCHAR(20) NOT NULL DEFAULT 'STEP_BASED'`). Existing pre-TC-004 rows
back-fill to `STEP_BASED` via the column default - no separate DML required.
V077 adds the matching nullable column on `test_case_audit` for Envers parity.

`format` is **set on create and immutable thereafter**. Mutation would orphan
existing children: a `STEP_BASED` test case may carry rows in
`test_case_step`, and a `GHERKIN` test case may carry a row in
`test_case_gherkin`. Flipping the discriminator while children exist would
break the format-vs-children invariant the services enforce, and a "migrate
my children too" path is out of scope for TC-004. If a downstream requirement
ever needs format migration, it adds an explicit migration endpoint with its
own cascade rules; for now the simpler invariant wins.

Reusing `TestCaseType` as a format discriminator was rejected per the
preflight - `AUTOMATED` is not synonymous with Gherkin (an automated test
case may use step rows or Gherkin or neither), and overloading the field
would conflict with existing UI copy and policy semantics that already treat
the three type values as orthogonal to authoring representation.

### `TestCaseGherkin` aggregate

Introduce a new entity `TestCaseGherkin` inside the existing `testcases`
domain package at
`backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/model/`.
The entity is a child of a single `TestCase`, with cardinality enforced by
both the schema (UNIQUE on `test_case_id`) and the service layer (existence
pre-check in `create`).

Fields:

- `id` UUID PK via `BaseEntity`.
- `test_case_id` UUID FK to `test_case(id)`; `@ManyToOne(fetch = LAZY)`.
- `source` TEXT NOT NULL - the canonical authored `.feature` text, stored
  verbatim.

There is no `parsedScenarioCount`, `hasOutlines`, or any other parser-derived
column on the entity. Parsing is a service-layer validation pass; the AST is
discarded after validation. This matches the preflight's "keep parser AST
classes out of JPA entities and public API DTOs" guardrail and avoids
double-bookkeeping that would drift if a future schema rewrite altered the
parser's output.

### Aggregate boundary

`TestCaseGherkin` references `TestCase` via `@ManyToOne(fetch = LAZY)` only.
There is no `@OneToMany` back-collection on `TestCase`. Rationale mirrors
ADR-041 §Aggregate boundary:

- No N+1 fetch / `LazyInitializationException` surprises when test cases are
  serialised from a controller.
- The Gherkin row is loaded through `TestCaseGherkinRepository.findByTestCaseId`
  with an explicit project-scope check on the parent.
- The TC-001 parent aggregate stays unchanged apart from the `format`
  scalar.

### Cascade on parent deletion

Deleting a `TestCase` must remove its Gherkin row (if any). Same trade-off as
ADR-041: a DB-level `ON DELETE CASCADE` bypasses Envers and silently loses
the audit revision, so we route the delete through Hibernate via
`TestCaseGherkinService.cascadeDeleteByTestCase`. `TestCaseService.delete`
invokes the step cascade and the Gherkin cascade in order, then deletes the
parent. A `Mockito.inOrder` assertion in `TestCaseServiceTest` pins the order
so a future refactor that flipped or skipped a cascade fails the test rather
than silently regressing audit coverage.

### Audit

`TestCaseGherkin` is `@Audited`. The only `@ManyToOne` is to `TestCase`,
which is itself `@Audited`, so no `@NotAudited` is needed. The Flyway pair
`V078__create_test_case_gherkin.sql` + `V079__create_test_case_gherkin_audit.sql`
lands the main and audit tables together; the audit table carries
`created_at` / `updated_at` columns per the V075 pattern so Envers flushes
succeed. `test_case_gherkin_audit` is added to `AuditRetentionJob.AUDIT_TABLES`
so retention sweep covers it.

### Parser

The validator at
`backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/service/GherkinValidator.java`
wraps `io.cucumber:gherkin:39.1.0`. This is the Cucumber organisation's pure
Gherkin parser - Apache 2.0, no execution surface, no glue loading, no
remote fetch, no temp files. The transitive `io.cucumber:messages` carries
the AST message types only; both libraries are widely deployed and present a
small OSV-scan surface.

The validator enforces, in order:

1. Source non-blank and ≤ `MAX_SOURCE_LENGTH` (102400 chars).
2. Source parses without any `ParseError` envelopes.
3. Source declares a `Feature` and at least one `Scenario` or
   `Scenario Outline` (≤ `MAX_SCENARIOS = 50`).
4. Each scenario has at least one step.
5. A `Scenario Outline` carries at least one `Examples` block. (The Gherkin
   grammar treats `Scenario` and `Scenario Outline` as the same syntactic
   node distinguished by keyword text; without explicit validator logic an
   outline without examples would parse as a degenerate scenario.)
6. Each `Examples` block has a header and at least one data row, ≤
   `MAX_EXAMPLES_ROWS = 200` rows, ≤ `MAX_EXAMPLES_CELL_LENGTH = 4000` chars
   per cell.

Validation failures surface `DomainValidationException` with code
`invalid_gherkin_source` and details that carry `line`, `column`, `keyword`,
and `field` only - never the source text, parser stack traces, `Examples`
cell content, or filesystem paths. This is the error-envelope guardrail from
the preflight; a dedicated unit test (`errorDetailsNeverLeakSourceText`)
asserts a distinctive source token does not appear anywhere in the exception
details or message.

Limits are static `public static final` constants on the validator. Making
them configurable would broaden the surface beyond what TC-004 asks for; if
a downstream requirement needs configurability, bind through a validated
`@ConfigurationProperties` POJO per the preflight.

### Mutual exclusion between step rows and Gherkin source

A `STEP_BASED` test case cannot accept a Gherkin document; a `GHERKIN` test
case cannot accept step rows. Enforcement lives in two service methods:

- `TestCaseGherkinService.requireGherkinTestCase` - POST/PUT to the Gherkin
  endpoint against a non-`GHERKIN` parent returns HTTP 422
  `invalid_test_case_format` with `expected: GHERKIN` and the actual format
  in the details.
- `TestCaseStepService.create` - POST step against a non-`STEP_BASED` parent
  returns HTTP 409 with a message naming the parent's actual format.

The asymmetry (422 vs 409) follows existing repo conventions:
`DomainValidationException` is the "wrong shape" signal (422), while
`ConflictException` is the "right shape, wrong state of resource" signal
(409). The step path is the latter - the step body is well-formed, but the
parent's state forbids it; the Gherkin path is the former - the parent's
state is incompatible with the operation at the validation layer.

### API surface

```
POST   /api/v1/test-cases/{testCaseId}/gherkin?project=…   → 201
GET    /api/v1/test-cases/{testCaseId}/gherkin?project=…   → 200
PUT    /api/v1/test-cases/{testCaseId}/gherkin?project=…   → 200
DELETE /api/v1/test-cases/{testCaseId}/gherkin?project=…   → 204
```

Singleton sub-resource: there is no `gherkinId` in the path because each
parent has at most one. The DTOs are deliberately minimal:

- `TestCaseGherkinRequest { source }`
- `UpdateTestCaseGherkinRequest { source }` - full replacement; no
  null-means-no-change semantic because the resource is a single field.
- `TestCaseGherkinResponse { id, testCaseId, source, createdAt, updatedAt }`.

`TestCaseRequest` gains an optional `format` field; `UpdateTestCaseRequest`
does not, because format is immutable after create.

The pre-existing `TestCase` API surface unchanged otherwise; `TestCaseResponse`
gains `format` so clients can route to the right authoring UI.

### MCP surface

The `controller-parity` policy check (`tools/policy/checks.py::run_controller_contracts`)
requires every new controller to land alongside `docs/API.md`,
`mcp/ground-control/lib.js`, and `mcp/ground-control/index.js` updates plus a
matching `@WebMvcTest` update. TC-004 extends the existing `gc_test_case`
consolidated tool with three new actions:

- `gherkin-create`
- `gherkin-update`
- `gherkin-delete`

Reads route through `gc_query` against the `TestCaseGherkin` entity, the same
pattern TC-001/TC-002 established.

The MCP arg name is `gherkin_source` (namespaced to avoid clashing with any
other `source` field a future test-case sub-resource might introduce); the
handler maps it explicitly to backend body `{ source: ... }` rather than
through the `TO_CAMEL` table, mirroring the `step_action → action` pattern
TC-002 used for the same reason.

A new `TEST_CASE_FORMATS` enum constant exports `["STEP_BASED", "GHERKIN"]`
and is asserted by `test-case-tools.test.js` so a drift between the Java
enum and the JS mirror surfaces immediately.

### Frontend mirror

`frontend/src/types/api.ts` ships `TestCaseFormat` (typed union + const
array), `TestCaseGherkinRequest`, `UpdateTestCaseGherkinRequest`, and
`TestCaseGherkinResponse` interfaces. The frontend does not implement a
Gherkin renderer in this PR - clients are expected to display the stored
source via a safe text/Markdown path. Introducing
`dangerouslySetInnerHTML` or an unsafe HTML sink is **not** part of TC-004.

## Consequences

- TC-004 fully implements the BDD/Gherkin format clause-by-clause with no
  shortcuts.
- The Gherkin parser stays in the validator only; if a future requirement
  needs scenario-level execution targets or step-level identity, it adds a
  separate aggregate that references the canonical source rather than
  re-parsing on every read.
- The format discriminator is the load-bearing extensibility seam: future
  authored formats (TestRail manual steps verbatim, Robot Framework
  source, etc.) plug in as additional `TestCaseFormat` values plus a
  dedicated sibling child aggregate; the parent `TestCase` does not need
  another schema rewrite.
- Image binary upload (still the ADR-041 inline-`![alt](url)` story) is
  unchanged.

## Alternatives considered

1. **Store Gherkin source in `TestCase.description`.** Rejected - the
   description is a Markdown rich-text field with its own size budget and
   no validation pass. Conflating Gherkin source with description would
   lose the parse-time validation guard, lose the format-vs-children
   invariant, and silently change the meaning of `description` on the API
   surface.
2. **Parse Gherkin into ordered step rows and reuse `TestCaseStep`.**
   Rejected per the preflight - flattening Given/When/Then into
   `action`/`expectedResult`/`actualResult` loses Scenario boundaries,
   Scenario Outline identity, tags, and `Examples` parameters. The
   reconstruction problem (round-trip to authored source) is intractable
   without storing the original anyway.
3. **Make `TestCaseType.AUTOMATED` imply Gherkin.** Rejected per the
   preflight - `AUTOMATED` is "this test has automation-stage support,"
   not "the authoring format is Gherkin." The two axes are independent.
4. **DB-level `ON DELETE CASCADE` for parent deletion.** Rejected - same
   audit-fidelity reasoning as ADR-041. Hibernate-routed deletes capture
   Envers revisions; DB-level cascades silently skip them.
5. **Allow format mutation on update.** Rejected - would orphan existing
   step rows or Gherkin documents and force a "migrate my children too"
   path TC-004 does not need. A future migration requirement can add an
   explicit endpoint.
6. **Store parsed scenario metadata on `TestCaseGherkin`.** Rejected for
   TC-004 - clients that need a structured view can re-parse the source.
   Adding derived columns now would double-book the canonical source and
   require an explicit sync invariant the requirement does not justify.
7. **Hand-roll a regex-based Gherkin reader.** Rejected per the preflight
   guardrail. The Cucumber-org parser is the canonical implementation,
   pure-Java, no execution surface, and tracks the Gherkin spec.

## Out of scope

- Cucumber runtime, glue-code execution, runner orchestration, CI binding,
  or pass/fail result model. The parser is for validation only.
- Vendor import/export mapping for Xray, Zephyr Scale, PractiTest, qTest,
  or `.feature` file upload beyond the base Gherkin authoring contract.
- Replacing or merging ADR-040's `TestCase`, ADR-041's `TestCaseStep`,
  requirements, traceability links, verification results, documents, graph
  projection, or control-test execution records.
- A React UI for Gherkin authoring or syntax highlighting (frontend types
  ship, but UI is a follow-on).
- Graph projection for Gherkin (test cases are still not graph-visible per
  ADR-040).
- Image binary upload / hosting / antivirus / processing pipeline (still
  defers to ADR-041's inline-`![alt](url)` seam).
- A configurable parser-limit `@ConfigurationProperties` block.
- Bulk operations for Gherkin documents.

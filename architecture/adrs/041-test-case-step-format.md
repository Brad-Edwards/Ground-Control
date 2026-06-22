# ADR-041: Step-based test case format

- Status: Accepted
- Date: 2026-05-16
- Driver: TC-002 (issue #670)

## Context

TC-002 introduces the step-based test case format on top of the TestCase aggregate
defined in ADR-040. The requirement statement is:

> The system shall support a step-based test case format where each test case contains
> an ordered sequence of steps, each with: step number, action description, expected
> result, and actual result fields. Steps shall support rich text and inline images.

ADR-040 deferred test steps to TC-002. This ADR records the design decisions for the
child step aggregate, the rich-text representation, the inline-image strategy, the
cascade behaviour on parent deletion, and the API surface.

## Decision

Introduce a `TestCaseStep` aggregate inside the existing `testcases` domain package at
`backend/src/main/java/com/keplerops/groundcontrol/domain/testcases/`. The step is a
child of a single `TestCase` and inherits its project-scoping through that parent.

### Identity and ordering

- **UUID primary key** via `BaseEntity`. `stepNumber` is a value, not the identity.
  This is the explicit guard called out in the architecture preflight: making
  `stepNumber` the PK would break re-ordering and forbid stable references from any
  future per-step execution layer.
- **Positive integer `stepNumber`** with a database `CHECK (step_number > 0)` and a
  service-layer `existsByTestCaseIdAndStepNumber` pre-check that returns HTTP 409 on
  duplicate.
- **Per-test-case uniqueness** via the unique constraint
  `(test_case_id, step_number)`. Gaps after deletion are permitted; clients renumber
  manually when they care about contiguity.
- **List ordering** by `stepNumber` ascending in `findByTestCaseIdOrderByStepNumberAsc`;
  the controller never returns steps unsorted.

A bulk-reorder endpoint is intentionally **not** added. Frontend reorder, when it
ships, can swap two step numbers with one intermediate PUT (`step 1 → 99`,
`step 2 → 1`, `step 99 → 2`); this is awkward but does not justify a parallel
collection-replace API in the TC-002 scope. A future requirement may add bulk
reorder if it becomes load-bearing.

### Step fields

- `action` - `TEXT NOT NULL`, max 10000 chars at the DTO. What the operator does.
- `expectedResult` - `TEXT NOT NULL`, max 10000 chars at the DTO. What should happen.
- `actualResult` - `TEXT NULL`, max 10000 chars at the DTO. What actually happened on
  the latest authored pass. Nullable because a freshly authored step has no observed
  outcome yet.

The 10000-character bound is a constrained-untrusted-content footprint trade-off: large
enough for real prose with several embedded Markdown image references, small enough
that a single test case's list response stays bounded even with many steps.

### Rich text

Rich-text fields use **CommonMark Markdown** by convention, the same precedent set by
ADR-040 §Rich text for the parent's `description` / `preconditions` /
`postconditions`. The backend stores the Markdown source verbatim as TEXT; it does
not render HTML, does not sanitize, and does not parse the Markdown server-side. When
a shared sanitised renderer lands (preflight forward-compat note), these fields are
forward-compatible.

The preflight explicitly warned against introducing `dangerouslySetInnerHTML` or an
equivalent unsafe HTML sink. This ADR upholds that: the rich-text path is text in →
text out. Frontend renderers are responsible for choosing a safe Markdown library
when UI work ships.

### Inline images

The requirement says "Steps shall support rich text and inline images." The chosen
seam is the **CommonMark image syntax** `![alt](url)` embedded in the same TEXT
field. The backend carries these strings verbatim - it does not validate the URL,
does not fetch the bytes, and does not host image binaries.

This is the smallest design that satisfies the clause while staying inside every
preflight non-goal:

- No image-binary upload API.
- No image-processing pipeline.
- No remote URL fetcher (an SSRF surface).
- No antivirus or content scanner.
- No filesystem temp paths.
- No OCR.

Image binary storage and upload - when a downstream requirement demands them - plug in
as a separate `Asset`-style aggregate that exposes `https://<host>/api/v1/assets/<id>`
URLs the Markdown reference can target. The text representation does not change.

### Aggregate boundary

`TestCaseStep` references `TestCase` via `@ManyToOne(fetch = LAZY)` only. There is
**no** `@OneToMany` back-collection on `TestCase`. Rationale:

- Avoids the N+1 fetch / `LazyInitializationException` surprises a bidirectional
  mapping brings when test cases are serialised from a controller.
- Steps are loaded through `TestCaseStepRepository.findByTestCaseIdOrderByStepNumberAsc`
  with an explicit project-scope check, which is the same pattern used by other
  unidirectional child collections in the domain.
- Keeps the TC-001 parent aggregate unchanged.

### Cascade on parent deletion

Deleting a `TestCase` must remove its steps. Two implementations were considered:

1. **DB-level `ON DELETE CASCADE`** on the `test_case_step.test_case_id` FK. Simple,
   self-contained at the schema layer.
2. **Service-level cascade** - `TestCaseService.delete()` first calls
   `TestCaseStepService.deleteAllByTestCase(...)` and only then deletes the parent.

Choice: **option 2**. Envers records each `TestCaseStep` delete in the
`test_case_step_audit` table only when the delete flows through Hibernate. A
DB-level CASCADE bypasses Envers and silently loses the per-step audit trail. Audit
fidelity is the load-bearing constraint; the extra few lines in the service are
worth it.

The behavior is pinned by a `Mockito.inOrder` assertion in `TestCaseServiceTest` so a
future refactor that flipped the call order (or replaced the service-level cascade
with a DB CASCADE) would fail the test rather than silently regress audit coverage.

### Audit

`TestCaseStep` is `@Audited` (Envers). Because the only `@ManyToOne` is to
`TestCase`, which is itself `@Audited`, no `@NotAudited` is needed. The Flyway pair
`V073__create_test_case_step.sql` + `V074__create_test_case_step_audit.sql` lands
the main and audit tables together. `test_case_step_audit` is added to
`AuditRetentionJob.AUDIT_TABLES` so retention sweeps cover it.

### API surface

```
POST   /api/v1/test-cases/{testCaseId}/steps?project=…            → 201
GET    /api/v1/test-cases/{testCaseId}/steps?project=…            → 200 (list, ordered)
GET    /api/v1/test-cases/{testCaseId}/steps/{stepId}?project=…   → 200
PUT    /api/v1/test-cases/{testCaseId}/steps/{stepId}?project=…   → 200
DELETE /api/v1/test-cases/{testCaseId}/steps/{stepId}?project=…   → 204
```

The service validates that the `testCaseId` resolves to a row inside the resolved
project. A step request targeting a test case that is not in the resolved project
returns HTTP 404 - the cross-project-leakage shape called out in the preflight is
covered by `crossTestCaseStepAccessRejected` in the integration test.

### MCP surface

The `controller-parity` policy check (`tools/policy/checks.py::run_controller_contracts`)
requires every new controller to land alongside `docs/API.md`,
`mcp/ground-control/lib.js`, and `mcp/ground-control/index.js` updates. TC-002
satisfies this by extending the existing `gc_test_case` consolidated tool with three
new actions:

- `step-create`
- `step-update`
- `step-delete`

Read paths (step-list, step-get) route through `gc_query` against the
`TestCaseStep` entity, the same pattern TC-001 established. Snake-case → camelCase
mapping is covered by the `TO_CAMEL` table entries for `step_number`,
`expected_result`, `actual_result`, and `clear_actual_result`, with an adapter test
in `test-case-tools.test.js` so a missing entry surfaces immediately (the failure
mode that drove TC-001 codex cycle 1).

The discriminator collision between the MCP tool's existing `action` argument and
the step's `action` field is resolved by exposing the step content as `step_action`
on the MCP surface; the handler maps it to `action` before posting to the backend.

### Frontend mirror

`frontend/src/types/api.ts` ships `TestCaseStepResponse`, `TestCaseStepRequest`, and
`UpdateTestCaseStepRequest` interfaces alongside the existing TC-001 enum mirrors.
No new enums are introduced in TC-002 (steps have no status or type vocabulary), so
ADR-034's enum-mirror requirement does not produce additional rows.

## Consequences

- TC-002 fully implements the step-based format clause-by-clause with no shortcuts.
- Future per-step execution tracking (next likely TC requirement) plugs into a
  separate execution-layer aggregate that references step UUIDs. The authored step's
  `actualResult` field is preserved for the "what was observed when the step was
  last authored" use case; runtime/historical observations belong in the future
  execution layer.
- Image hosting can be added later behind a separate `Asset` aggregate without
  changing the step format. The Markdown URL stays the seam.
- Pre-existing TC-001 service code gains one cascade call and one corresponding unit
  test. Blast radius is local to the `testcases` package.

## Alternatives considered

1. **Store steps as JSON in `TestCase.customFields`.** Rejected - preflight explicit
   non-goal. JSON-in-text loses per-step audit, per-field validation, and the
   `(test_case_id, step_number)` uniqueness invariant.
2. **`@OneToMany` collection on `TestCase`.** Rejected - would have required loading
   every step every time a test case is serialised, or carrying a lazy-init hazard
   that bidirectional collections regularly trigger in Spring serialisation.
3. **DB-level `ON DELETE CASCADE` for parent deletion.** Rejected - bypasses Envers
   and silently loses per-step audit revisions.
4. **HTML rich text with a server-side sanitiser.** Rejected - introduces an HTML
   sink the codebase doesn't currently need, and the sanitiser is a non-trivial
   security surface that the preflight pushed to a future shared-renderer
   requirement. Markdown stays the smaller surface.
5. **Inline image binaries as base64 in the step body.** Rejected - preflight
   non-goal. Inflates every list/detail response, makes audit diffs unreadable,
   and conflicts with the bounded-untrusted-content footprint goal.
6. **Bulk reorder endpoint.** Rejected for TC-002 - the requirement statement does
   not mandate it, and the unique constraint plus three sequential PUTs implement
   any reorder. A future frontend-driven requirement can revisit.

## Out of scope

- Image binary upload / hosting / antivirus / processing pipeline.
- Per-step execution results, pass/fail tracking across runs, environments, or
  testers.
- Bulk reorder primitive.
- A React UI for step authoring.
- Cross-test-case step references or step reuse.
- Graph projection for steps (inherits from TestCase, which is not graph-visible
  per ADR-040).

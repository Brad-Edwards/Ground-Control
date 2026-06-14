# ADR-043: Test Case Hierarchical Organization

## Status

Accepted - 2026-05-17.

## Context

TC-005 (Wave 1, MUST) requires the system to support hierarchical
folder/section organisation for test cases, with unlimited nesting,
drag-and-drop reordering, move/copy between folders, and tree-based
repository browsing.

The existing test-case aggregates (`TestCase`, `TestCaseStep`,
`TestCaseGherkin` from ADR-040/041/042) are flat: every test case sits
directly under a project, with no notion of grouping. The
`domain/documents/section` package already implements a tree for
document content, but its semantics (document-scoped, no audit, no
cycle protection) do not match test repository organisation.

## Decision

Introduce a dedicated `TestCaseFolder` aggregate inside the existing
`domain/testcases` boundary. The folder is project-scoped, self-
referencing (`parent` nullable), `@Audited`, and ordered within its
container via `sort_order`. The `TestCase` aggregate gains
`parentFolderId` (nullable; null ⇒ project root) and `sort_order`.

### Boundary and naming

- The aggregate is **not** a reuse of `documents.Section`. Test
  organisation and document structure are unrelated concerns and
  conflating them would couple two ordering models, leak document
  semantics into test management, and force every audit/compliance
  invariant for one to apply to the other.
- The vocabulary is `TestCaseFolder`. Test steps, runs, results,
  defects, suites, automation, and traceability remain separate
  surfaces.

### Sibling sort spaces

Folders and test cases use **independent** `sort_order` axes within
each container. The tree renders folders first (sorted by their
`sort_order`), then test cases (sorted by their `sort_order`). This
matches best-of-breed UX (folders sit above leaves in file explorers
and test repositories) and keeps reorder queries simple - each
operation rewrites at most N rows in one container of one entity type.

A unified ordering across heterogeneous entities was considered and
rejected: it would require either a synthetic key on each entity that
includes its kind, or a discriminator column on a shared `position`
table. Both add structural complexity for marginal UX gain.

### Sibling-title uniqueness

Folder titles must be unique within their container. PostgreSQL treats
NULL parents as distinct under a plain `UNIQUE`, so two partial unique
indexes back the invariant:

- `uq_test_case_folder_title_root` covers `(project_id, title)` where
  `parent_id IS NULL`.
- `uq_test_case_folder_title_under_parent` covers
  `(project_id, parent_id, title)` where `parent_id IS NOT NULL`.

Test-case sibling uniqueness already exists via `(project_id, uid)`
on `TestCase`; no additional index is needed for placement.

### Move semantics

`TestCaseFolderService.move` and `TestCaseService.move` change
placement only:

- The moved aggregate retains its identity (`id`, `uid` for test
  cases, audit history). The Envers revision records the new parent
  and sort order.
- Cross-project moves are rejected because `findByIdAndProjectId` is
  used for every lookup; the target folder must belong to the same
  project.
- Folder moves additionally reject moving a folder under itself or
  any descendant (cycle protection by ancestor walk with a defensive
  depth cap of 10_000).
- Same-container moves are a no-op when `sortOrder` is null (no
  rebalancing); explicit sort positions are honoured.

### Copy semantics

`TestCaseService.copy` creates a new `TestCase` with a
caller-supplied `newUid`, copies every immutable definition field
(title, description, preconditions, postconditions, priority, type,
format, estimatedDurationSeconds), resets status to `DRAFT`, and
clones authored children through their owning services
(`TestCaseStepService.copyStepsToTestCase`,
`TestCaseGherkinService.copyGherkinToTestCase`). The source test case
is unchanged.

Copy placement uses the **same convention as move**: `parentFolderId`
is the explicit target - a UUID names the folder, `null` (or omitted)
means the project root. The earlier "null preserves the source's
folder" semantic was rejected because it conflated JSON omission and
explicit null, leaving the project root unreachable for a copy from a
folder. Callers that want to clone in place pass the source's
`parentFolderId` explicitly.

A copy without a `newUid` is rejected. Auto-generating UIDs would
either leak naming conventions into the domain or force callers to
clean up afterwards; explicit naming keeps the contract obvious.

`actualResult` is **not** copied for step children - it is run-time
evidence (ADR-041), not part of the test-case definition.

### Folder deletion

Folder deletion is **rejected** when the folder still contains child
folders or test cases. Callers must move or delete the contents first.
Silent cascade was rejected because:

- It would skip the per-child Envers audit revision that explicit
  service calls produce.
- It would surprise users by destroying authored test definitions on
  a single DELETE.

### Tree read

`GET /api/v1/test-cases/tree` returns a deterministic nested tree.
The service issues one batch SELECT per entity kind and assembles
the tree in memory (group-by-parent, sort by `sort_order`); there
are no recursive repository calls. Adapted from
`SectionService.getTree` but kept in the test-case domain.

### API surface

All routes stay under `/api/v1/test-cases/**` so the existing auth
allow-list, IP guard, and actor-filter chain apply unchanged:

- `POST/GET/PUT/DELETE /api/v1/test-cases/folders[/...]`
- `PUT /api/v1/test-cases/folders/{id}/move`
- `PUT /api/v1/test-cases/folders/reorder`
- `PUT /api/v1/test-cases/{id}/move`
- `POST /api/v1/test-cases/{id}/copy`
- `PUT /api/v1/test-cases/reorder`
- `GET /api/v1/test-cases/tree`

### Persistence

Four migrations (V084–V087) introduce the folder table, its audit
shadow, and the placement columns on `test_case` / `test_case_audit`.
`AUDIT_TABLES` in `AuditRetentionJob` is updated to include the
folder audit table. `MigrationSmokeTest` covers the new versions and
probes for the new tables/columns/indexes.

### MCP and frontend parity

- `gc_test_case` gains folder/move/copy/reorder actions.
- `gc_query` allowlist covers `/api/v1/test-cases/folders` and
  `/api/v1/test-cases/tree` reads.
- `frontend/src/types/api.ts` mirrors the new request/response shapes
  and the tree-node discriminated union.
- `docs/API.md` documents the new endpoints.

## Consequences

- Drag-and-drop reordering becomes a single-container `PUT /reorder`
  call; the backend renumbers `sort_order` to 0..N-1 atomically and
  rejects partial reorder lists.
- Move/copy/delete are audit-visible operations; Envers records every
  state change at the row level for compliance.
- The tree read scales to "unlimited nesting" while staying O(n) in
  the number of folders + test cases.
- Future "archived" filters and root-folder selection plug into the
  tree read as additional query parameters without folder-schema
  changes.

## Alternatives considered

- **Reuse `documents.Section`.** Rejected: coupling unrelated domains.
- **Embed folder path in the test-case UID.** Rejected: UIDs would
  drift under move; foreign-key denormalisation; existing UIDs would
  need rewriting.
- **Cascade-delete folder contents.** Rejected: skips per-child audit;
  surprising blast radius.
- **Unified sort_order across folders + test cases.** Rejected: extra
  structural complexity for marginal UX gain.
- **Auto-generate copy UIDs.** Rejected: leaks naming conventions into
  the domain.

## Related

- TC-005 - Hierarchical Test Case Organization (Wave 1)
- ADR-040 Test Case Domain
- ADR-041 Test Case Step Format
- ADR-042 Test Case BDD/Gherkin Format
- `architecture/notes/test-case-hierarchy-preflight.md`

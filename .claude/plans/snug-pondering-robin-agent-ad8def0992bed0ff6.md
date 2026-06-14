# GC-F001: Verification Result Storage -- Implementation Plan

## Requirement Summary

**UID:** GC-F001
**Title:** Verification Result Storage
**Statement:** Store verification results from any prover or verifier in a common schema, capturing: the requirement verified, the verifier used, the result (pass/fail/error/inconclusive), evidence artifacts, and timestamp.
**Source:** ADR-014 (Pluggable Verification Architecture)
**Status:** DRAFT, Wave 3, Priority MUST

## Scope Boundaries

**In scope:**
1. Domain model (entity, enums, repository, service)
2. REST API (CRUD endpoints)
3. MCP tools (matching REST API)
4. Database migrations (V049, V050)
5. Tests (controller unit, service unit, migration smoke)

**Out of scope:**
- Infrastructure verifier adapters (OpenJmlAdapter, TlcAdapter, OpaAdapter, etc.)
- Graph materialization for VerificationResult (separate feature)
- Automated re-verification triggers based on expiresAt

---

## Phase 1: Domain Layer

### Step 1.1: Create enum VerificationStatus

**File:** `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/state/VerificationStatus.java`

Simple enum with no state machine (verification results do not transition through states):

```java
public enum VerificationStatus {
    PROVEN,
    REFUTED,
    TIMEOUT,
    UNKNOWN,
    ERROR
}
```

No `canTransitionTo()` method needed -- this is a terminal classification, not a lifecycle state.

### Step 1.2: Create enum AssuranceLevel

**File:** `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/state/AssuranceLevel.java`

```java
public enum AssuranceLevel {
    L0,
    L1,
    L2,
    L3
}
```

### Step 1.3: Create entity VerificationResult

**File:** `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/model/VerificationResult.java`

Follow the Control.java pattern exactly:
- Extends `BaseEntity` (UUID id, Instant createdAt, updatedAt)
- `@Entity @Audited`
- `@NotAudited` on `@ManyToOne` to `Project` (matches Control pattern -- Project is not audited)
- `@ManyToOne(fetch = FetchType.LAZY)` for `target` (TraceabilityLink) -- nullable
- `@ManyToOne(fetch = FetchType.LAZY)` for `requirement` (Requirement) -- nullable
- `@Enumerated(EnumType.STRING)` for `result` and `assuranceLevel`
- `@Convert(converter = JacksonTextCollectionConverters.StringObjectMapConverter.class)` for `evidence` field (Map<String, Object>, stored as TEXT, matching the Control.java pattern for methodology_factors/effectiveness)
- Constructor: `VerificationResult(Project project, String prover, VerificationStatus result, Instant verifiedAt)`

Fields (mapped to ADR-014 schema):

| Java field | Column | Type | Nullable | Notes |
|-----------|--------|------|----------|-------|
| project | project_id | FK -> project | NOT NULL | @NotAudited, `FetchType.EAGER` (matches Control) |
| target | target_id | FK -> traceability_link | nullable | @NotAudited, `FetchType.LAZY` |
| requirement | requirement_id | FK -> requirement | nullable | @NotAudited, `FetchType.LAZY` |
| prover | prover | VARCHAR(50) | NOT NULL | Tool identifier string |
| property | property | TEXT | nullable | Formal property checked |
| result | result | VARCHAR(20) | NOT NULL | Enum: PROVEN, REFUTED, TIMEOUT, UNKNOWN, ERROR |
| assuranceLevel | assurance_level | VARCHAR(5) | nullable | Enum: L0, L1, L2, L3 |
| evidence | evidence | TEXT | nullable | JSON via StringObjectMapConverter |
| verifiedAt | verified_at | TIMESTAMPTZ | NOT NULL | When verification ran |
| expiresAt | expires_at | TIMESTAMPTZ | nullable | Re-verification trigger |

Design decisions:
- No `@UniqueConstraint` -- the same target can be verified multiple times by the same prover (each run is a distinct result).
- `target` is nullable -- manual review results or design-level verifications may not have a TraceabilityLink yet.
- `requirement` is nullable -- some verification results may be target-driven without a specific requirement linkage.
- `project` uses EAGER fetch (matches Control pattern) since all list/get queries are project-scoped.
- `target` and `requirement` use LAZY fetch to avoid N+1 on list queries.
- Both `target` and `requirement` get `@NotAudited` since TraceabilityLink and Requirement are audited independently.

### Step 1.4: Create repository VerificationResultRepository

**File:** `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/repository/VerificationResultRepository.java`

```java
public interface VerificationResultRepository extends JpaRepository<VerificationResult, UUID> {
    Optional<VerificationResult> findByIdAndProjectId(UUID id, UUID projectId);
    List<VerificationResult> findByProjectIdOrderByVerifiedAtDesc(UUID projectId);
    List<VerificationResult> findByProjectIdAndRequirementIdOrderByVerifiedAtDesc(UUID projectId, UUID requirementId);
    List<VerificationResult> findByProjectIdAndTargetIdOrderByVerifiedAtDesc(UUID projectId, UUID targetId);
    boolean existsByIdAndProjectId(UUID id, UUID projectId);
}
```

Notable: ordering by `verifiedAt DESC` (not `createdAt DESC`) because verification results should be ordered by when the verification ran, not when the record was created in the database.

### Step 1.5: Create command records

**File:** `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/service/CreateVerificationResultCommand.java`

```java
public record CreateVerificationResultCommand(
    UUID projectId,
    UUID targetId,           // nullable
    UUID requirementId,      // nullable
    String prover,
    String property,         // nullable
    VerificationStatus result,
    AssuranceLevel assuranceLevel,  // nullable
    Map<String, Object> evidence,   // nullable
    Instant verifiedAt,
    Instant expiresAt        // nullable
) {}
```

**File:** `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/service/UpdateVerificationResultCommand.java`

```java
public record UpdateVerificationResultCommand(
    String prover,               // nullable (null = don't update)
    String property,             // nullable
    VerificationStatus result,   // nullable
    AssuranceLevel assuranceLevel, // nullable
    Map<String, Object> evidence,  // nullable
    Instant verifiedAt,          // nullable
    Instant expiresAt            // nullable
) {}
```

### Step 1.6: Create service VerificationResultService

**File:** `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/service/VerificationResultService.java`

Follow the ControlService.java pattern:
- `@Service @Transactional`
- Constructor injection of `VerificationResultRepository`, `ProjectService`, `RequirementRepository` (for validating requirementId), `TraceabilityLinkRepository` (for validating targetId)
- Methods:
  - `create(CreateVerificationResultCommand)` -- validates project, optionally resolves target/requirement FKs, saves, logs
  - `update(UUID projectId, UUID id, UpdateVerificationResultCommand)` -- find-or-throw, apply non-null fields, save, log
  - `getById(UUID projectId, UUID id)` -- read-only, find-or-throw
  - `listByProject(UUID projectId)` -- read-only, returns list ordered by verifiedAt DESC
  - `listByRequirement(UUID projectId, UUID requirementId)` -- read-only, filter by requirement
  - `listByTarget(UUID projectId, UUID targetId)` -- read-only, filter by target
  - `delete(UUID projectId, UUID id)` -- find-or-throw, delete, log

Logging pattern (matching Control):
```
log.info("verification_result_created: id={} prover={} project={}", ...)
log.info("verification_result_updated: id={}", ...)
log.info("verification_result_deleted: id={}", ...)
```

FK validation on create:
- If `targetId` is provided, look up `TraceabilityLink` by ID and confirm it belongs to the same project (via its requirement's project). Throw NotFoundException if not found.
- If `requirementId` is provided, look up `Requirement` by ID and confirm it belongs to the same project. Throw NotFoundException if not found.

---

## Phase 2: REST API Layer

### Step 2.1: Create request DTO

**File:** `backend/src/main/java/com/keplerops/groundcontrol/api/verification/VerificationResultRequest.java`

```java
public record VerificationResultRequest(
    UUID targetId,
    UUID requirementId,
    @NotBlank @Size(max = 50) String prover,
    String property,
    @NotNull VerificationStatus result,
    AssuranceLevel assuranceLevel,
    Map<String, Object> evidence,
    @NotNull Instant verifiedAt,
    Instant expiresAt
) {}
```

### Step 2.2: Create update request DTO

**File:** `backend/src/main/java/com/keplerops/groundcontrol/api/verification/UpdateVerificationResultRequest.java`

```java
public record UpdateVerificationResultRequest(
    @Size(max = 50) String prover,
    String property,
    VerificationStatus result,
    AssuranceLevel assuranceLevel,
    Map<String, Object> evidence,
    Instant verifiedAt,
    Instant expiresAt
) {}
```

### Step 2.3: Create response DTO

**File:** `backend/src/main/java/com/keplerops/groundcontrol/api/verification/VerificationResultResponse.java`

```java
public record VerificationResultResponse(
    UUID id,
    String graphNodeId,
    String projectIdentifier,
    UUID targetId,
    UUID requirementId,
    String prover,
    String property,
    VerificationStatus result,
    AssuranceLevel assuranceLevel,
    Map<String, Object> evidence,
    Instant verifiedAt,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static VerificationResultResponse from(VerificationResult vr) {
        return new VerificationResultResponse(
            vr.getId(),
            GraphIds.nodeId(GraphEntityType.VERIFICATION_RESULT, vr.getId()),
            vr.getProject().getIdentifier(),
            vr.getTarget() != null ? vr.getTarget().getId() : null,
            vr.getRequirement() != null ? vr.getRequirement().getId() : null,
            vr.getProver(),
            vr.getProperty(),
            vr.getResult(),
            vr.getAssuranceLevel(),
            vr.getEvidence(),
            vr.getVerifiedAt(),
            vr.getExpiresAt(),
            vr.getCreatedAt(),
            vr.getUpdatedAt()
        );
    }
}
```

**Note:** This requires adding `VERIFICATION_RESULT` to the `GraphEntityType` enum.

### Step 2.4: Create controller

**File:** `backend/src/main/java/com/keplerops/groundcontrol/api/verification/VerificationResultController.java`

```
@RestController
@RequestMapping("/api/v1/verification-results")
```

Endpoints (following ControlController pattern):
- `POST /` -- create, returns 201
- `GET /` -- list by project, optional `requirementId` and `targetId` query params for filtering
- `GET /{id}` -- get by ID
- `PUT /{id}` -- update
- `DELETE /{id}` -- delete, returns 204

All endpoints accept `@RequestParam(required = false) String project`.

The list endpoint supports optional filtering:
- `GET /api/v1/verification-results?project=foo` -- all results for project
- `GET /api/v1/verification-results?project=foo&requirementId=UUID` -- filter by requirement
- `GET /api/v1/verification-results?project=foo&targetId=UUID` -- filter by target

---

## Phase 3: Database Migrations

### Step 3.1: Main table migration

**File:** `backend/src/main/resources/db/migration/V049__create_verification_result.sql`

```sql
CREATE TABLE verification_result (
    id                UUID PRIMARY KEY,
    project_id        UUID         NOT NULL REFERENCES project(id),
    target_id         UUID         REFERENCES traceability_link(id),
    requirement_id    UUID         REFERENCES requirement(id),
    prover            VARCHAR(50)  NOT NULL,
    property          TEXT,
    result            VARCHAR(20)  NOT NULL,
    assurance_level   VARCHAR(5),
    evidence          TEXT,
    verified_at       TIMESTAMPTZ  NOT NULL,
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_verification_result_project ON verification_result (project_id);
CREATE INDEX idx_verification_result_target ON verification_result (target_id);
CREATE INDEX idx_verification_result_requirement ON verification_result (requirement_id);
CREATE INDEX idx_verification_result_result ON verification_result (result);
CREATE INDEX idx_verification_result_prover ON verification_result (project_id, prover);
CREATE INDEX idx_verification_result_verified_at ON verification_result (project_id, verified_at);
```

Design notes on indexes:
- `idx_verification_result_project` -- required for all project-scoped queries
- `idx_verification_result_target` / `idx_verification_result_requirement` -- for FK-based filtering
- `idx_verification_result_result` -- for queries like "show all REFUTED results"
- `idx_verification_result_prover` -- for queries like "show all openjml-esc results in this project"
- `idx_verification_result_verified_at` -- for time-range queries and ordering

### Step 3.2: Audit table migration

**File:** `backend/src/main/resources/db/migration/V050__create_verification_result_audit.sql`

```sql
CREATE TABLE verification_result_audit (
    id                UUID         NOT NULL,
    rev               INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype           SMALLINT     NOT NULL,
    prover            VARCHAR(50),
    property          TEXT,
    result            VARCHAR(20),
    assurance_level   VARCHAR(5),
    evidence          TEXT,
    verified_at       TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
```

Note: The audit table omits `project_id`, `target_id`, and `requirement_id` because those ManyToOne relationships are `@NotAudited` (following the Control.java pattern where project_id is excluded from the audit table).

---

## Phase 4: Update Existing Files

### Step 4.1: Add VERIFICATION_RESULT to GraphEntityType

**File:** `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphEntityType.java`

Add `VERIFICATION_RESULT` to the enum. Current last entry is `CONTROL_LINK`.

### Step 4.2: Update MigrationSmokeTest

**File:** `backend/src/test/java/com/keplerops/groundcontrol/integration/MigrationSmokeTest.java`

1. Add `"049", "050"` to the `containsExactly()` assertion in `allFlywayMigrationsRan()`
2. Add table existence checks in `auditTablesExist()`:
   ```java
   entityManager.createNativeQuery("SELECT 1 FROM verification_result LIMIT 1").getResultList();
   entityManager.createNativeQuery("SELECT 1 FROM verification_result_audit LIMIT 1").getResultList();
   ```

---

## Phase 5: Tests

### Step 5.1: Service unit test

**File:** `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/VerificationResultServiceTest.java`

Follow `ControlServiceTest.java` pattern exactly:
- `@ExtendWith(MockitoExtension.class)`
- `@Mock` VerificationResultRepository, ProjectService, RequirementRepository, TraceabilityLinkRepository
- `@InjectMocks` VerificationResultService
- `@Nested` classes: Create, Update, GetById, ListByProject, ListByRequirement, Delete

Test cases:
- **Create**: creates result with required fields, logs, returns entity
- **Create with targetId**: validates target exists, sets relationship
- **Create with requirementId**: validates requirement exists, sets relationship
- **Create with invalid targetId**: throws NotFoundException
- **Create with invalid requirementId**: throws NotFoundException
- **Update**: applies non-null fields, preserves others
- **GetById**: returns result
- **GetById not found**: throws NotFoundException
- **ListByProject**: returns list
- **ListByRequirement**: filters by requirement
- **ListByTarget**: filters by target
- **Delete**: deletes and logs

### Step 5.2: Controller unit test

**File:** `backend/src/test/java/com/keplerops/groundcontrol/unit/api/VerificationResultControllerTest.java`

Follow `ControlControllerTest.java` pattern exactly:
- `@WebMvcTest(VerificationResultController.class)`
- `@MockitoBean` VerificationResultService, ProjectService
- Helper `makeVerificationResult()` that creates a VerificationResult with `TestUtil.setField()` for id/createdAt/updatedAt

Test cases:
- **createReturns201**: POST with valid JSON, assert 201 and response fields
- **listReturnsResults**: GET list, assert array response
- **getByIdReturnsResult**: GET by ID, assert fields
- **updateReturnsUpdatedResult**: PUT with partial fields, assert update
- **deleteReturns204**: DELETE, verify service called
- **listFiltersByRequirement**: GET with requirementId param, verify service called with correct args
- **listFiltersByTarget**: GET with targetId param, verify service called with correct args

---

## Phase 6: MCP Layer

### Step 6.1: Add constants and API functions to lib.js

**File:** `mcp/ground-control/lib.js`

Add after the Control section (after line ~1687, before the Methodology Profile section):

Constants:
```javascript
export const VERIFICATION_STATUSES = ["PROVEN", "REFUTED", "TIMEOUT", "UNKNOWN", "ERROR"];
export const ASSURANCE_LEVELS = ["L0", "L1", "L2", "L3"];
```

API functions (5 functions):
```javascript
export async function createVerificationResult(data, project) {
  return request("POST", "/api/v1/verification-results", { body: data, params: { project } });
}

export async function listVerificationResults({ requirementId, targetId, project } = {}) {
  return request("GET", "/api/v1/verification-results", {
    params: { requirementId, targetId, project },
  });
}

export async function getVerificationResult(id, project) {
  return request("GET", `/api/v1/verification-results/${encodeURIComponent(id)}`, {
    params: { project },
  });
}

export async function updateVerificationResult(id, data, project) {
  return request("PUT", `/api/v1/verification-results/${encodeURIComponent(id)}`, {
    body: data,
    params: { project },
  });
}

export async function deleteVerificationResult(id, project) {
  await request("DELETE", `/api/v1/verification-results/${encodeURIComponent(id)}`, {
    params: { project },
  });
}
```

### Step 6.2: Add tool definitions to index.js

**File:** `mcp/ground-control/index.js`

Add imports at the top (in the import block from `./lib.js`):
```javascript
createVerificationResult,
listVerificationResults,
getVerificationResult,
updateVerificationResult,
deleteVerificationResult,
VERIFICATION_STATUSES,
ASSURANCE_LEVELS,
```

Add 5 tool definitions before the `// Start` section (before line ~3670):

**gc_create_verification_result** -- Create a verification result from any prover/verifier.
- Schema: `target_id` (uuid, optional), `requirement_id` (uuid, optional), `prover` (string, required, max 50), `property` (string, optional), `result` (enum VERIFICATION_STATUSES, required), `assurance_level` (enum ASSURANCE_LEVELS, optional), `evidence` (record, optional), `verified_at` (string/ISO instant, required), `expires_at` (string/ISO instant, optional), `project` (string, optional)

**gc_list_verification_results** -- List verification results, optionally filtered by requirement or target.
- Schema: `requirement_id` (uuid, optional), `target_id` (uuid, optional), `project` (string, optional)

**gc_get_verification_result** -- Get a verification result by UUID.
- Schema: `id` (uuid, required), `project` (string, optional)

**gc_update_verification_result** -- Update a verification result.
- Schema: `id` (uuid, required), `prover` (string, optional), `property` (string, optional), `result` (enum, optional), `assurance_level` (enum, optional), `evidence` (record, optional), `verified_at` (string, optional), `expires_at` (string, optional), `project` (string, optional)

**gc_delete_verification_result** -- Delete a verification result.
- Schema: `id` (uuid, required), `project` (string, optional)

---

## File Creation/Modification Summary

### New files to create (15 files):

| # | File | Description |
|---|------|-------------|
| 1 | `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/state/VerificationStatus.java` | Result enum |
| 2 | `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/state/AssuranceLevel.java` | Assurance level enum |
| 3 | `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/model/VerificationResult.java` | JPA entity |
| 4 | `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/repository/VerificationResultRepository.java` | Spring Data repository |
| 5 | `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/service/CreateVerificationResultCommand.java` | Create command record |
| 6 | `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/service/UpdateVerificationResultCommand.java` | Update command record |
| 7 | `backend/src/main/java/com/keplerops/groundcontrol/domain/verification/service/VerificationResultService.java` | Domain service |
| 8 | `backend/src/main/java/com/keplerops/groundcontrol/api/verification/VerificationResultRequest.java` | Create request DTO |
| 9 | `backend/src/main/java/com/keplerops/groundcontrol/api/verification/UpdateVerificationResultRequest.java` | Update request DTO |
| 10 | `backend/src/main/java/com/keplerops/groundcontrol/api/verification/VerificationResultResponse.java` | Response DTO |
| 11 | `backend/src/main/java/com/keplerops/groundcontrol/api/verification/VerificationResultController.java` | REST controller |
| 12 | `backend/src/main/resources/db/migration/V049__create_verification_result.sql` | Main table migration |
| 13 | `backend/src/main/resources/db/migration/V050__create_verification_result_audit.sql` | Audit table migration |
| 14 | `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/VerificationResultServiceTest.java` | Service unit test |
| 15 | `backend/src/test/java/com/keplerops/groundcontrol/unit/api/VerificationResultControllerTest.java` | Controller unit test |

### Existing files to modify (4 files):

| # | File | Change |
|---|------|--------|
| 1 | `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/model/GraphEntityType.java` | Add `VERIFICATION_RESULT` enum value |
| 2 | `backend/src/test/java/com/keplerops/groundcontrol/integration/MigrationSmokeTest.java` | Add V049, V050 to version list; add table existence checks |
| 3 | `mcp/ground-control/lib.js` | Add 2 constants + 5 API functions (~30 lines) |
| 4 | `mcp/ground-control/index.js` | Add 7 imports + 5 tool definitions (~120 lines) |

---

## Implementation Order

Execute in this exact sequence to maintain compilability at each step:

1. **Enums first** (Steps 1.1, 1.2) -- no dependencies
2. **GraphEntityType update** (Step 4.1) -- no dependencies, needed by Response DTO
3. **Entity** (Step 1.3) -- depends on enums, BaseEntity, Project, Requirement, TraceabilityLink
4. **Repository** (Step 1.4) -- depends on entity
5. **Command records** (Step 1.5) -- depends on enums
6. **Service** (Step 1.6) -- depends on repository, commands, ProjectService
7. **DTOs** (Steps 2.1, 2.2, 2.3) -- depends on entity, enums, GraphEntityType, GraphIds
8. **Controller** (Step 2.4) -- depends on service, DTOs, ProjectService
9. **Migrations** (Steps 3.1, 3.2) -- independent of Java code, but must be present for integration tests
10. **MigrationSmokeTest update** (Step 4.2) -- depends on migrations
11. **Service test** (Step 5.1) -- depends on service, commands, entity
12. **Controller test** (Step 5.2) -- depends on controller, DTOs, service
13. **MCP lib.js** (Step 6.1) -- independent of backend
14. **MCP index.js** (Step 6.2) -- depends on lib.js additions

---

## Dependency Considerations

### Repository imports needed in VerificationResultService

The service needs to resolve FK references. Preferred approach: inject `TraceabilityLinkRepository` and `RequirementRepository` directly. The service calls `findById()` on them to resolve the FK entities, then passes them to the entity setters. This matches how ControlService injects ProjectService to resolve project references.

Both repositories exist at:
- `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/repository/TraceabilityLinkRepository.java`
- `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/repository/RequirementRepository.java`

Both have `findById()` from JpaRepository. We validate project ownership:
- For Requirement: `requirement.getProject().getId().equals(projectId)`
- For TraceabilityLink: `traceabilityLink.getRequirement().getProject().getId().equals(projectId)`

### No circular dependency risk

The verification domain depends on:
- `domain/projects/` (ProjectService for project resolution)
- `domain/requirements/` (RequirementRepository, TraceabilityLinkRepository for FK resolution)

Neither of those will depend on the verification domain.

---

## Potential Challenges

1. **TraceabilityLink project validation**: TraceabilityLink does not have a direct `project_id` column. It belongs to a Requirement which belongs to a Project. The service must traverse `traceabilityLink.getRequirement().getProject()` to validate project ownership. Since TraceabilityLink has `@ManyToOne(fetch = FetchType.LAZY)` for `requirement`, this will trigger a lazy load -- acceptable for create operations.

2. **evidence field serialization**: The `StringObjectMapConverter` stores as TEXT, not native JSONB. This matches the existing pattern (Control.methodology_factors, Control.effectiveness). The migration column type must be `TEXT` (not `JSONB`), consistent with existing migrations.

3. **verifiedAt vs createdAt ordering**: The list endpoint orders by `verifiedAt DESC`, not `createdAt DESC`. A verification result might be created in the database after the actual verification ran (batch import scenario). The `verifiedAt` field captures the actual verification timestamp.

4. **MCP snake_case conversion**: The `request()` function in lib.js auto-converts between snake_case (MCP) and camelCase (Java REST API). Fields like `target_id` in MCP become `targetId` in the JSON body sent to the backend. No special handling needed.

5. **Controller list endpoint branching**: The list endpoint has three paths (all results, filter by requirement, filter by target). The controller should check which optional params are provided and call the appropriate service method. This is straightforward conditional logic in the controller.

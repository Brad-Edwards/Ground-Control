// Enums
//
// The requirement/traceability enum vocabularies below mirror the backend Java
// enums under `domain/requirements/state/`, which are the single source of truth
// (ADR-034). `tools/policy/checks.py::run_enum_contract_check` (run by
// `bin/policy` in CI) fails the build on any drift; `enum-contract.test.ts` is
// the developer-local mirror of that gate.
export type Status = "DRAFT" | "ACTIVE" | "DEPRECATED" | "ARCHIVED";
export const STATUSES: Status[] = ["DRAFT", "ACTIVE", "DEPRECATED", "ARCHIVED"];
export type Priority = "MUST" | "SHOULD" | "COULD" | "WONT";
export const PRIORITIES: Priority[] = ["MUST", "SHOULD", "COULD", "WONT"];
export type ControlFunction =
  | "PREVENTIVE"
  | "DETECTIVE"
  | "CORRECTIVE"
  | "COMPENSATING";
export const CONTROL_FUNCTIONS: ControlFunction[] = [
  "PREVENTIVE",
  "DETECTIVE",
  "CORRECTIVE",
  "COMPENSATING",
];
export type MappingControlRole =
  | "PREVENTIVE"
  | "DETECTIVE"
  | "CORRECTIVE"
  | "DETERRENT"
  | "COMPENSATING"
  | "RECOVERY"
  | "DIRECTIVE";
export const MAPPING_CONTROL_ROLES: MappingControlRole[] = [
  "PREVENTIVE",
  "DETECTIVE",
  "CORRECTIVE",
  "DETERRENT",
  "COMPENSATING",
  "RECOVERY",
  "DIRECTIVE",
];
export type RequirementType =
  | "FUNCTIONAL"
  | "NON_FUNCTIONAL"
  | "CONSTRAINT"
  | "INTERFACE";
export const REQUIREMENT_TYPES: RequirementType[] = [
  "FUNCTIONAL",
  "NON_FUNCTIONAL",
  "CONSTRAINT",
  "INTERFACE",
];
export type RelationType =
  | "PARENT"
  | "DEPENDS_ON"
  | "CONFLICTS_WITH"
  | "REFINES"
  | "SUPERSEDES"
  | "RELATED";
export const RELATION_TYPES: RelationType[] = [
  "PARENT",
  "DEPENDS_ON",
  "CONFLICTS_WITH",
  "REFINES",
  "SUPERSEDES",
  "RELATED",
];
export type ArtifactType =
  | "GITHUB_ISSUE"
  | "PULL_REQUEST"
  | "CODE_FILE"
  | "ADR"
  | "CONFIG"
  | "POLICY"
  | "TEST"
  | "SPEC"
  | "PROOF"
  | "DOCUMENTATION"
  | "RISK_SCENARIO"
  | "CONTROL";
export const ARTIFACT_TYPES: ArtifactType[] = [
  "GITHUB_ISSUE",
  "PULL_REQUEST",
  "CODE_FILE",
  "ADR",
  "CONFIG",
  "POLICY",
  "TEST",
  "SPEC",
  "PROOF",
  "DOCUMENTATION",
  "RISK_SCENARIO",
  "CONTROL",
];
export type LinkType =
  | "IMPLEMENTS"
  | "TESTS"
  | "DOCUMENTS"
  | "CONSTRAINS"
  | "VERIFIES";
export const LINK_TYPES: LinkType[] = [
  "IMPLEMENTS",
  "TESTS",
  "DOCUMENTS",
  "CONSTRAINS",
  "VERIFIES",
];
export type SyncStatus = "SYNCED" | "STALE" | "BROKEN";
export type RevisionType = "ADD" | "MOD" | "DEL";
export type PackType = "CONTROL_PACK" | "REQUIREMENTS_PACK" | "CUSTOM";
export type CatalogStatus = "AVAILABLE" | "WITHDRAWN" | "SUPERSEDED";
export type PackRegistryImportFormat = "AUTO" | "OSCAL_JSON" | "GC_MANIFEST";
export const PACK_REGISTRY_IMPORT_FORMATS: PackRegistryImportFormat[] = [
  "AUTO",
  "OSCAL_JSON",
  "GC_MANIFEST",
];
export type TestCaseStatus = "DRAFT" | "APPROVED" | "DEPRECATED" | "ARCHIVED";
export const TEST_CASE_STATUSES: TestCaseStatus[] = [
  "DRAFT",
  "APPROVED",
  "DEPRECATED",
  "ARCHIVED",
];
export type TestCaseType = "MANUAL" | "AUTOMATED" | "HYBRID";
export const TEST_CASE_TYPES: TestCaseType[] = [
  "MANUAL",
  "AUTOMATED",
  "HYBRID",
];
export type TestCasePriority = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";
export const TEST_CASE_PRIORITIES: TestCasePriority[] = [
  "CRITICAL",
  "HIGH",
  "MEDIUM",
  "LOW",
];

// TC-002 / ADR-041 — step-based test case format. Rich-text fields hold
// CommonMark Markdown by convention (inline images via `![alt](url)`).
// Mirrors backend TestCaseStepResponse / TestCaseStepRequest field-for-field.
export interface TestCaseStepResponse {
  id: string;
  testCaseId: string;
  stepNumber: number;
  action: string;
  expectedResult: string;
  actualResult: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TestCaseStepRequest {
  stepNumber: number;
  action: string;
  expectedResult: string;
  actualResult?: string | null;
}

export interface UpdateTestCaseStepRequest {
  stepNumber?: number;
  action?: string;
  expectedResult?: string;
  actualResult?: string | null;
  clearActualResult?: boolean;
}

// TC-004 / ADR-042 — authored test-case format axis. Mirrors backend
// TestCaseFormat.java. Set on test-case create and immutable thereafter.
export type TestCaseFormat = "STEP_BASED" | "GHERKIN";
export const TEST_CASE_FORMATS: TestCaseFormat[] = ["STEP_BASED", "GHERKIN"];

// Mirrors backend TestCaseGherkinResponse / TestCaseGherkinRequest. The
// backend stores .feature source verbatim; clients render with a Gherkin-aware
// syntax highlighter at their discretion (no HTML sink is wired through this
// surface, per ADR-042 §Rendering).
export interface TestCaseGherkinResponse {
  id: string;
  testCaseId: string;
  source: string;
  createdAt: string;
  updatedAt: string;
}

export interface TestCaseGherkinRequest {
  source: string;
}

export interface UpdateTestCaseGherkinRequest {
  source: string;
}

// TC-005 / ADR-043 — Hierarchical test repository organisation. Mirrors
// the backend TestCaseFolderRequest / Response and the move/copy/reorder
// request shapes.
export interface TestCaseFolderResponse {
  id: string;
  projectIdentifier: string;
  parentFolderId: string | null;
  title: string;
  description: string | null;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
}

export interface TestCaseFolderRequest {
  parentFolderId?: string | null;
  title: string;
  description?: string | null;
  sortOrder?: number | null;
}

export interface UpdateTestCaseFolderRequest {
  title?: string;
  description?: string | null;
  clearDescription?: boolean;
}

export interface MoveTestCaseFolderRequest {
  parentFolderId: string | null;
  sortOrder?: number | null;
}

export interface ReorderTestCaseFoldersRequest {
  parentFolderId: string | null;
  orderedFolderIds: string[];
}

export interface MoveTestCaseRequest {
  parentFolderId: string | null;
  sortOrder?: number | null;
}

export interface CopyTestCaseRequest {
  newUid: string;
  parentFolderId?: string | null;
  sortOrder?: number | null;
}

export interface ReorderTestCasesRequest {
  parentFolderId: string | null;
  orderedTestCaseIds: string[];
}

export type TestCaseTreeNodeKind = "FOLDER" | "TEST_CASE";

export interface TestCaseTreeLeaf {
  uid: string;
  status: TestCaseStatus;
  type: TestCaseType;
  priority: TestCasePriority;
  format: TestCaseFormat;
}

export interface TestCaseTreeNode {
  kind: TestCaseTreeNodeKind;
  id: string;
  parentFolderId: string | null;
  title: string;
  description: string | null;
  sortOrder: number;
  // Populated for kind === "TEST_CASE"; null for folders.
  testCase: TestCaseTreeLeaf | null;
  // Folder nodes carry nested children (folders first by sortOrder, then
  // test cases). Test-case nodes carry an empty children array.
  children: TestCaseTreeNode[];
}

// TC-006 / ADR-044 — Top-level planning aggregate. Mirrors backend
// TestPlanResponse / TestPlanRequest / UpdateTestPlanRequest /
// TestPlanStatusTransitionRequest field-for-field; enum is single-sourced
// per ADR-034.
export type TestPlanStatus =
  | "DRAFT"
  | "ACTIVE"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "ARCHIVED";
export const TEST_PLAN_STATUSES: TestPlanStatus[] = [
  "DRAFT",
  "ACTIVE",
  "IN_PROGRESS",
  "COMPLETED",
  "ARCHIVED",
];

export interface TestPlanResponse {
  id: string;
  projectIdentifier: string;
  uid: string;
  name: string;
  description: string | null;
  product: string | null;
  version: string | null;
  build: string | null;
  status: TestPlanStatus;
  // ISO-8601 date strings (YYYY-MM-DD); LocalDate on the backend.
  startDate: string | null;
  endDate: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TestPlanRequest {
  uid: string;
  name: string;
  description?: string | null;
  product?: string | null;
  version?: string | null;
  build?: string | null;
  startDate?: string | null;
  endDate?: string | null;
}

export interface UpdateTestPlanRequest {
  name?: string;
  description?: string | null;
  product?: string | null;
  version?: string | null;
  build?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  clearDescription?: boolean;
  clearProduct?: boolean;
  clearVersion?: boolean;
  clearBuild?: boolean;
  clearStartDate?: boolean;
  clearEndDate?: boolean;
}

export interface TestPlanStatusTransitionRequest {
  status: TestPlanStatus;
}

// TC-007 / ADR-047 — Selection container for test cases. Mirrors backend
// TestSuiteResponse / TestSuiteRequest / UpdateTestSuiteRequest /
// AddTestSuiteMemberRequest / etc. The enum is single-sourced per ADR-034
// convention (today the policy gate scope is requirement/state enums; the
// test-management enums follow the same pattern manually).
export type TestSuitePopulationMode = "STATIC" | "REQUIREMENTS_BASED" | "QUERY_BASED";
export const TEST_SUITE_POPULATION_MODES: TestSuitePopulationMode[] = [
  "STATIC",
  "REQUIREMENTS_BASED",
  "QUERY_BASED",
];

export interface TestSuiteResponse {
  id: string;
  projectIdentifier: string;
  uid: string;
  name: string;
  description: string | null;
  populationMode: TestSuitePopulationMode;
  // QUERY_BASED criteria — null on STATIC / REQUIREMENTS_BASED suites.
  criteriaStatus: TestCaseStatus | null;
  criteriaType: TestCaseType | null;
  criteriaPriority: TestCasePriority | null;
  criteriaFormat: TestCaseFormat | null;
  criteriaFolderId: string | null;
  criteriaTextSearch: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TestSuiteRequest {
  uid: string;
  name: string;
  description?: string | null;
  populationMode: TestSuitePopulationMode;
  criteriaStatus?: TestCaseStatus | null;
  criteriaType?: TestCaseType | null;
  criteriaPriority?: TestCasePriority | null;
  criteriaFormat?: TestCaseFormat | null;
  criteriaFolderId?: string | null;
  criteriaTextSearch?: string | null;
}

export interface UpdateTestSuiteRequest {
  name?: string;
  description?: string | null;
  criteriaStatus?: TestCaseStatus | null;
  criteriaType?: TestCaseType | null;
  criteriaPriority?: TestCasePriority | null;
  criteriaFormat?: TestCaseFormat | null;
  criteriaFolderId?: string | null;
  criteriaTextSearch?: string | null;
  clearDescription?: boolean;
  clearCriteriaStatus?: boolean;
  clearCriteriaType?: boolean;
  clearCriteriaPriority?: boolean;
  clearCriteriaFormat?: boolean;
  clearCriteriaFolderId?: boolean;
  clearCriteriaTextSearch?: boolean;
}

export interface TestSuiteMemberResponse {
  id: string;
  testSuiteId: string;
  testCaseId: string;
  testCaseUid: string;
  position: number;
  createdAt: string;
  updatedAt: string;
}

export interface AddTestSuiteMemberRequest {
  testCaseId: string;
  position?: number;
}

export interface ReorderTestSuiteMembersRequest {
  orderedTestCaseIds: string[];
}

export interface TestSuiteSourceRequirementResponse {
  id: string;
  testSuiteId: string;
  requirementId: string;
  requirementUid: string;
  createdAt: string;
  updatedAt: string;
}

export interface AddTestSuiteSourceRequirementRequest {
  requirementId: string;
}

// TC-008 / ADR-049 — TestRun aggregate. Mirrors the backend Java
// TestRunResponse / TestRunRequest / UpdateTestRunRequest /
// TestRunStatusTransitionRequest field-for-field. Both enums are single-sourced
// in the domain `state/` package per ADR-034 and mirrored here for typed UI
// access.

export type TestRunStatus = "PLANNED" | "IN_PROGRESS" | "COMPLETED" | "ABORTED" | "ARCHIVED";

export const TEST_RUN_STATUSES: TestRunStatus[] = [
  "PLANNED",
  "IN_PROGRESS",
  "COMPLETED",
  "ABORTED",
  "ARCHIVED",
];

export type TestRunCaseResultStatus = "NOT_RUN" | "PASSED" | "FAILED" | "BLOCKED" | "SKIPPED";

export const TEST_RUN_CASE_RESULT_STATUSES: TestRunCaseResultStatus[] = [
  "NOT_RUN",
  "PASSED",
  "FAILED",
  "BLOCKED",
  "SKIPPED",
];

export interface TestRunResponse {
  id: string;
  projectIdentifier: string;
  uid: string;
  name: string;
  testPlanId: string;
  testPlanUid: string;
  testSuiteId: string;
  testSuiteUid: string;
  // Nullable on the backend (Jackson serializes present-with-null), so the
  // mirror models `string | null` rather than optional-only — matches the
  // TestPlanResponse pattern and ADR-034.
  environment: string | null;
  version: string | null;
  build: string | null;
  status: TestRunStatus;
  // ISO-8601 timestamp strings; Instant on the backend.
  startAt: string | null;
  endAt: string | null;
  // TC-009 / ADR-050 — pause/resume cursor. Both axes nullable.
  currentCaseResultId: string | null;
  currentStepResultId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TestRunRequest {
  uid: string;
  name: string;
  testPlanId: string;
  testSuiteId: string;
  environment?: string | null;
  version?: string | null;
  build?: string | null;
  startAt?: string | null;
  endAt?: string | null;
}

export interface UpdateTestRunRequest {
  name?: string;
  environment?: string | null;
  version?: string | null;
  build?: string | null;
  startAt?: string | null;
  endAt?: string | null;
  clearEnvironment?: boolean;
  clearVersion?: boolean;
  clearBuild?: boolean;
  clearStartAt?: boolean;
  clearEndAt?: boolean;
}

export interface TestRunStatusTransitionRequest {
  status: TestRunStatus;
}

export interface TestRunTesterAssignmentResponse {
  id: string;
  testRunId: string;
  testerName: string;
  createdAt: string;
  updatedAt: string;
}

export interface AddTestRunTesterRequest {
  testerName: string;
}

export interface TestRunCaseResultResponse {
  id: string;
  testRunId: string;
  testCaseId: string;
  testCaseUid: string;
  testCaseTitle: string;
  snapshotOrder: number;
  status: TestRunCaseResultStatus;
  // Notes are nullable on the backend (TEXT column with no default).
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateTestRunCaseResultRequest {
  // TC-009 / ADR-050: status is intentionally optional. The runner's
  // notes-only autosave omits it so a stale local status snapshot can't
  // revert a concurrent flip.
  status?: TestRunCaseResultStatus;
  notes?: string | null;
  clearNotes?: boolean;
}

// TC-009 / ADR-050 — Per-step execution result on a TestRunCaseResult.
// Snapshot fields capture authored step content at run-create time.
export interface TestRunStepResultResponse {
  id: string;
  testRunCaseResultId: string;
  testCaseStepId: string;
  stepNumberSnapshot: number;
  actionSnapshot: string;
  expectedResultSnapshot: string;
  snapshotOrder: number;
  status: TestRunCaseResultStatus;
  comment: string | null;
  executedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateTestRunStepResultRequest {
  // Status is optional so a comment-only autosave doesn't carry a stale
  // status snapshot through to the backend (see UpdateTestRunCaseResultRequest).
  status?: TestRunCaseResultStatus;
  comment?: string | null;
  clearComment?: boolean;
  executedAt?: string | null;
  clearExecutedAt?: boolean;
}

export interface UpdateTestRunCursorRequest {
  currentCaseResultId?: string | null;
  currentStepResultId?: string | null;
  clearCursor?: boolean;
}

export type GraphEntityType =
  | "REQUIREMENT"
  | "OPERATIONAL_ASSET"
  | "OBSERVATION"
  | "RISK_SCENARIO"
  | "RISK_REGISTER_RECORD"
  | "RISK_ASSESSMENT_RESULT"
  | "TREATMENT_PLAN"
  | "METHODOLOGY_PROFILE"
  | "EVIDENCE_ARTIFACT";

// GC-M012 asset ownership / criticality / scope vocabularies. Mirrors the
// backend `AssetCriticality`, `AssetEnvironment`, and `AssetScope` enums; ADR-012
// records them as L0 pure value enums. Distinct from `quality_gate.scopeStatus`,
// control `implementationScope`, and risk `assetScopeSummary`.
export type AssetCriticality = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";
export const ASSET_CRITICALITIES: AssetCriticality[] = [
  "CRITICAL",
  "HIGH",
  "MEDIUM",
  "LOW",
];

export type AssetEnvironment =
  | "PRODUCTION"
  | "STAGING"
  | "DEVELOPMENT"
  | "TEST"
  | "NON_PRODUCTION"
  | "OTHER";
export const ASSET_ENVIRONMENTS: AssetEnvironment[] = [
  "PRODUCTION",
  "STAGING",
  "DEVELOPMENT",
  "TEST",
  "NON_PRODUCTION",
  "OTHER",
];

export type AssetScope = "IN_SCOPE" | "OUT_OF_SCOPE";
export const ASSET_SCOPES: AssetScope[] = ["IN_SCOPE", "OUT_OF_SCOPE"];

// GC-M018 knowledge / completeness dimension. Mirrors the backend
// `KnowledgeState` enum. Distinct from confidence, asset type, asset scope,
// and the (subtype, metadata) bag — see
// architecture/notes/partial-knowledge-unknown-dependency-preflight.md.
export type KnowledgeState = "CONFIRMED" | "PROVISIONAL" | "UNKNOWN";
export const KNOWLEDGE_STATES: KnowledgeState[] = [
  "CONFIRMED",
  "PROVISIONAL",
  "UNKNOWN",
];

// GC-U001 / ADR-047 Audit entity enums. Single-sourced from backend
// AuditStatus, AuditType, AuditPhaseKind, AuditLinkTargetType, AuditLinkType.
export type AuditType = "INTERNAL" | "EXTERNAL" | "REGULATORY" | "SPECIAL";
export const AUDIT_TYPES: AuditType[] = [
  "INTERNAL",
  "EXTERNAL",
  "REGULATORY",
  "SPECIAL",
];

export type AuditStatus =
  | "PLANNED"
  | "IN_PROGRESS"
  | "DRAFT_REPORT"
  | "FINAL_REPORT"
  | "CLOSED";
export const AUDIT_STATUSES: AuditStatus[] = [
  "PLANNED",
  "IN_PROGRESS",
  "DRAFT_REPORT",
  "FINAL_REPORT",
  "CLOSED",
];

export type AuditPhaseKind = "PLANNING" | "FIELDWORK" | "REPORTING" | "FOLLOWUP";
export const AUDIT_PHASE_KINDS: AuditPhaseKind[] = [
  "PLANNING",
  "FIELDWORK",
  "REPORTING",
  "FOLLOWUP",
];

export type AuditLinkTargetType =
  | "FRAMEWORK"
  | "ASSET"
  | "CONTROL"
  | "RISK_SCENARIO"
  | "RISK_REGISTER_RECORD"
  | "EVIDENCE"
  | "FINDING"
  | "EXTERNAL";
export const AUDIT_LINK_TARGET_TYPES: AuditLinkTargetType[] = [
  "FRAMEWORK",
  "ASSET",
  "CONTROL",
  "RISK_SCENARIO",
  "RISK_REGISTER_RECORD",
  "EVIDENCE",
  "FINDING",
  "EXTERNAL",
];

export type AuditLinkType =
  | "SCOPES"
  | "ASSESSES"
  | "EVIDENCED_BY"
  | "FOLLOWS_UP_ON"
  | "ASSOCIATED";
export const AUDIT_LINK_TYPES: AuditLinkType[] = [
  "SCOPES",
  "ASSESSES",
  "EVIDENCED_BY",
  "FOLLOWS_UP_ON",
  "ASSOCIATED",
];

// Responses
export interface ProjectResponse {
  id: string;
  identifier: string;
  name: string;
  description: string;
  createdAt: string;
  updatedAt: string;
}

export interface RequirementResponse {
  id: string;
  graphNodeId: string;
  uid: string;
  projectIdentifier: string;
  title: string;
  statement: string;
  rationale: string;
  requirementType: RequirementType;
  priority: Priority;
  status: Status;
  wave: number;
  createdAt: string;
  updatedAt: string;
  archivedAt: string | null;
}

export interface RelationResponse {
  id: string;
  sourceId: string;
  sourceUid: string;
  targetId: string;
  targetUid: string;
  relationType: RelationType;
  createdAt: string;
}

export interface GraphVisualizationNodeResponse {
  id: string;
  domainId: string;
  entityType: GraphEntityType | string;
  projectIdentifier: string;
  uid: string | null;
  label: string;
  properties: Record<string, unknown>;
}

export interface GraphEdgeResponse {
  id: string;
  edgeType: string;
  sourceId: string;
  targetId: string;
  sourceEntityType: string;
  targetEntityType: string;
  properties: Record<string, unknown>;
}

export interface GraphVisualizationResponse {
  nodes: GraphVisualizationNodeResponse[];
  edges: GraphEdgeResponse[];
  totalNodes: number;
  totalEdges: number;
}

export interface GraphNeighborhoodResponse extends GraphVisualizationResponse {
  rootNodeIds: string[];
}

export interface GraphPathResponse {
  nodeIds: string[];
  edgeTypes: string[];
}

export interface PackDependencyResponse {
  packId: string;
  versionConstraint: string | null;
}

export interface RegisteredControlPackEntryResponse {
  uid: string;
  title: string;
  controlFunction: ControlFunction;
  description?: string | null;
  objective?: string | null;
  owner?: string | null;
  implementationScope?: string | null;
  methodologyFactors?: Record<string, unknown> | null;
  effectiveness?: Record<string, unknown> | null;
  category?: string | null;
  source?: string | null;
  implementationGuidance?: string | null;
  expectedEvidence?: Record<string, unknown>[] | null;
  frameworkMappings?: Record<string, unknown>[] | null;
}

export interface PackRegistryEntryResponse {
  id: string;
  projectIdentifier: string;
  packId: string;
  packType: PackType;
  publisher: string | null;
  version: string;
  description: string | null;
  sourceUrl: string | null;
  checksum: string | null;
  signatureInfo: Record<string, unknown> | null;
  compatibility: Record<string, unknown> | null;
  dependencies: PackDependencyResponse[] | null;
  controlPackEntries: RegisteredControlPackEntryResponse[] | null;
  provenance: Record<string, unknown> | null;
  registryMetadata: Record<string, unknown> | null;
  catalogStatus: CatalogStatus;
  registeredAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface TraceabilityLinkResponse {
  id: string;
  requirementId: string;
  artifactType: ArtifactType;
  artifactIdentifier: string;
  artifactUrl: string;
  artifactTitle: string;
  linkType: LinkType;
  syncStatus: SyncStatus;
  lastSyncedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface RequirementHistoryResponse {
  revisionNumber: number;
  revisionType: RevisionType;
  timestamp: string;
  actor: string;
  snapshot: RequirementResponse;
}

export interface RelationHistoryResponse {
  revisionNumber: number;
  revisionType: RevisionType;
  timestamp: string;
  actor: string;
  snapshot: {
    id: string;
    sourceId: string;
    targetId: string;
    relationType: RelationType;
    description: string;
    createdAt: string;
  };
}

export interface TraceabilityLinkHistoryResponse {
  revisionNumber: number;
  revisionType: RevisionType;
  timestamp: string;
  actor: string;
  snapshot: TraceabilityLinkResponse;
}

export type ChangeCategory = "REQUIREMENT" | "RELATION" | "TRACEABILITY_LINK";
export const CHANGE_CATEGORIES: ChangeCategory[] = [
  "REQUIREMENT",
  "RELATION",
  "TRACEABILITY_LINK",
];

export interface FieldChangeResponse {
  oldValue: unknown;
  newValue: unknown;
}

export interface TimelineEntryResponse {
  revisionNumber: number;
  revisionType: RevisionType;
  timestamp: string;
  actor: string;
  changeCategory: ChangeCategory;
  entityId: string;
  snapshot: Record<string, unknown>;
  changes: Record<string, FieldChangeResponse>;
}

export interface RequirementSummaryResponse {
  id: string;
  uid: string;
  title: string;
  status: Status;
  wave: number;
}

export interface CycleEdgeResponse {
  sourceUid: string;
  targetUid: string;
  relationType: RelationType;
}

export interface CycleResponse {
  members: string[];
  edges: CycleEdgeResponse[];
}

export interface RelationValidationResponse {
  id: string;
  sourceId: string;
  sourceUid: string;
  sourceWave: number;
  targetId: string;
  targetUid: string;
  targetWave: number;
  relationType: RelationType;
}

export interface ConsistencyViolationResponse {
  relationId: string;
  sourceId: string;
  sourceUid: string;
  sourceStatus: Status;
  targetId: string;
  targetUid: string;
  targetStatus: Status;
  relationType: RelationType;
  violationType: string;
}

export interface CompletenessIssueResponse {
  uid: string;
  issue: string;
}

export interface CompletenessResponse {
  total: number;
  byStatus: Record<string, number>;
  issues: CompletenessIssueResponse[];
}

export interface WaveStatsResponse {
  wave: number | null;
  total: number;
  byStatus: Record<string, number>;
}

export interface CoverageStatsResponse {
  total: number;
  covered: number;
  percentage: number;
}

export interface RecentChangeResponse {
  uid: string;
  title: string;
  revisionType: RevisionType;
  timestamp: string;
  actor: string;
}

export interface DashboardStatsResponse {
  totalRequirements: number;
  byStatus: Record<string, number>;
  byWave: WaveStatsResponse[];
  coverageByLinkType: Record<string, CoverageStatsResponse>;
  recentChanges: RecentChangeResponse[];
}

export interface BulkStatusTransitionResponse {
  succeeded: RequirementResponse[];
  failed: Array<{ id: string; error: string }>;
  totalRequested: number;
  totalSucceeded: number;
  totalFailed: number;
}

export interface SyncResultResponse {
  syncId: string;
  syncedAt: string;
  issuesFetched: number;
  issuesCreated: number;
  issuesUpdated: number;
  linksUpdated: number;
  errors: string[];
}

export interface GitHubIssueResponse {
  issueUrl: string;
  issueNumber: number;
  traceabilityLinkId: string;
  warning: string | null;
}

export interface ImportResultResponse {
  importId: string;
  importedAt: string;
  requirementsParsed: number;
  requirementsCreated: number;
  requirementsUpdated: number;
  relationsCreated: number;
  relationsSkipped: number;
  traceabilityLinksCreated: number;
  traceabilityLinksSkipped: number;
  errors: string[];
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// Requests
export interface ProjectRequest {
  identifier: string;
  name: string;
  description?: string;
}

export interface UpdateProjectRequest {
  name: string;
  description?: string;
}

export interface RequirementRequest {
  uid: string;
  title: string;
  // Backend RequirementRequest annotates `statement` @NotBlank — required on create.
  statement: string;
  rationale?: string;
  requirementType?: RequirementType;
  priority?: Priority;
  wave?: number;
}

export interface UpdateRequirementRequest {
  title: string;
  statement: string;
  rationale?: string;
  requirementType?: RequirementType;
  priority?: Priority;
  wave?: number;
}

export interface RelationRequest {
  targetId: string;
  relationType: RelationType;
}

export interface TraceabilityLinkRequest {
  artifactType: ArtifactType;
  artifactIdentifier: string;
  artifactUrl?: string;
  artifactTitle?: string;
  linkType: LinkType;
}

export interface CloneRequirementRequest {
  newUid: string;
  copyRelations?: boolean;
}

export interface GitHubIssueRequest {
  requirementUid: string;
  repo?: string;
  extraBody?: string;
  labels?: string[];
}

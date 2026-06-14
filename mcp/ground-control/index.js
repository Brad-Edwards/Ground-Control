#!/usr/bin/env node
// Ground Control MCP Server
//
// Environment variables consumed by this server (see mcp/ground-control/lib.js):
//   GC_BASE_URL                              Base URL of the Ground Control backend.
//   GROUND_CONTROL_API_TOKEN                 Bearer token forwarded on every
//                                             /api/v1/** request when the backend
//                                             has groundcontrol.security.enabled=true.
//   GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN Legacy admin-only token; forwarded only
//                                             on paths requiring ROLE_ADMIN. Fallback
//                                             when GROUND_CONTROL_API_TOKEN is unset.
//
// These values are read from the consumer repo's `.env` file at startup.
//
// ============================================================================
// CONSOLIDATED TOOL SURFACE (ADR-035)
// ============================================================================
//
// Every REST endpoint used to have its own MCP tool — 215 in total. After
// ADR-035 the surface is consolidated to ~33 named tools plus the read-only
// `gc_query` escape hatch:
//
//   Workflow primitives the /implement skill calls by name (unchanged):
//     gc_get_repo_ground_control_context, gc_codex_architecture_preflight,
//     gc_codex_review, gc_codex_verify_finding, gc_post_implementation_plan,
//     gc_create_github_issue, gc_dashboard_stats, gc_query,
//     gc_get_requirement, gc_get_traceability, gc_get_traceability_by_artifact,
//     gc_create_traceability_link, gc_delete_traceability_link,
//     gc_transition_status, gc_bulk_transition_status
//
//   Consolidated entity tools (one per entity, action-discriminated):
//     gc_requirement, gc_relation, gc_adr, gc_document, gc_section,
//     gc_asset, gc_observation, gc_risk_scenario, gc_threat_model,
//     gc_control, gc_derivation, gc_risk_governance, gc_risk_control_mapping, gc_analyze, gc_graph, gc_baseline,
//     gc_quality_gate, gc_admin, gc_pack
//
// Pure GETs (history, timeline, exports, list-by-X) are NOT registered as
// named tools — they're reachable via `gc_query` with the right /api/v1/*
// path. The allowlist covers every read prefix the curated tools used to
// expose; agents discover them via gc_get_repo_ground_control_context's
// catalog field.

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import {
  // ---- project/requirement/relation/traceability ----
  listProjects, createProject, replaceResearchIntake,
  getRequirementByUid, listRequirements, createRequirement, updateRequirement,
  transitionStatus, bulkTransitionStatus, archiveRequirement, cloneRequirement,
  createRelation, getRelations, deleteRelation,
  getTraceabilityLinks, getTraceabilityByArtifact, createTraceabilityLink,
  deleteTraceabilityLink,
  // ---- analysis ----
  detectCycles, findOrphans, findCoverageGaps, impactAnalysis,
  crossWaveValidation, detectConsistencyViolations, analyzeCompleteness,
  analyzeStatusDrift, analyzeSemanticSimilarity, getWorkOrder,
  getDashboardStats,
  // ---- GC-L007 GRC analysis ----
  analyzeEvidenceFreshness, analyzeObservationProjection, aggregateVendorRisk,
  // ---- GC-T014 NIST SP 800-30 Rev. 1 assessment ----
  analyzeNistAssessment,
  // ---- history / exports (kept for completeness even though tools route to gc_query) ----
  getRequirementHistory, getRelationHistory, getTraceabilityLinkHistory,
  getRequirementTimeline, getRequirementDiff, getProjectTimeline,
  exportAuditTimeline, exportRequirements, exportSweepReport, exportDocument,
  // ---- graph ----
  materializeGraph, getAncestors, getDescendants, findPaths,
  getGraphVisualization, extractSubgraph, traverseGraph, findGraphPaths,
  // ---- github / codex workflow ----
  createGitHubIssue, formatIssueBody,
  runSweep, runSweepAll,
  getRepoGroundControlContext,
  runCodexArchitecturePreflight, runCodexReview, runCodexVerifyFinding,
  runTestQualityReview, TEST_QUALITY_REVIEW_HARD_CAP,
  runPostImplementationPlan,
  runAssertTraceabilityReconciled, runAssertGrcReconciled, runAssertQualityGates, runCloseIssueAfterMerge,
  runPostDecisionRecord, runPostFinalReport, runRenderPrBody, runLogStepTelemetry,
  runPostGrcScreening,
  runGetIssueThread, runWatchCiRun, runWatchSonarAnalysis,
  runCodexReviewCycle, runTestQualityReviewCycle,
  startReviewJob, pollReviewJob, cancelReviewJob,
  runResolveWorkflowRoute,
  DECISION_RECORD_REVIEWERS, DECISION_RECORD_DECISIONS, DECISION_RECORD_CLASSIFICATIONS,
  GRC_SCREENING_VERDICTS,
  PR_BODY_CHANGE_CLASSES, PR_REQUIREMENT_RE, EXACT_REQUIREMENT_UID_RE,
  TELEMETRY_TIERS, TELEMETRY_OUTCOMES,
  buildCodexReviewToolDescription, buildCodexReviewOverrideCapDescription,
  buildCodexReviewOverrideReasonDescription,
  CODEX_REVIEW_HARD_CAP, CODEX_REVIEW_PREPUSH_HARD_CAP,
  // ---- embeddings ----
  embedRequirement, getEmbeddingStatus, embedProject,
  // ---- baselines + quality gates ----
  createBaseline, listBaselines, getBaseline, getBaselineSnapshot,
  compareBaselines, deleteBaseline,
  createQualityGate, listQualityGates, getQualityGate, updateQualityGate,
  deleteQualityGate, evaluateQualityGates,
  // ---- documents / sections / ADRs ----
  createDocument, listDocuments, getDocument, updateDocument, deleteDocument,
  createSection, listSections, getSectionTree, getSection, updateSection,
  deleteSection,
  addSectionContent, listSectionContent, updateSectionContent, deleteSectionContent,
  getDocumentReadingOrder,
  setDocumentGrammar, getDocumentGrammar, deleteDocumentGrammar,
  createAdr, listAdrs, getAdr, updateAdr, deleteAdr, transitionAdrStatus,
  getAdrRequirements,
  // ---- imports + sync ----
  importStrictdoc, importReqif, syncGithub, syncGithubPrs,
  // ---- assets ----
  createAsset, listAssets, getAsset, getAssetByUid, updateAsset, deleteAsset,
  archiveAsset, createAssetRelation, getAssetRelations, deleteAssetRelation,
  detectAssetCycles, assetImpactAnalysis, extractAssetSubgraph,
  createAssetLink, getAssetLinks, deleteAssetLink, getAssetLinksByTarget,
  createAssetExternalId, getAssetExternalIds, updateAssetExternalId,
  deleteAssetExternalId, findAssetByExternalId,
  registerAssetSubtypeSchema, listAssetSubtypeSchemas, getAssetSubtypeSchema,
  getActiveAssetSubtypeSchema, updateAssetSubtypeSchema, deprecateAssetSubtypeSchema,
  // ---- risk domain ----
  createObservation, listObservations, getObservation, updateObservation,
  deleteObservation, listLatestObservations,
  getEvidenceStateWorkspace,
  getControlAssuranceWorkspace, CONTROL_STATUSES, CONTROL_FUNCTIONS, CONTROL_WORKSPACE_QUEUE_REASONS,
  createRiskScenario, listRiskScenarios, getRiskScenario, updateRiskScenario,
  deleteRiskScenario, transitionRiskScenarioStatus, getRiskScenarioRequirements,
  getRiskScenarioWorkspace,
  createRiskScenarioLink, listRiskScenarioLinks, deleteRiskScenarioLink,
  createThreatModel, listThreatModels, getThreatModel, updateThreatModel,
  deleteThreatModel, transitionThreatModelStatus, getThreatModelLinkedRequirements,
  getThreatModelWorkspace,
  createThreatModelLink, listThreatModelLinks, deleteThreatModelLink,
  createMethodologyProfile, listMethodologyProfiles, getMethodologyProfile,
  updateMethodologyProfile, deleteMethodologyProfile,
  createRiskRegisterRecord, listRiskRegisterRecords, getRiskRegisterRecord,
  updateRiskRegisterRecord, transitionRiskRegisterRecordStatus, deleteRiskRegisterRecord,
  createRiskAssessmentResult, listRiskAssessmentResults, getRiskAssessmentResult,
  updateRiskAssessmentResult, transitionRiskAssessmentApprovalState,
  deleteRiskAssessmentResult,
  createTreatmentPlan, listTreatmentPlans, getTreatmentPlan, updateTreatmentPlan,
  transitionTreatmentPlanStatus, deleteTreatmentPlan,
  // ---- risk-control mapping (GC-T003 / ADR-052) ----
  createScopedControlImplementation, listScopedControlImplementations,
  getScopedControlImplementation, updateScopedControlImplementation,
  deleteScopedControlImplementation,
  createRiskControlMapping, listRiskControlMappings, getRiskControlMapping,
  updateRiskControlMapping, deleteRiskControlMapping,
  attachMappingObservation, detachMappingObservation, addMappingEvidenceRef,
  getUnmappedScenarios, getUnmappedRecords, getUnmappedControls, getAssessmentFeed,
  MAPPING_CONTROL_ROLES,
  createVerificationResult, listVerificationResults, getVerificationResult,
  updateVerificationResult, deleteVerificationResult,
  // ---- packs + plugins ----
  listPlugins, getPlugin, registerPlugin, unregisterPlugin,
  listControlPacks, getControlPack, deprecateControlPack, removeControlPack,
  listControlPackEntries, getControlPackEntry,
  createControlPackOverride, listControlPackOverrides, deleteControlPackOverride,
  registerPackRegistryEntry, importPackRegistryEntry, listPackRegistryEntries,
  listPackVersions, getPackRegistryEntry, updatePackRegistryEntry,
  withdrawPackRegistryEntry, deletePackRegistryEntry, resolvePack,
  checkPackCompatibility,
  createTrustPolicy, listTrustPolicies, getTrustPolicy, updateTrustPolicy,
  deleteTrustPolicy,
  installPackFromRegistry, upgradePackFromRegistry, listPackInstallRecords,
  getPackInstallRecord,
  // createAdminUser is intentionally NOT imported — passwords must not flow
  // through MCP tool-call payloads (ADR-037, codex security finding).
  listAdminUsers, updateAdminUserRole, updateAdminUserEnabled, deleteAdminUser,
  // ---- test cases (TC-001 / ADR-040) ----
  createTestCase, updateTestCase, deleteTestCase, transitionTestCaseStatus,
  createTestCaseGherkin, updateTestCaseGherkin, deleteTestCaseGherkin,
  TEST_CASE_STATUSES, TEST_CASE_TYPES, TEST_CASE_PRIORITIES, TEST_CASE_FORMATS,
  // ---- test case steps (TC-002 / ADR-041) ----
  createTestCaseStep, updateTestCaseStep, deleteTestCaseStep,
  // ---- test case folders + move/copy/reorder (TC-005 / ADR-043) ----
  createTestCaseFolder, updateTestCaseFolder, deleteTestCaseFolder,
  moveTestCaseFolder, reorderTestCaseFolders,
  moveTestCase, copyTestCase, reorderTestCases,
  // ---- test plans (TC-006 / ADR-044) ----
  createTestPlan, updateTestPlan, deleteTestPlan, transitionTestPlanStatus,
  TEST_PLAN_STATUSES,
  // ---- test suites (TC-007 / ADR-047) ----
  createTestSuite, updateTestSuite, deleteTestSuite,
  addTestSuiteMember, removeTestSuiteMember, reorderTestSuiteMembers,
  addTestSuiteSourceRequirement, removeTestSuiteSourceRequirement,
  resolveTestSuiteTestCases,
  TEST_SUITE_POPULATION_MODES,
  // ---- test runs (TC-008 / ADR-049) + runner (TC-009 / ADR-050) ----
  createTestRun, updateTestRun, deleteTestRun, transitionTestRunStatus,
  addTestRunTester, removeTestRunTester,
  updateTestRunCaseResult,
  listTestRunStepResults, updateTestRunStepResult, updateTestRunCursor,
  TEST_RUN_STATUSES, TEST_RUN_CASE_RESULT_STATUSES,
  // ---- enums ----
  STATUSES, REQUIREMENT_TYPES, PRIORITIES, RELATION_TYPES,
  ARTIFACT_TYPES, LINK_TYPES, CHANGE_CATEGORIES, CONFIDENCE_LEVELS,
  METRIC_TYPES, COMPARISON_OPERATORS, ADR_STATUSES,
  ASSET_TYPES, ASSET_RELATION_TYPES, ASSET_LINK_TARGET_TYPES, ASSET_LINK_TYPES,
  ASSET_CRITICALITIES, ASSET_ENVIRONMENTS, ASSET_SCOPES, KNOWLEDGE_STATES,
  OBSERVATION_CATEGORIES, RISK_SCENARIO_STATUSES,
  METHODOLOGY_FAMILIES, METHODOLOGY_PROFILE_STATUSES,
  RISK_REGISTER_STATUSES, RISK_ASSESSMENT_APPROVAL_STATUSES,
  TREATMENT_PLAN_STATUSES, TREATMENT_STRATEGIES,
  RISK_SCENARIO_LINK_TARGET_TYPES, RISK_SCENARIO_LINK_TYPES,
  THREAT_MODEL_STATUSES, STRIDE_CATEGORIES,
  THREAT_MODEL_LINK_TARGET_TYPES, THREAT_MODEL_LINK_TYPES,
  VERIFICATION_STATUSES, ASSURANCE_LEVELS,
  PLUGIN_TYPES, PLUGIN_LIFECYCLE_STATES,
  CONTROL_PACK_LIFECYCLE_STATES, CONTROL_PACK_ENTRY_STATUSES,
  PACK_TYPES, PACK_IMPORT_FORMATS, CATALOG_STATUSES,
  TRUST_OUTCOMES, INSTALL_OUTCOMES,
  TRUST_POLICY_FIELDS, TRUST_POLICY_RULE_OPERATORS,
  pick, reqArg,
  validateGovernanceStatus,
  GOVERNANCE_FIELDS,
  PR_BODY_SUMMARY_MAX,
  FINAL_REPORT_SUMMARY_MAX,
  FINAL_REPORT_REVIEW_SUMMARY_MAX,
  KNOWLEDGE_SOURCE_TYPES,
  writeKnowledgeInbox,
  classifyChangedSurface,
} from "./lib.js";
import {
  executeGcQuery,
  gcQueryToolHandler,
  gcQuerySchema,
  GC_QUERY_BODY_BYTE_CAP,
  GC_QUERY_TIMEOUT_MS,
  GC_QUERY_PATH_ALLOWLIST,
  GC_QUERY_PATH_DENYLIST,
} from "./gc-query.js";
import {
  gcThreatModelZodShape,
  gcThreatModelToolHandler,
  GC_THREAT_MODEL_DESCRIPTION,
} from "./gc-threat-model.js";
import {
  gcFindingZodShape,
  gcFindingToolHandler,
  GC_FINDING_DESCRIPTION,
} from "./gc-finding.js";
import {
  gcEvidenceZodShape,
  gcEvidenceToolHandler,
  GC_EVIDENCE_DESCRIPTION,
} from "./gc-evidence.js";
import {
  gcDerivationZodShape,
  gcDerivationToolHandler,
  GC_DERIVATION_DESCRIPTION,
} from "./gc-derivation.js";
import {
  gcAuditZodShape,
  gcAuditToolHandler,
  GC_AUDIT_DESCRIPTION,
} from "./gc-audit.js";
import {
  gcRiskScenarioZodShape,
  gcRiskScenarioToolHandler,
  GC_RISK_SCENARIO_DESCRIPTION,
} from "./gc-risk-scenario.js";
import {
  gcRiskGovernanceZodShape,
  gcRiskGovernanceToolHandler,
  GC_RISK_GOVERNANCE_DESCRIPTION,
} from "./gc-risk-governance.js";
import {
  gcControlZodShape,
  gcControlToolHandler,
  GC_CONTROL_DESCRIPTION,
} from "./gc-control.js";
import {
  linkCreateOptionalSharedZodFields,
  performLinkCreate,
} from "./link-create.js";
import {
  gcAssetZodShape,
  gcAssetToolHandler,
  GC_ASSET_DESCRIPTION,
} from "./gc-asset.js";
import {
  gcObservationZodShape,
  gcObservationToolHandler,
  GC_OBSERVATION_DESCRIPTION,
} from "./gc-observation.js";
import {
  runIntegrationManager,
  GC_INTEGRATION_MANAGER_DESCRIPTION,
  GC_INTEGRATION_MANAGER_INPUT_SCHEMA,
} from "./gc-integrate.js";

// Load .env from cwd before any auth header is composed.
function loadDotenvFromCwd() {
  let body;
  try {
    body = readFileSync(join(process.cwd(), ".env"), "utf-8");
  } catch (err) {
    if (err.code === "ENOENT") return;
    throw err;
  }
  for (const rawLine of body.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (process.env[key] === undefined || process.env[key] === "") {
      process.env[key] = value;
    }
  }
}
loadDotenvFromCwd();

function ok(text) {
  return { content: [{ type: "text", text }] };
}

function err(e) {
  let text = `Error: ${e.message}`;
  if (e && e.name === "RequestError") {
    if (e.code) text += ` (${e.code})`;
    if (e.detail && typeof e.detail === "object" && Object.keys(e.detail).length > 0) {
      text += `\nDetail: ${JSON.stringify(e.detail, null, 2)}`;
    }
  }
  return { content: [{ type: "text", text }], isError: true };
}

// pick / reqArg moved to lib.js so the extracted per-tool modules
// (gc-query.js, gc-threat-model.js, link-create.js) share the same helpers
// without coupling to this module (which boots the MCP server at import).

// Admin gating: gc_admin and gc_pack expose ROLE_ADMIN-only write/mutating
// operations. They register only when GC_MCP_ADMIN is set, so a default MCP
// session does not surface admin operations even if the launching env happens
// to have an admin bearer token configured.
const ADMIN_TOOLS_ENABLED =
  process.env.GC_MCP_ADMIN === "1" ||
  process.env.GC_MCP_ADMIN === "true" ||
  process.env.GC_MCP_ADMIN === "yes";

const server = new McpServer({ name: "ground-control", version: "1.0.0" });

// ============================================================================
// WORKFLOW PRIMITIVES — kept by name; /implement and /ship skills call these.
// ============================================================================

// `gc_query` is the only tool in this file registered with a constructed
// `z.object(...).strict()` (rather than a raw shape) so the SDK preserves
// the strict-rejection contract. The deprecated `server.tool(name, desc,
// schema, cb)` overload routes a constructed Zod object into the
// `annotations` slot instead of `inputSchema`, which makes the SDK call
// the handler with its `extra` object (containing `signal`) in the args
// position — issue #874's root cause. `server.registerTool` takes
// `inputSchema` explicitly, so the strict schema actually gates the call
// and `signal` stays in `extra` where it belongs.
server.registerTool(
  "gc_query",
  {
    description:
      `Read-only ad-hoc GET against the Ground Control REST API (ADR-035). Use this when no curated tool covers the read you need. ` +
      `Path must be a relative '/api/v1/...' string under one of the allowlisted prefixes: ${GC_QUERY_PATH_ALLOWLIST.join(", ")}. ` +
      `Admin prefixes (${GC_QUERY_PATH_DENYLIST.join(", ")}) are rejected. ` +
      `GET only; pass query params via the structured 'params' object (flat, primitive values only). ` +
      `Body cap: ${GC_QUERY_BODY_BYTE_CAP} bytes; timeout: ${GC_QUERY_TIMEOUT_MS}ms.`,
    inputSchema: gcQuerySchema,
  },
  async (args) => {
    try { return ok(JSON.stringify(await gcQueryToolHandler(args), null, 2)); }
    catch (e) { return err(e); }
  },
);

server.tool(
  "gc_get_repo_ground_control_context",
  "Read the repo's .ground-control.yaml and return the workflow config: project, github_repo, workflow commands, sonarcloud, knowledge paths, and inlined plan-rules content. Returns validation errors when the file is missing or invalid.",
  { repo_path: z.string().describe("Absolute path to the target Git repository") },
  async ({ repo_path }) => {
    try { return ok(JSON.stringify(await getRepoGroundControlContext(repo_path), null, 2)); }
    catch (e) { return err(e); }
  },
);

server.tool(
  "gc_dashboard_stats",
  "Aggregate project health snapshot: requirement counts by status/wave, traceability coverage percentages, recent changes.",
  { project: z.string().optional().describe("Project identifier (auto-resolved if only one project)") },
  async ({ project }) => {
    try { return ok(JSON.stringify(await getDashboardStats(project), null, 2)); }
    catch (e) { return err(e); }
  },
);

server.tool(
  "gc_get_requirement",
  "Get a requirement by its human-readable UID (e.g. 'GC-O007').",
  {
    uid: z.string().describe("Requirement UID"),
    project: z.string().optional(),
  },
  async ({ uid, project }) => {
    try { return ok(JSON.stringify(await getRequirementByUid(uid, project), null, 2)); }
    catch (e) { return err(e); }
  },
);

server.tool(
  "gc_get_traceability",
  "Get all traceability links for a requirement (by UUID).",
  { id: z.string().uuid().describe("Requirement UUID") },
  async ({ id }) => {
    try { return ok(JSON.stringify(await getTraceabilityLinks(id), null, 2)); }
    catch (e) { return err(e); }
  },
);

server.tool(
  "gc_get_traceability_by_artifact",
  "Reverse lookup: find all traceability links for an artifact (file path, issue number, etc.).",
  {
    artifact_type: z.enum(ARTIFACT_TYPES),
    artifact_identifier: z.string(),
  },
  async ({ artifact_type, artifact_identifier }) => {
    try { return ok(JSON.stringify(await getTraceabilityByArtifact(artifact_type, artifact_identifier), null, 2)); }
    catch (e) { return err(e); }
  },
);

server.tool(
  "gc_create_traceability_link",
  "Link an artifact to a requirement.",
  {
    requirement_id: z.string().uuid(),
    artifact_type: z.enum(ARTIFACT_TYPES),
    artifact_identifier: z.string(),
    link_type: z.enum(LINK_TYPES),
    artifact_url: z.string().optional(),
    artifact_title: z.string().optional(),
  },
  async (args) => {
    try {
      const data = pick(args, ["artifact_type", "artifact_identifier", "link_type", "artifact_url", "artifact_title"]);
      return ok(JSON.stringify(await createTraceabilityLink(args.requirement_id, data), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_delete_traceability_link",
  "Delete a traceability link.",
  {
    requirement_id: z.string().uuid(),
    link_id: z.string().uuid(),
  },
  async ({ requirement_id, link_id }) => {
    try { await deleteTraceabilityLink(requirement_id, link_id); return ok("Deleted"); }
    catch (e) { return err(e); }
  },
);

server.tool(
  "gc_transition_status",
  "Transition a requirement's status. Valid: DRAFT->ACTIVE, ACTIVE->DEPRECATED, ACTIVE->ARCHIVED, DEPRECATED->ARCHIVED.",
  {
    id: z.string().uuid(),
    status: z.enum(STATUSES),
    reason: z.string().optional(),
  },
  async ({ id, status, reason }) => {
    try { return ok(JSON.stringify(await transitionStatus(id, status, reason), null, 2)); }
    catch (e) { return err(e); }
  },
);

server.tool(
  "gc_bulk_transition_status",
  "Transition multiple requirements (by UUID) to the same status. Best-effort: valid succeed, invalid collected as failures.",
  {
    ids: z.array(z.string().uuid()).describe("Requirement UUIDs"),
    status: z.enum(STATUSES),
    reason: z.string().optional(),
  },
  async ({ ids, status, reason }) => {
    try { return ok(JSON.stringify(await bulkTransitionStatus(ids, status, reason), null, 2)); }
    catch (e) { return err(e); }
  },
);

server.tool(
  "gc_create_github_issue",
  "Create a GitHub issue from a requirement and auto-link it back. Required for /implement's UID-first path. Auto-link uses IMPLEMENTS for ACTIVE requirements; DRAFT requirements need a manual DOCUMENTS link afterwards.",
  {
    uid: z.string(),
    project: z.string().optional(),
    repo: z.string().regex(/^[a-zA-Z0-9][a-zA-Z0-9._-]*\/[a-zA-Z0-9][a-zA-Z0-9._-]*$/).optional(),
    labels: z.array(z.string()).optional(),
    extra_body: z.string().optional(),
  },
  async (args) => {
    try { return ok(JSON.stringify(await createGitHubIssue(args), null, 2)); }
    catch (e) { return err(e); }
  },
);

// Shared description for the opt-in `async` parameter on the codex/claude
// review + preflight tools (issue #937).
const ASYNC_REVIEW_PARAM_DESC =
  "When true, start the review/preflight as a background job and return " +
  "{ok,status:'running',job_id} immediately instead of blocking the MCP call. " +
  "Poll the job with gc_codex_job (action='poll') until status='done', then dispatch " +
  "on result.next_action exactly as for the synchronous call. Use this in the /implement " +
  "workflow so a multi-minute review never trips the MCP client's tool-call timeout (issue #937).";

server.tool(
  "gc_remember",
  "Capture a knowledge-base observation from the calling agent. Writes a structured inbox file in the repository's knowledge base and spawns a detached ingest subprocess that integrates the observation into the wiki. Synchronous success means the inbox entry was durably written; wiki integration happens asynchronously and may be retried by later real-time or scheduled runs. Requires the repository's .ground-control.yaml to declare a knowledge block.",
  {
    repo_path: z.string().describe("Absolute path to the target Git repository"),
    note: z.string().min(1).describe("The observation to capture, as free-form text"),
    source_type: z
      .enum(KNOWLEDGE_SOURCE_TYPES)
      .describe(
        "Source citation type (must match the vocabulary in docs/knowledge/SCHEMA.md)",
      ),
    source_ref: z
      .string()
      .min(1)
      .describe(
        "Source citation reference (short SHA for commit, number for pr/issue, comment id for review, etc.)",
      ),
    tags: z
      .array(z.string())
      .optional()
      .describe("Optional list of tags used for index discovery"),
  },
  async ({ repo_path, note, source_type, source_ref, tags }) => {
    try {
      const result = await writeKnowledgeInbox({
        repoPath: repo_path,
        note,
        sourceType: source_type,
        sourceRef: source_ref,
        tags,
      });
      return ok(JSON.stringify(result, null, 2));
    } catch (e) {
      return err(e);
    }
  },
);

server.tool(
  "gc_codex_architecture_preflight",
  "Run Codex architecture preflight before implementation. Codex inspects the requirement and/or issue plus the repository, updates ADRs/design guidance when needed, and returns guardrails and changed files. At least one of requirement_uid or issue_number must be supplied. Pass async=true to run it as a background job polled via gc_codex_job.",
  {
    requirement_uid: z.string().optional(),
    repo_path: z.string(),
    project: z.string().optional(),
    issue_number: z.number().int().positive().optional(),
    repo: z.string().regex(/^[a-zA-Z0-9][a-zA-Z0-9._-]*\/[a-zA-Z0-9][a-zA-Z0-9._-]*$/).optional(),
    async: z.boolean().optional().describe(ASYNC_REVIEW_PARAM_DESC),
  },
  async ({ requirement_uid, repo_path, project, issue_number, repo, async: asyncMode }) => {
    try {
      const params = {
        requirementUid: requirement_uid, repoPath: repo_path, project,
        issueNumber: issue_number ?? null, repo: repo ?? null,
      };
      if (asyncMode) {
        return ok(JSON.stringify(startReviewJob(
          "architecture_preflight",
          (signal) => runCodexArchitecturePreflight({ ...params, signal }),
        ), null, 2));
      }
      return ok(JSON.stringify(await runCodexArchitecturePreflight(params), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_post_implementation_plan",
  "Post the implementation plan as a comment on the GitHub issue. Refuses unless a 'preflight' phase marker exists for the issue. Writes a 'plan' phase marker on success.",
  {
    repo_path: z.string(),
    issue_number: z.number().int().positive(),
    plan_body: z.string().min(1),
    override: z.boolean().optional(),
    override_reason: z.string().optional(),
  },
  async ({ repo_path, issue_number, plan_body, override, override_reason }) => {
    try {
      return ok(JSON.stringify(await runPostImplementationPlan({
        repoPath: repo_path, issueNumber: issue_number, planBody: plan_body,
        override: Boolean(override), overrideReason: override_reason ?? null,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_assert_traceability_reconciled",
  "Assert that traceability reconciliation has landed for the issue and post a 'traceability_reconciled' phase marker. Re-fetches each in-scope requirement (status_intent: ACTIVE or DRAFT) and its links from the Ground Control REST API and refuses unless every ACTIVE requirement has an IMPLEMENTS link AND, when the IMPLEMENTS link points at an executable surface (backend/src/main/**, frontend/src/**, mcp/**, tools/policy/**), at least one TESTS link. DRAFT requirements are TESTS-exempt. Empty requirements[] runs the orphaned-link audit instead. Downstream: gc_post_final_report refuses unless this marker exists for the issue. override=true + override_reason allows the user to authorize a skip with a quoted rationale.",
  {
    repo_path: z.string(),
    issue_number: z.number().int().positive(),
    requirements: z.array(z.object({
      uid: z.string().regex(EXACT_REQUIREMENT_UID_RE),
      status_intent: z.enum(["ACTIVE", "DRAFT", "DEPRECATED", "ARCHIVED"]).optional(),
    })),
    project: z.string().optional(),
    touched_files: z.array(z.string()).optional(),
    override: z.boolean().optional(),
    override_reason: z.string().optional(),
  },
  async ({ repo_path, issue_number, requirements, project, touched_files, override, override_reason }) => {
    try {
      return ok(JSON.stringify(await runAssertTraceabilityReconciled({
        repoPath: repo_path,
        issueNumber: issue_number,
        requirements: requirements.map((r) => ({ uid: r.uid, statusIntent: r.status_intent ?? "ACTIVE" })),
        project: project ?? null,
        touchedFiles: touched_files ?? [],
        override: Boolean(override),
        overrideReason: override_reason ?? null,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_assert_grc_reconciled",
  "Assert that GRC reconciliation has landed for the issue and post a 'grc_reconciled' phase marker. " +
  "Reads the GRC screening record posted by gc_post_grc_screening (Step 3.5) from the issue thread. " +
  "For 'security_relevant' verdicts: resolves each entity ref (threat_model → getThreatModelByUid, " +
  "risk_scenario → getRiskScenarioByUid, control → getControlByUid) and verifies that each claimed " +
  "CODE link exists on the owner entity (listThreatModelLinks / listRiskScenarioLinks / listControlLinks " +
  "filtered to target_type=CODE). Any gap → refuses with ok:false, error:'grc_not_reconciled', missing[]. " +
  "For 'not_security_relevant' / 'no_baseline' verdicts: passes immediately (no entity or link checks). " +
  "There is no tool-level override: a free-text reason is not a server-verifiable authorization, so the single audited skip for the completion-gate prerequisite is gc_post_final_report's phase override, which bypasses both markers together. " +
  "Downstream: gc_post_final_report refuses unless both traceability_reconciled AND grc_reconciled markers exist.",
  {
    repo_path: z.string(),
    issue_number: z.number().int().positive(),
    project: z.string().optional(),
  },
  async ({ repo_path, issue_number, project }) => {
    try {
      return ok(JSON.stringify(await runAssertGrcReconciled({
        repoPath: repo_path,
        issueNumber: issue_number,
        project: project ?? null,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_assert_quality_gates",
  "Assert that the project's enabled quality gates pass. Calls the server-side QualityGateService.evaluate contract (POST /api/v1/quality-gates/evaluate) and refuses (ok:false) when any enabled gate fails, returning failing_gates[] — ONLY the failing gates, each as {name, metric_type, threshold, actual} (plus operator) — so the fix is obvious from the error alone. Callers must pass requirements[]; use [] only as an explicit no-in-scope-requirements declaration. When the active DOCUMENTS coverage gate exists, also verifies every in-scope requirement has a DOCUMENTS traceability link regardless of status; missing links return error='in_scope_documentation_coverage_failed'. Used by the /implement completion gate (Step 6) to block a run on failing project gates or PR-scoped documentation coverage. Enforced metric types: COVERAGE (over IMPLEMENTS / TESTS / DOCUMENTS link coverage), ORPHAN_COUNT, COMPLETENESS.",
  {
    project: z.string(),
    requirements: z.array(z.object({
      uid: z.string(),
      status_intent: z.enum(["ACTIVE", "DRAFT", "DEPRECATED", "ARCHIVED"]).optional(),
    })),
  },
  async ({ project, requirements }) => {
    try {
      return ok(JSON.stringify(await runAssertQualityGates({
        project,
        requirements: requirements.map((r) => ({ uid: r.uid, statusIntent: r.status_intent ?? "ACTIVE" })),
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_close_issue_after_merge",
  "Canonical post-merge close path for the /implement workflow's Phase E (Step 20). Verifies the issue's linked PR is merged (merged_at non-null AND state=MERGED) before running `gh issue close`; refuses otherwise. Idempotent — re-running on an already-closed issue returns ok with already_closed=true. The PR body's `Closes #<n>` keyword remains the GitHub cross-link for sidebar / timeline purposes, but this tool is the gate-enforcing close path. pr_number is optional; when omitted the tool resolves the merged PR for the issue via the GitHub timeline.",
  {
    repo_path: z.string(),
    issue_number: z.number().int().positive(),
    pr_number: z.number().int().positive().optional(),
  },
  async ({ repo_path, issue_number, pr_number }) => {
    try {
      return ok(JSON.stringify(await runCloseIssueAfterMerge({
        repoPath: repo_path,
        issueNumber: issue_number,
        prNumber: pr_number ?? null,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

const CODEX_REVIEW_CAPS = { postPushCap: CODEX_REVIEW_HARD_CAP, prepushCap: CODEX_REVIEW_PREPUSH_HARD_CAP };

server.tool(
  "gc_codex_review",
  buildCodexReviewToolDescription(CODEX_REVIEW_CAPS),
  {
    repo_path: z.string(),
    base_branch: z.string().optional(),
    uncommitted: z.boolean().optional(),
    pr_number: z.number().int().positive().optional(),
    issue_number: z.number().int().positive().optional(),
    override_cap: z.boolean().optional().describe(buildCodexReviewOverrideCapDescription(CODEX_REVIEW_CAPS)),
    override_reason: z.string().optional().describe(buildCodexReviewOverrideReasonDescription(CODEX_REVIEW_CAPS)),
    override_phase_gate: z.boolean().optional(),
    override_phase_reason: z.string().optional(),
    async: z.boolean().optional().describe(ASYNC_REVIEW_PARAM_DESC),
  },
  async ({ repo_path, base_branch, uncommitted, pr_number, issue_number, override_cap, override_reason, override_phase_gate, override_phase_reason, async: asyncMode }) => {
    try {
      const params = {
        repoPath: repo_path, baseBranch: base_branch ?? null,
        uncommitted: Boolean(uncommitted),
        prNumber: pr_number != null ? pr_number : null,
        issueNumber: issue_number != null ? issue_number : null,
        overrideCap: Boolean(override_cap),
        overrideReason: override_reason ?? null,
        overridePhaseGate: Boolean(override_phase_gate),
        overridePhaseReason: override_phase_reason ?? null,
      };
      if (asyncMode) {
        return ok(JSON.stringify(startReviewJob(
          "codex_review",
          (signal) => runCodexReview({ ...params, signal }),
        ), null, 2));
      }
      return ok(JSON.stringify(await runCodexReview(params), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_test_quality_review",
  `Run the canonical /implement Step 6.6 pre-push test-quality review against the staged + unstaged + ` +
    `untracked diff vs the base branch. (Issue #906 moved this from the former post-PR Step 13 to ` +
    `pre-push Step 6.6 so the PR opens with both AI-assisted reviewers clean.) Shells out to the ` +
    `\`claude\` CLI (Sonnet 4.6 by default) with the review-tests rubric and the ` +
    `changed test-file paths, parses the structured JSON output (validated by --json-schema), posts ` +
    `the durable findings record + cycle marker to the issue thread, and returns a structured ` +
    `envelope: \`{ ok, finding_count, findings, cycle, cap, next_action, findings_comment_url, ... }\`. ` +
    `The \`next_action\` field is "fix_findings_and_reinvoke" / "post_clean_decision_record_and_advance_to_phase_c" / ` +
    `"fix_findings_then_summarize_and_escalate" / "post_summary_and_escalate_to_user" — the parent ` +
    `/implement workflow reads it as a directive. "fix_findings_then_summarize_and_escalate" is the ` +
    `last-in-cap action: fix the findings, post the decision record, then summarize and escalate to the ` +
    `user; it is NOT a normal re-invoke path. Replaces the prior Skill("review-tests") boundary, ` +
    `which produced prose findings that the autoregressive parent agent kept echoing back to the user ` +
    `instead of fixing in-turn (issue #884 v1 regression). Default cycle cap: ${TEST_QUALITY_REVIEW_HARD_CAP} per ` +
    `issue (issue #906; configurable per repo via \`workflow.test_quality_review.pre_push_cap\` in ` +
    `.ground-control.yaml; bounds [1, 10]); cycle cap+1 requires override_cap=true + override_reason. ` +
    `Authentication: the CLI invocation strips ANTHROPIC_API_KEY from the subprocess env so claude uses ` +
    `the host's OAuth session — see docs/DEVELOPMENT_WORKFLOW.md "Test-quality review engine".`,
  {
    repo_path: z.string(),
    base_branch: z.string().optional(),
    issue_number: z.number().int().positive().optional(),
    pr_number: z.number().int().positive().optional(),
    override_cap: z.boolean().optional(),
    override_reason: z.string().optional(),
    model: z.string().optional(),
    async: z.boolean().optional().describe(ASYNC_REVIEW_PARAM_DESC),
  },
  async ({ repo_path, base_branch, issue_number, pr_number, override_cap, override_reason, model, async: asyncMode }) => {
    try {
      const params = {
        repoPath: repo_path,
        // Pass null when not supplied so the runner resolves from
        // .ground-control.yaml; the runner falls back to "dev" only if
        // YAML doesn't declare workflow.base_branch.
        baseBranch: base_branch ?? null,
        issueNumber: issue_number != null ? issue_number : null,
        prNumber: pr_number != null ? pr_number : null,
        overrideCap: Boolean(override_cap),
        overrideReason: override_reason ?? null,
        ...(model ? { model } : {}),
      };
      if (asyncMode) {
        return ok(JSON.stringify(startReviewJob(
          "test_quality_review",
          (signal) => runTestQualityReview({ ...params, signal }),
        ), null, 2));
      }
      return ok(JSON.stringify(await runTestQualityReview(params), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_post_decision_record",
  "Post the canonical review-cycle decision record as a comment on the GitHub issue (per ADR-029, the issue thread is the durable record). Renders the verdict envelope (verdict, architectural_read, blocking, notes) into the standard decision-record Markdown layout; rejects 'defer' decisions and any body containing detected secrets. Replaces free-prose decision comments from the Step 6.5 / 6.6 review loops. The verdict + architectural_read fields are optional for back-compat; new callers (issue #931) populate them. Returns the posted comment's URL and id. A GitHub update gives exactly what's needed — not more, not less. No restating context the reader already has, no padding sections, no hedging prose.",
  {
    repo_path: z.string(),
    issue_number: z.number().int().positive(),
    cycle: z.number().int().positive(),
    reviewer: z.enum(DECISION_RECORD_REVIEWERS),
    // Verdict envelope (#931). Optional for back-compat; required for the new
    // principal-engineer contract.
    verdict: z.enum(["ship", "ship-with-fixes", "don't-ship"]).optional(),
    architectural_read: z.string().min(1).optional(),
    notes: z.array(z.object({
      text: z.string().min(1),
    })).max(2).optional(),
    findings: z.array(z.object({
      id: z.string().min(1),
      title: z.string().min(1),
      classification: z.enum(DECISION_RECORD_CLASSIFICATIONS),
      decision: z.enum(DECISION_RECORD_DECISIONS),
      rationale: z.string().min(1),
      // Required at runtime when decision === "wontfix" — see ADR-029. The
      // Zod object cannot conditionally require a field, so the validator in
      // lib.js performs the conditional check; expose the field here so MCP
      // callers can supply it. Pass a URL to the user's authorization
      // comment on the issue thread OR a verbatim quote with comment id.
      user_authorization: z.string().optional(),
      location: z.string().optional(),
      comment_url: z.string().optional(),
      instances: z.array(z.string().min(1)).optional(),
    })),
  },
  async ({ repo_path, issue_number, cycle, reviewer, findings, verdict, architectural_read, notes }) => {
    try {
      return ok(JSON.stringify(await runPostDecisionRecord({
        repoPath: repo_path, issueNumber: issue_number, cycle, reviewer, findings,
        verdict, architectural_read, notes,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_post_grc_screening",
  "Post the canonical Step 3.5 GRC screening record as a comment on the GitHub issue. Accepts one of three verdicts: 'security_relevant' (threat-model entries, risk scenarios, controls, and CODE links were created/updated/confirmed during this run — entities_created/updated/confirmed and code_links required), 'not_security_relevant' (change does not touch a security-relevant surface — rationale required, entity/link arrays empty), 'no_baseline' (project has no threat-model baseline — explicit declination, not a clean verdict). Renders a schema-versioned 'gc.implement.grc-screening/v1' record with machine-parseable marker family 'gc:grc-screening'; runs the sensitive-content filter and body-size cap before posting; writes the grc_screening phase marker on success. Rejects caller-controlled fields carrying reserved '<!-- gc:' marker sequences. Returns {ok, verdict, comment_url, comment_id, phase_marker_posted}.",
  {
    repo_path: z.string(),
    issue_number: z.number().int().positive(),
    verdict: z.enum(GRC_SCREENING_VERDICTS),
    rationale: z.string().min(1),
    entities_created: z.array(z.object({
      type: z.string().min(1).optional(),
      uid: z.string().min(1),
    })).optional().default([]),
    entities_updated: z.array(z.object({
      type: z.string().min(1).optional(),
      uid: z.string().min(1),
    })).optional().default([]),
    entities_confirmed: z.array(z.object({
      type: z.string().min(1).optional(),
      uid: z.string().min(1),
    })).optional().default([]),
    code_links: z.array(z.object({
      owner_type: z.string().min(1).optional(),
      owner_uid: z.string().min(1).optional(),
      target_identifier: z.string().min(1),
    })).optional().default([]),
  },
  async ({ repo_path, issue_number, verdict, rationale, entities_created, entities_updated, entities_confirmed, code_links }) => {
    try {
      return ok(JSON.stringify(await runPostGrcScreening({
        repoPath: repo_path,
        issueNumber: issue_number,
        verdict,
        rationale,
        entities_created: entities_created ?? [],
        entities_updated: entities_updated ?? [],
        entities_confirmed: entities_confirmed ?? [],
        code_links: code_links ?? [],
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_post_final_report",
  "Post the canonical /implement Step 19 final report (or the /quickfix Step Q19 slim close comment) as a comment on the GitHub issue. Renders structured input (in-scope requirements, files-by-change-kind, reviews, traceability reconciliation, CI/SonarCloud status) into the standard final-report Markdown layout. Pass lane='quickfix' (issue #906) to enable the slim payload — empty reviews[] and no codex-entry requirement — for the /quickfix lane where AI-assisted reviews are opt-in; every other gate (CI green, Sonar pass-or-legit-skipped, sensitive-content / no-defer / reserved-marker scrubs) still applies. Replaces free-prose Step 19 comments. Returns the posted comment's URL and id. A GitHub update gives exactly what's needed — not more, not less. No restating context the reader already has, no padding sections, no hedging prose.",
  {
    repo_path: z.string(),
    issue_number: z.number().int().positive(),
    pr_number: z.number().int().positive(),
    requirements: z.array(z.object({
      // Anchored UID match — `requirements[].uid` must BE a UID (codex cycle-4 F2).
      uid: z.string().regex(EXACT_REQUIREMENT_UID_RE),
      title: z.string().min(1),
      status: z.string().min(1),
      note: z.string().optional(),
    })),
    files: z.object({
      added: z.array(z.string().min(1)).optional(),
      modified: z.array(z.string().min(1)).optional(),
      renamed: z.array(z.string().min(1)).optional(),
      deleted: z.array(z.string().min(1)).optional(),
    }).optional(),
    reviews: z.array(z.object({
      reviewer: z.string().min(1),
      summary: z.string().min(1).max(FINAL_REPORT_REVIEW_SUMMARY_MAX),
    })),
    traceability: z.object({
      added: z.array(z.string()).optional(),
      updated: z.array(z.string()).optional(),
      deleted: z.array(z.string()).optional(),
      notes: z.string().optional(),
    }).optional(),
    ci_status: z.enum(["green", "red", "skipped"]),
    sonar_status: z.enum(["passed", "failed", "skipped"]),
    plan_comment_url: z.string().optional(),
    summary: z.string().max(FINAL_REPORT_SUMMARY_MAX).optional(),
    lane: z.enum(["implement", "quickfix"]).optional(),
    documentation_outcome: z.object({
      outcome: z.enum(["updated", "verified_unchanged", "not_updated_authorized"]),
      rationale: z.string().optional(),
    }).optional(),
    override_traceability_gate: z.boolean().optional(),
    override_traceability_reason: z.string().optional(),
  },
  async ({ repo_path, issue_number, pr_number, requirements, files, reviews, traceability, ci_status, sonar_status, plan_comment_url, summary, lane, documentation_outcome, override_traceability_gate, override_traceability_reason }) => {
    try {
      return ok(JSON.stringify(await runPostFinalReport({
        repoPath: repo_path,
        issueNumber: issue_number,
        prNumber: pr_number,
        requirements,
        files: files ?? {},
        reviews,
        traceability: traceability ?? {},
        ciStatus: ci_status,
        sonarStatus: sonar_status,
        planCommentUrl: plan_comment_url ?? null,
        lane: lane ?? null,
        summary: summary ?? null,
        documentation_outcome: documentation_outcome ?? null,
        overrideTraceabilityGate: Boolean(override_traceability_gate),
        overrideTraceabilityReason: override_traceability_reason ?? null,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_render_pr_body",
  "Render a PR body that satisfies the Ground Control policy gates (template sections, requirement UIDs, ADR impact, three Ground Control Checks, IMPLEMENTS/TESTS markers, no defer language). Returns the rendered body string for the caller to pass to `gh pr create --body`. change_class shapes a few cells: doc-only marks integration tests / changelog fragment N/A; source requires changelog fragment; source+migration adds the MigrationSmokeTest reminder. A GitHub update gives exactly what's needed — not more, not less. No restating context the reader already has, no padding sections, no hedging prose.",
  {
    repo_path: z.string(),
    issue_number: z.number().int().positive(),
    change_class: z.enum(PR_BODY_CHANGE_CLASSES),
    // Use the ANCHORED EXACT_REQUIREMENT_UID_RE for structured UID fields
    // (codex cycle-4 F2). The unanchored PR_REQUIREMENT_RE is a body-search
    // predicate; here each array element must BE a UID, not contain one.
    requirement_uids: z.array(z.string().regex(EXACT_REQUIREMENT_UID_RE)),
    adr_refs: z.array(z.string().min(1)),
    summary: z.string().min(1).max(PR_BODY_SUMMARY_MAX),
    changes: z.array(z.string().min(1)),
    traceability: z.object({
      implements: z.array(z.string()),
      tests: z.array(z.string()),
    }),
    changelog_fragment: z.string().optional(),
    test_notes: z.string().optional(),
    documentation_outcome: z.object({
      outcome: z.enum(["updated", "verified_unchanged", "not_updated_authorized"]),
      rationale: z.string().optional(),
    }).optional(),
  },
  async ({ repo_path, issue_number, change_class, requirement_uids, adr_refs, summary, changes, traceability, changelog_fragment, test_notes, documentation_outcome }) => {
    try {
      return ok(JSON.stringify(await runRenderPrBody({
        repoPath: repo_path,
        issueNumber: issue_number,
        changeClass: change_class,
        requirementUids: requirement_uids,
        adrRefs: adr_refs,
        summary,
        changes,
        traceability,
        changelogFragment: changelog_fragment ?? null,
        testNotes: test_notes ?? null,
        documentation_outcome: documentation_outcome ?? null,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_log_step_telemetry",
  "Append a single JSONL telemetry record for a /implement step to `.gc/telemetry/<issue>-<sanitized-branch>.jsonl`. Operational measurement only — NOT workflow state (per ADR-036). wall_time_ms is mandatory; input_tokens / output_tokens are optional. Path is repo-relative and validated for containment.",
  {
    repo_path: z.string(),
    issue_number: z.number().int().positive(),
    branch: z.string().min(1),
    step: z.string().min(1),
    tier: z.enum(TELEMETRY_TIERS),
    model: z.string().min(1),
    wall_time_ms: z.number().int().nonnegative(),
    input_tokens: z.number().int().nonnegative().nullable().optional(),
    output_tokens: z.number().int().nonnegative().nullable().optional(),
    outcome: z.enum(TELEMETRY_OUTCOMES),
    ts: z.string().optional(),
  },
  async ({ repo_path, issue_number, branch, step, tier, model, wall_time_ms, input_tokens, output_tokens, outcome, ts }) => {
    try {
      return ok(JSON.stringify(await runLogStepTelemetry({
        repoPath: repo_path,
        issueNumber: issue_number,
        branch,
        step,
        tier,
        model,
        wallTimeMs: wall_time_ms,
        inputTokens: input_tokens ?? null,
        outputTokens: output_tokens ?? null,
        outcome,
        ts: ts ?? null,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_documentation_coverage",
  "Classify a list of repo-relative changed paths into surface classes and return their documentation targets. Surface classes: workflow, mcp_tool, config_parser, policy, adr, public_api, user_visible, doc, unclassified. outcome_required is true when any path belongs to a class that requires a documented outcome (workflow/mcp_tool/config_parser/policy/adr/public_api/user_visible). suggested_doc_targets is the deduped union of doc_targets across all classifications. Paths are validated for repo-containment — absolute paths and '..' escapes are rejected.",
  {
    repo_path: z.string().describe("Absolute path to the target Git repository"),
    changed_paths: z.array(z.string()).describe("Repo-relative paths to classify"),
  },
  ({ repo_path, changed_paths }) => {
    try {
      const result = classifyChangedSurface(changed_paths, repo_path);
      const allTargets = result.classifications.flatMap((c) => c.doc_targets);
      const suggested_doc_targets = [...new Set(allTargets)];
      return ok(JSON.stringify({ ok: true, ...result, suggested_doc_targets }, null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_get_issue_thread",
  "Fetch the GitHub issue body + comments with an in-memory content-addressed cache. First call returns the full payload + a sha256 hash; subsequent calls passing `expected_hash` return `{unchanged: true}` without re-fetching when the hash matches. Cache is keyed by (repo, issue_number) — NOT branch-keyed — and is operational only (the GitHub issue thread remains the durable record per ADR-029). Pass expected_hash=null to force a fresh fetch (use after a posting may have failed or when marker state is uncertain).",
  {
    repo_path: z.string(),
    issue_number: z.number().int().positive(),
    expected_hash: z.string().min(1).nullable().optional(),
  },
  async ({ repo_path, issue_number, expected_hash }) => {
    try {
      return ok(JSON.stringify(await runGetIssueThread({
        repoPath: repo_path,
        issueNumber: issue_number,
        expectedHash: expected_hash ?? null,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_watch_ci_run",
  "Poll a GitHub Actions run to a terminal state server-side and return one compact terminal envelope (conclusion, failed steps, bounded log summary). Designed for the /implement Step 10 monitor: the agent makes one tool call; the MCP server holds the connection while polling so the agent's context is not burned by per-poll turns. Defaults: queued cap 5 min, total cap 45 min, poll every 15s. On queued-too-long or timeout the tool returns ok=true with conclusion='queued_too_long' or 'timed_out' so the caller can decide policy. If run_id is omitted, the latest run for the branch is resolved via `gh run list`. Raw CI logs stay server-side; only a bounded UTF-8 summary (default 4096 bytes from the tail of `--log-failed`) reaches the caller.",
  {
    repo_path: z.string(),
    branch: z.string().min(1),
    run_id: z.number().int().positive().nullable().optional(),
    queued_timeout_seconds: z.number().int().positive().optional(),
    total_timeout_seconds: z.number().int().positive().optional(),
    poll_interval_seconds: z.number().int().positive().optional(),
  },
  async ({ repo_path, branch, run_id, queued_timeout_seconds, total_timeout_seconds, poll_interval_seconds }) => {
    try {
      return ok(JSON.stringify(await runWatchCiRun({
        repoPath: repo_path,
        branch,
        runId: run_id ?? null,
        queuedTimeoutSeconds: queued_timeout_seconds ?? 300,
        totalTimeoutSeconds: total_timeout_seconds ?? 2700,
        pollIntervalSeconds: poll_interval_seconds ?? 15,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_codex_review_cycle",
  "Pre-push codex-review cycle wrapper. Runs gc_codex_review (uncommitted=true) AND auto-posts the canonical per-cycle decision record (every finding gets decision='fix' with auto-rationale, the only decision the cycle tool can record without user authorization). Returns a compact envelope: {ok, reviewer, cycle, cap, status, next_action, findings_summary, findings_record_url, decision_record_url}. Verbatim review prose and per-finding bodies stay server-side via the underlying review's findings record — they never reach the agent through this tool. The subagent that drives the loop calls this tool once per cycle; on next_action='fix_findings_and_reinvoke' it fixes, self-verifies locally, re-stages, and re-invokes. wontfix / not-applicable decisions still require an explicit gc_post_decision_record call after user authorization.",
  {
    repo_path: z.string(),
    issue_number: z.number().int().positive(),
    base_branch: z.string().nullable().optional(),
    uncommitted: z.boolean().optional(),
    override_cap: z.boolean().optional(),
    override_reason: z.string().nullable().optional(),
    async: z.boolean().optional().describe(ASYNC_REVIEW_PARAM_DESC),
  },
  async ({ repo_path, issue_number, base_branch, uncommitted, override_cap, override_reason, async: asyncMode }) => {
    try {
      const params = {
        repoPath: repo_path,
        issueNumber: issue_number,
        baseBranch: base_branch ?? null,
        uncommitted: uncommitted ?? true,
        overrideCap: Boolean(override_cap),
        overrideReason: override_reason ?? null,
      };
      if (asyncMode) {
        return ok(JSON.stringify(startReviewJob(
          "codex_review_cycle",
          (signal) => runCodexReviewCycle({ ...params, signal }),
        ), null, 2));
      }
      return ok(JSON.stringify(await runCodexReviewCycle(params), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_test_quality_review_cycle",
  "Pre-push test-quality review cycle wrapper. Runs gc_test_quality_review AND auto-posts the canonical per-cycle decision record (reviewer='test-quality', every finding decision='fix' with auto-rationale). Same compact envelope shape as gc_codex_review_cycle. Verbatim reviewer prose stays server-side. Skips automatically when the diff has no test files (the underlying review handles that).",
  {
    repo_path: z.string(),
    issue_number: z.number().int().positive(),
    base_branch: z.string().nullable().optional(),
    override_cap: z.boolean().optional(),
    override_reason: z.string().nullable().optional(),
    model: z.string().optional(),
    async: z.boolean().optional().describe(ASYNC_REVIEW_PARAM_DESC),
  },
  async ({ repo_path, issue_number, base_branch, override_cap, override_reason, model, async: asyncMode }) => {
    try {
      const params = {
        repoPath: repo_path,
        issueNumber: issue_number,
        baseBranch: base_branch ?? null,
        overrideCap: Boolean(override_cap),
        overrideReason: override_reason ?? null,
        model,
      };
      if (asyncMode) {
        return ok(JSON.stringify(startReviewJob(
          "test_quality_review_cycle",
          (signal) => runTestQualityReviewCycle({ ...params, signal }),
        ), null, 2));
      }
      return ok(JSON.stringify(await runTestQualityReviewCycle(params), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_codex_job",
  "Poll or cancel an async review/preflight job started by gc_codex_review, gc_codex_review_cycle, " +
    "gc_codex_architecture_preflight, gc_test_quality_review, or gc_test_quality_review_cycle when those " +
    "tools are called with async=true (issue #937). action='poll' returns {ok:true,status:'running'} while " +
    "the codex/claude child is still running, and {ok:true,status:'done',result:<review envelope>} once it " +
    "finishes — dispatch on result.next_action exactly as for the synchronous tool. A failed or cancelled " +
    "job returns ok=false. action='cancel' aborts a running job and kills its child process (no orphan). " +
    "Jobs are reaped 30 minutes after they finish; a poll for an unknown or expired job_id returns " +
    "error='job_not_found', at which point re-run the review.",
  {
    action: z.enum(["poll", "cancel"]),
    job_id: z.string().min(1),
  },
  async ({ action, job_id }) => {
    try {
      const result = action === "cancel" ? cancelReviewJob(job_id) : pollReviewJob(job_id);
      return ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_watch_sonar_analysis",
  "Poll SonarCloud for a PR's quality gate and open issues / hotspots server-side. Returns one compact terminal envelope: {quality_gate, issues_summary, hotspots_summary, full_issue_export_path}. Designed for /implement Step 11: the agent makes one tool call; the MCP server holds the connection through the analysis propagation wait (60s default) and quality-gate polling (30 min default). When the repo has no sonarcloud block in .ground-control.yaml the tool returns ok=true skipped=true quality_gate='NONE' (mirrors the existing skip behavior). SonarCloud REST authentication uses HTTP Basic with the SONAR_TOKEN env var as the username — the token is read at call time and passed only in the Authorization header (never argv, telemetry, export, or returned envelope). The full per-issue + per-hotspot payload is written server-side under `.gc/sonar/<pr>-<ts>.json` for on-demand drilldown; only summaries reach the caller.",
  {
    repo_path: z.string(),
    pr_number: z.number().int().positive(),
    initial_wait_seconds: z.number().int().nonnegative().optional(),
    total_timeout_seconds: z.number().int().nonnegative().optional(),
    poll_interval_seconds: z.number().int().nonnegative().optional(),
  },
  async ({ repo_path, pr_number, initial_wait_seconds, total_timeout_seconds, poll_interval_seconds }) => {
    try {
      return ok(JSON.stringify(await runWatchSonarAnalysis({
        repoPath: repo_path,
        prNumber: pr_number,
        initialWaitSeconds: initial_wait_seconds ?? 60,
        totalTimeoutSeconds: total_timeout_seconds ?? 1800,
        pollIntervalSeconds: poll_interval_seconds ?? 30,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_resolve_workflow_route",
  "Resolve the configured /implement route for a workflow stage or purpose. Returns the provider, agent, canonical model id, tier, fallback policy, and source, or a structured disabled/unavailable response. This is the executable routing contract; callers use it before delegated stages instead of relying on skill prose.",
  {
    repo_path: z.string(),
    stage: z.string().min(1),
    tier: z.enum(TELEMETRY_TIERS).optional(),
  },
  async ({ repo_path, stage, tier }) => {
    try {
      return ok(JSON.stringify(await runResolveWorkflowRoute({
        repoPath: repo_path,
        stage,
        tier: tier ?? null,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_codex_verify_finding",
  "Ask Codex to verify whether a specific PR review finding has been resolved. RESOLVED → mark thread resolved; UNRESOLVED → post threaded reply. Per-finding cap of 2 verify calls.",
  {
    repo_path: z.string(),
    pr_number: z.number().int().positive(),
    comment_id: z.number().int().positive(),
    override_cap: z.boolean().optional(),
    override_reason: z.string().optional(),
  },
  async ({ repo_path, pr_number, comment_id, override_cap, override_reason }) => {
    try {
      return ok(JSON.stringify(await runCodexVerifyFinding({
        repoPath: repo_path, prNumber: pr_number, commentId: comment_id,
        overrideCap: Boolean(override_cap), overrideReason: override_reason ?? null,
      }), null, 2));
    } catch (e) { return err(e); }
  },
);

// ============================================================================
// CONSOLIDATED ENTITY TOOLS — action-discriminated CRUD per entity.
// Pure GETs (list, get-by-id, history, timeline, exports) live on gc_query
// against the allowlisted /api/v1/* prefixes.
// ============================================================================

const REQUIREMENT_ACTIONS = ["list", "create", "update", "delete", "archive", "clone"];

server.tool(
  "gc_requirement",
  `Requirement operations (action-discriminated). Actions: ${REQUIREMENT_ACTIONS.join(", ")}. ` +
    `Reads (list/get/history/diff/timeline) route through gc_query against /api/v1/requirements. ` +
    `Status transitions live on gc_transition_status / gc_bulk_transition_status (workflow primitives). ` +
    `Required fields per action: create→{uid,title,statement}; update→{id}; delete/archive→{id}; clone→{source_uid,new_uid}.`,
  {
    action: z.enum(REQUIREMENT_ACTIONS),
    // identifiers
    id: z.string().uuid().optional(),
    uid: z.string().optional(),
    source_uid: z.string().optional(),
    new_uid: z.string().optional(),
    // create/update fields
    project: z.string().optional(),
    title: z.string().optional(),
    statement: z.string().optional(),
    rationale: z.string().optional(),
    requirement_type: z.enum(REQUIREMENT_TYPES).optional(),
    priority: z.enum(PRIORITIES).optional(),
    wave: z.number().int().optional(),
    status: z.enum(STATUSES).optional(),
    // list filtering
    type: z.enum(REQUIREMENT_TYPES).optional(),
    search: z.string().optional(),
    page: z.number().int().optional(),
    size: z.number().int().optional(),
    sort: z.string().optional(),
    // clone
    copy_relations: z.boolean().optional(),
  },
  async (args) => {
    try {
      const ENTITY_FIELDS = ["uid", "title", "statement", "rationale", "requirement_type", "priority", "wave", "status"];
      switch (args.action) {
        case "list": {
          const filter = pick(args, ["status", "type", "priority", "wave", "search", "page", "size", "sort", "project"]);
          return ok(JSON.stringify(await listRequirements(filter), null, 2));
        }
        case "create": {
          reqArg(args, "uid", "create"); reqArg(args, "title", "create"); reqArg(args, "statement", "create");
          return ok(JSON.stringify(await createRequirement(pick(args, ENTITY_FIELDS), args.project), null, 2));
        }
        case "update": {
          reqArg(args, "id", "update");
          return ok(JSON.stringify(await updateRequirement(args.id, pick(args, ENTITY_FIELDS)), null, 2));
        }
        case "delete": {
          reqArg(args, "id", "delete");
          await archiveRequirement(args.id);
          return ok("Archived (the backend has no hard delete for requirements; transitioned to ARCHIVED)");
        }
        case "archive": {
          reqArg(args, "id", "archive");
          return ok(JSON.stringify(await archiveRequirement(args.id), null, 2));
        }
        case "clone": {
          // Note: lib.js cloneRequirement signature is (id, newUid, copyRelations).
          // The `id` is the SOURCE requirement's UUID. Look it up from source_uid
          // if the caller only knows the human-readable UID.
          reqArg(args, "new_uid", "clone");
          let sourceId = args.id;
          if (!sourceId) {
            reqArg(args, "source_uid", "clone");
            const src = await getRequirementByUid(args.source_uid, args.project);
            sourceId = src?.id;
            if (!sourceId) throw new Error(`clone: source requirement '${args.source_uid}' not found`);
          }
          return ok(JSON.stringify(await cloneRequirement(sourceId, args.new_uid, args.copy_relations ?? false), null, 2));
        }
        default: return err(new Error(`Unknown action: ${args.action}`));
      }
    } catch (e) { return err(e); }
  },
);

const RELATION_ACTIONS = ["create", "get", "delete"];

server.tool(
  "gc_relation",
  `Requirement-to-requirement relations. Actions: ${RELATION_ACTIONS.join(", ")}. ` +
    `Reads (history) route through gc_query.`,
  {
    action: z.enum(RELATION_ACTIONS),
    id: z.string().uuid().optional(),
    requirement_id: z.string().uuid().optional(),
    source_id: z.string().uuid().optional(),
    target_id: z.string().uuid().optional(),
    relation_type: z.enum(RELATION_TYPES).optional(),
  },
  async (args) => {
    try {
      switch (args.action) {
        case "create": {
          reqArg(args, "source_id", "create"); reqArg(args, "target_id", "create"); reqArg(args, "relation_type", "create");
          return ok(JSON.stringify(await createRelation(args.source_id, args.target_id, args.relation_type), null, 2));
        }
        case "get": {
          reqArg(args, "requirement_id", "get");
          return ok(JSON.stringify(await getRelations(args.requirement_id), null, 2));
        }
        case "delete": {
          // lib.js signature: deleteRelation(reqId, relId)
          reqArg(args, "requirement_id", "delete"); reqArg(args, "id", "delete");
          await deleteRelation(args.requirement_id, args.id);
          return ok("Deleted");
        }
        default: return err(new Error(`Unknown action: ${args.action}`));
      }
    } catch (e) { return err(e); }
  },
);

const ADR_ACTIONS = ["create", "update", "delete", "transition", "requirements"];

server.tool(
  "gc_adr",
  `ADR operations. Actions: ${ADR_ACTIONS.join(", ")}. ` +
    `Reads (list, get) route through gc_query.`,
  {
    action: z.enum(ADR_ACTIONS),
    id: z.string().uuid().optional(),
    uid: z.string().optional(),
    project: z.string().optional(),
    title: z.string().optional(),
    status: z.enum(ADR_STATUSES).optional(),
    decision_date: z.string().optional(),
    context: z.string().optional(),
    decision: z.string().optional(),
    consequences: z.string().optional(),
    superseded_by: z.string().uuid().nullable().optional(),
  },
  async (args) => {
    try {
      const ENTITY_FIELDS = ["uid", "title", "status", "decision_date", "context", "decision", "consequences", "superseded_by"];
      switch (args.action) {
        case "create": {
          reqArg(args, "uid", "create"); reqArg(args, "title", "create");
          return ok(JSON.stringify(await createAdr(pick(args, ENTITY_FIELDS), args.project), null, 2));
        }
        case "update": {
          reqArg(args, "id", "update");
          return ok(JSON.stringify(await updateAdr(args.id, pick(args, ENTITY_FIELDS)), null, 2));
        }
        case "delete": {
          reqArg(args, "id", "delete");
          await deleteAdr(args.id);
          return ok("Deleted");
        }
        case "transition": {
          // lib.js signature: transitionAdrStatus(id, status). superseded_by lands via update.
          reqArg(args, "id", "transition"); reqArg(args, "status", "transition");
          return ok(JSON.stringify(await transitionAdrStatus(args.id, args.status), null, 2));
        }
        case "requirements": {
          reqArg(args, "id", "requirements");
          return ok(JSON.stringify(await getAdrRequirements(args.id), null, 2));
        }
        default: return err(new Error(`Unknown action: ${args.action}`));
      }
    } catch (e) { return err(e); }
  },
);

const DOCUMENT_ACTIONS = ["create", "update", "delete", "grammar_set", "grammar_delete", "reading_order"];

server.tool(
  "gc_document",
  `Document operations + grammar + reading-order. Actions: ${DOCUMENT_ACTIONS.join(", ")}. ` +
    `Reads (list, get, grammar_get) route through gc_query.`,
  {
    action: z.enum(DOCUMENT_ACTIONS),
    id: z.string().uuid().optional(),
    project: z.string().optional(),
    title: z.string().optional(),
    description: z.string().optional(),
    grammar: z.record(z.any()).optional(),
  },
  async (args) => {
    try {
      const ENTITY_FIELDS = ["title", "description"];
      switch (args.action) {
        case "create": {
          reqArg(args, "title", "create");
          return ok(JSON.stringify(await createDocument(pick(args, ENTITY_FIELDS), args.project), null, 2));
        }
        case "update": {
          reqArg(args, "id", "update");
          return ok(JSON.stringify(await updateDocument(args.id, pick(args, ENTITY_FIELDS)), null, 2));
        }
        case "delete": {
          reqArg(args, "id", "delete");
          await deleteDocument(args.id);
          return ok("Deleted");
        }
        case "grammar_set": {
          reqArg(args, "id", "grammar_set"); reqArg(args, "grammar", "grammar_set");
          return ok(JSON.stringify(await setDocumentGrammar(args.id, args.grammar), null, 2));
        }
        case "grammar_delete": {
          reqArg(args, "id", "grammar_delete");
          await deleteDocumentGrammar(args.id);
          return ok("Grammar deleted");
        }
        case "reading_order": {
          reqArg(args, "id", "reading_order");
          return ok(JSON.stringify(await getDocumentReadingOrder(args.id), null, 2));
        }
        default: return err(new Error(`Unknown action: ${args.action}`));
      }
    } catch (e) { return err(e); }
  },
);

const SECTION_ACTIONS = ["create", "update", "delete", "tree", "content_add", "content_update", "content_delete"];

server.tool(
  "gc_section",
  `Section + section-content operations. Actions: ${SECTION_ACTIONS.join(", ")}. ` +
    `Reads (list, get, content_list) route through gc_query.`,
  {
    action: z.enum(SECTION_ACTIONS),
    id: z.string().uuid().optional(),
    document_id: z.string().uuid().optional(),
    parent_section_id: z.string().uuid().nullable().optional(),
    title: z.string().optional(),
    description: z.string().optional(),
    ordinal: z.number().int().optional(),
    content_id: z.string().uuid().optional(),
    content_type: z.string().optional(),
    requirement_id: z.string().uuid().optional(),
    text: z.string().optional(),
    project: z.string().optional(),
  },
  async (args) => {
    try {
      const SECTION_ENTITY_FIELDS = ["parent_section_id", "title", "description", "ordinal"];
      const CONTENT_ENTITY_FIELDS = ["content_type", "requirement_id", "text", "ordinal"];
      switch (args.action) {
        case "create": {
          reqArg(args, "document_id", "create"); reqArg(args, "title", "create");
          return ok(JSON.stringify(await createSection(args.document_id, pick(args, SECTION_ENTITY_FIELDS)), null, 2));
        }
        case "update": {
          reqArg(args, "id", "update");
          return ok(JSON.stringify(await updateSection(args.id, pick(args, SECTION_ENTITY_FIELDS)), null, 2));
        }
        case "delete": {
          reqArg(args, "id", "delete");
          await deleteSection(args.id);
          return ok("Deleted");
        }
        case "tree": {
          reqArg(args, "document_id", "tree");
          return ok(JSON.stringify(await getSectionTree(args.document_id), null, 2));
        }
        case "content_add": {
          reqArg(args, "id", "content_add"); reqArg(args, "content_type", "content_add");
          return ok(JSON.stringify(await addSectionContent(args.id, pick(args, CONTENT_ENTITY_FIELDS)), null, 2));
        }
        case "content_update": {
          reqArg(args, "content_id", "content_update");
          return ok(JSON.stringify(await updateSectionContent(args.content_id, pick(args, CONTENT_ENTITY_FIELDS)), null, 2));
        }
        case "content_delete": {
          reqArg(args, "content_id", "content_delete");
          await deleteSectionContent(args.content_id);
          return ok("Content deleted");
        }
        default: return err(new Error(`Unknown action: ${args.action}`));
      }
    } catch (e) { return err(e); }
  },
);

const ANALYZE_KINDS = [
  "cycles", "orphans", "coverage_gaps", "impact", "cross_wave",
  "consistency", "completeness", "status_drift", "similarity", "work_order",
  // GC-L007 — GRC analyses on existing substrates. Methodology-execution
  // engines (FAIR / FAIR-CAM) and compliance-framework analyses are tracked
  // separately and ship their own kinds when those engines land.
  "evidence_freshness", "observation_exposure", "control_state", "vendor_risk_aggregation",
  // GC-T014 — NIST SP 800-30 Rev. 1 risk-assessment view (methodology-attributed
  // envelope from /api/v1/analysis/grc/nist-sp-800-30).
  "nist_assessment",
];

server.tool(
  "gc_analyze",
  `Compute-heavy analysis operations. Kinds: ${ANALYZE_KINDS.join(", ")}. ` +
    `Required fields per kind: coverage_gaps→{link_type}; impact→{id}; status_drift→{minimum_confidence?}; similarity→{threshold?}; ` +
    `evidence_freshness→{project?, as_of?, freshness_window_days?, include_superseded?, asset_id?, control_id?}; ` +
    `observation_exposure→{project?, as_of?, asset_id?}; ` +
    `control_state→{project?, as_of?, asset_id?, control_id?}; ` +
    `vendor_risk_aggregation→{project?, as_of?, freshness_window_days?, vendor_asset_id?}; ` +
    `nist_assessment→{project?, as_of?, risk_assessment_result_id?, risk_scenario_id?}. ` +
    `Others take {project?}.`,
  {
    kind: z.enum(ANALYZE_KINDS),
    project: z.string().optional(),
    id: z.string().uuid().optional(),
    link_type: z.enum(LINK_TYPES).optional(),
    minimum_confidence: z.enum(CONFIDENCE_LEVELS).optional(),
    threshold: z.number().optional(),
    // GC-L007 GRC analysis params
    as_of: z.string().datetime().optional(),
    freshness_window_days: z.number().int().positive().optional(),
    include_superseded: z.boolean().optional(),
    asset_id: z.string().uuid().optional(),
    control_id: z.string().uuid().optional(),
    vendor_asset_id: z.string().uuid().optional(),
    // GC-T014 NIST assessment params
    risk_assessment_result_id: z.string().uuid().optional(),
    risk_scenario_id: z.string().uuid().optional(),
  },
  async (args) => {
    try {
      switch (args.kind) {
        case "cycles": return ok(JSON.stringify(await detectCycles(args.project), null, 2));
        case "orphans": return ok(JSON.stringify(await findOrphans(args.project), null, 2));
        case "coverage_gaps": {
          reqArg(args, "link_type", "coverage_gaps");
          return ok(JSON.stringify(await findCoverageGaps(args.link_type, args.project), null, 2));
        }
        case "impact": {
          reqArg(args, "id", "impact");
          return ok(JSON.stringify(await impactAnalysis(args.id), null, 2));
        }
        case "cross_wave": return ok(JSON.stringify(await crossWaveValidation(args.project), null, 2));
        case "consistency": return ok(JSON.stringify(await detectConsistencyViolations(args.project), null, 2));
        case "completeness": return ok(JSON.stringify(await analyzeCompleteness(args.project), null, 2));
        case "status_drift": return ok(JSON.stringify(await analyzeStatusDrift({ project: args.project, minimumConfidence: args.minimum_confidence }), null, 2));
        case "similarity": return ok(JSON.stringify(await analyzeSemanticSimilarity({ project: args.project, threshold: args.threshold }), null, 2));
        case "work_order": return ok(JSON.stringify(await getWorkOrder(args.project), null, 2));
        case "evidence_freshness":
          return ok(JSON.stringify(await analyzeEvidenceFreshness({
            project: args.project,
            asOf: args.as_of,
            freshnessWindowDays: args.freshness_window_days,
            includeSuperseded: args.include_superseded,
            assetId: args.asset_id,
            controlId: args.control_id,
          }), null, 2));
        case "observation_exposure":
          return ok(JSON.stringify(await analyzeObservationProjection({
            project: args.project,
            asOf: args.as_of,
            mode: "ASSET_EXPOSURE",
            assetId: args.asset_id,
          }), null, 2));
        case "control_state":
          return ok(JSON.stringify(await analyzeObservationProjection({
            project: args.project,
            asOf: args.as_of,
            mode: "CONTROL_STATE",
            assetId: args.asset_id,
            controlId: args.control_id,
          }), null, 2));
        case "vendor_risk_aggregation":
          return ok(JSON.stringify(await aggregateVendorRisk({
            project: args.project,
            asOf: args.as_of,
            freshnessWindowDays: args.freshness_window_days,
            vendorAssetId: args.vendor_asset_id,
          }), null, 2));
        case "nist_assessment":
          return ok(JSON.stringify(await analyzeNistAssessment({
            project: args.project,
            asOf: args.as_of,
            riskAssessmentResultId: args.risk_assessment_result_id,
            riskScenarioId: args.risk_scenario_id,
          }), null, 2));
        default: return err(new Error(`Unknown kind: ${args.kind}`));
      }
    } catch (e) { return err(e); }
  },
);

const GRAPH_MODES = ["ancestors", "descendants", "paths", "subgraph", "visualization", "traverse", "find_paths"];

server.tool(
  "gc_graph",
  `Graph traversal. Modes: ${GRAPH_MODES.join(", ")}. ` +
    `Required: ancestors/descendants→{uid}; paths/find_paths→{source,target}; subgraph/traverse→{roots}; visualization→{project?}. entity_types/max_depth are optional refinements.`,
  {
    mode: z.enum(GRAPH_MODES),
    project: z.string().optional(),
    uid: z.string().optional(),
    source: z.string().optional(),
    target: z.string().optional(),
    roots: z.array(z.string()).optional(),
    depth: z.number().int().optional(),
    entity_types: z.array(z.string()).optional(),
    max_depth: z.number().int().optional(),
  },
  async (args) => {
    try {
      switch (args.mode) {
        case "ancestors": {
          reqArg(args, "uid", "ancestors");
          return ok(JSON.stringify(await getAncestors(args.uid, args.depth, args.project), null, 2));
        }
        case "descendants": {
          reqArg(args, "uid", "descendants");
          return ok(JSON.stringify(await getDescendants(args.uid, args.depth, args.project), null, 2));
        }
        case "paths": {
          reqArg(args, "source", "paths"); reqArg(args, "target", "paths");
          return ok(JSON.stringify(await findPaths(args.source, args.target, args.project), null, 2));
        }
        case "find_paths": {
          // lib.js: findGraphPaths(sourceNodeId, targetNodeId, project, entityTypes, maxDepth)
          reqArg(args, "source", "find_paths"); reqArg(args, "target", "find_paths");
          return ok(JSON.stringify(await findGraphPaths(args.source, args.target, args.project, args.entity_types, args.max_depth), null, 2));
        }
        case "subgraph": {
          // lib.js: extractSubgraph(rootNodeIds, project, entityTypes, maxDepth)
          reqArg(args, "roots", "subgraph");
          return ok(JSON.stringify(await extractSubgraph(args.roots, args.project, args.entity_types, args.max_depth), null, 2));
        }
        case "traverse": {
          // lib.js: traverseGraph(rootNodeIds, project, entityTypes, maxDepth)
          reqArg(args, "roots", "traverse");
          return ok(JSON.stringify(await traverseGraph(args.roots, args.project, args.entity_types, args.max_depth), null, 2));
        }
        case "visualization": {
          return ok(JSON.stringify(await getGraphVisualization(args.project, args.entity_types), null, 2));
        }
        default: return err(new Error(`Unknown mode: ${args.mode}`));
      }
    } catch (e) { return err(e); }
  },
);

const BASELINE_ACTIONS = ["create", "delete", "snapshot", "compare"];

server.tool(
  "gc_baseline",
  `Baseline operations. Actions: ${BASELINE_ACTIONS.join(", ")}. ` +
    `Reads (list, get) route through gc_query. Required: create→{name}; delete/snapshot→{id}; compare→{baseline_a, baseline_b}.`,
  {
    action: z.enum(BASELINE_ACTIONS),
    id: z.string().uuid().optional(),
    project: z.string().optional(),
    name: z.string().optional(),
    description: z.string().optional(),
    baseline_a: z.string().uuid().optional(),
    baseline_b: z.string().uuid().optional(),
  },
  async (args) => {
    try {
      const ENTITY_FIELDS = ["name", "description"];
      switch (args.action) {
        case "create": {
          reqArg(args, "name", "create");
          return ok(JSON.stringify(await createBaseline(pick(args, ENTITY_FIELDS), args.project), null, 2));
        }
        case "delete": {
          reqArg(args, "id", "delete");
          await deleteBaseline(args.id);
          return ok("Deleted");
        }
        case "snapshot": {
          reqArg(args, "id", "snapshot");
          return ok(JSON.stringify(await getBaselineSnapshot(args.id), null, 2));
        }
        case "compare": {
          reqArg(args, "baseline_a", "compare"); reqArg(args, "baseline_b", "compare");
          return ok(JSON.stringify(await compareBaselines(args.baseline_a, args.baseline_b), null, 2));
        }
        default: return err(new Error(`Unknown action: ${args.action}`));
      }
    } catch (e) { return err(e); }
  },
);

const QUALITY_GATE_ACTIONS = ["create", "update", "delete", "evaluate"];

server.tool(
  "gc_quality_gate",
  `Quality gate operations. Actions: ${QUALITY_GATE_ACTIONS.join(", ")}. ` +
    `Reads (list, get) route through gc_query.`,
  {
    action: z.enum(QUALITY_GATE_ACTIONS),
    id: z.string().uuid().optional(),
    project: z.string().optional(),
    name: z.string().optional(),
    description: z.string().optional(),
    metric_type: z.enum(METRIC_TYPES).optional(),
    comparison_operator: z.enum(COMPARISON_OPERATORS).optional(),
    threshold: z.number().optional(),
    enabled: z.boolean().optional(),
  },
  async (args) => {
    try {
      const ENTITY_FIELDS = ["name", "description", "metric_type", "comparison_operator", "threshold", "enabled"];
      switch (args.action) {
        case "create": {
          reqArg(args, "name", "create"); reqArg(args, "metric_type", "create");
          return ok(JSON.stringify(await createQualityGate(pick(args, ENTITY_FIELDS), args.project), null, 2));
        }
        case "update": {
          reqArg(args, "id", "update");
          return ok(JSON.stringify(await updateQualityGate(args.id, pick(args, ENTITY_FIELDS)), null, 2));
        }
        case "delete": {
          reqArg(args, "id", "delete");
          await deleteQualityGate(args.id);
          return ok("Deleted");
        }
        case "evaluate":
          return ok(JSON.stringify(await evaluateQualityGates(args.project), null, 2));
        default: return err(new Error(`Unknown action: ${args.action}`));
      }
    } catch (e) { return err(e); }
  },
);

// gc_asset: GC-L008. Operational asset operations incl. relations (incl.
// relation_update — Defect-3 fix), links, external IDs, and subtype-schema
// registry. Handler logic lives in gc-asset.js for testability.
server.tool(
  "gc_asset",
  GC_ASSET_DESCRIPTION,
  gcAssetZodShape,
  async (args) => {
    try {
      const result = await gcAssetToolHandler(args);
      return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

// gc_observation: GC-L008. Time-bounded state observations about an asset.
// Defect-1 fix: uses correct ObservationRequest field names (observationKey,
// observationValue, source, observedAt, expiresAt, confidence, evidenceRef)
// instead of old title/statement/valid_until/metadata names. Handler logic
// lives in gc-observation.js for testability.
server.tool(
  "gc_observation",
  GC_OBSERVATION_DESCRIPTION,
  gcObservationZodShape,
  async (args) => {
    try {
      const result = await gcObservationToolHandler(args);
      return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_risk_scenario",
  GC_RISK_SCENARIO_DESCRIPTION,
  gcRiskScenarioZodShape,
  async (args) => {
    try {
      const result = await gcRiskScenarioToolHandler(args);
      return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_threat_model",
  GC_THREAT_MODEL_DESCRIPTION,
  gcThreatModelZodShape,
  async (args) => {
    try {
      const result = await gcThreatModelToolHandler(args);
      return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

// gc_risk_scenario_workspace: GC-Q009. Read-only composition endpoint returning
// risk scenarios with linked assets, controls, findings, evidence, assessments,
// treatments, and register memberships. Review indicator uses only explicit signals.
server.tool(
  "gc_risk_scenario_workspace",
  "Read-only Risk Scenario Workspace (GC-Q009). Returns scoped risk scenarios with " +
    "linked operational assets, controls, findings, evidence, requirements, assessments " +
    "(methodologyProfileName, approvalState, hasComputedOutputs, reassessmentRequiredAt), " +
    "treatment plans, and risk register memberships. Review indicator is an explicit-signal " +
    "rollup: REASSESSMENT_REQUIRED > REVIEW_DUE > EVIDENCE_STALE > CURRENT > NO_SIGNAL. " +
    "Optional filters: assetId (UUID), status (RiskScenarioStatus), methodologyProfileId (UUID), " +
    "approvalState (RiskAssessmentApprovalStatus), treatmentStatus (TreatmentPlanStatus), " +
    "asOf (ISO-8601 instant), freshnessWindowDays (default 90), compare (array of ≤10 UUIDs).",
  {
    project: z.string().optional(),
    assetId: z.string().uuid().optional(),
    status: z.enum(["DRAFT", "ACTIVE", "ARCHIVED"]).optional(),
    methodologyProfileId: z.string().uuid().optional(),
    approvalState: z.enum(["DRAFT", "SUBMITTED", "APPROVED", "REJECTED"]).optional(),
    treatmentStatus: z.enum(["PLANNED", "IN_PROGRESS", "BLOCKED", "COMPLETED", "CANCELED"]).optional(),
    asOf: z.string().optional(),
    freshnessWindowDays: z.number().int().positive().optional(),
    compare: z.array(z.string().uuid()).max(10).optional(),
  },
  async ({ project, assetId, status, methodologyProfileId, approvalState, treatmentStatus, asOf, freshnessWindowDays, compare }) => {
    try {
      const result = await getRiskScenarioWorkspace({ project, assetId, status, methodologyProfileId, approvalState, treatmentStatus, asOf, freshnessWindowDays, compare });
      return ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

// gc_threat_model_workspace: GC-Q010. Read-only composition endpoint returning
// scoped assets (with boundary distinction), active flows, and threat entries
// with linked controls, requirements, and evidence-freshness staleness indicators.
server.tool(
  "gc_threat_model_workspace",
  "Read-only Threat Modeling Workspace (GC-Q010). Returns scoped operational assets, " +
    "trust boundaries, active flows, and threat entries with linked controls, " +
    "requirements, and per-entry evidence-freshness staleness indicators. " +
    "Optional filters: assetId (UUID), stride (StrideCategory enum), " +
    "status (ThreatModelStatus enum), asOf (ISO-8601 instant), " +
    "freshnessWindowDays (default 90).",
  {
    project: z.string().optional(),
    assetId: z.string().uuid().optional(),
    stride: z.enum(["SPOOFING", "TAMPERING", "REPUDIATION", "INFORMATION_DISCLOSURE", "DENIAL_OF_SERVICE", "ELEVATION_OF_PRIVILEGE"]).optional(),
    status: z.enum(["DRAFT", "ACTIVE", "ARCHIVED"]).optional(),
    asOf: z.string().optional(),
    freshnessWindowDays: z.number().int().positive().optional(),
  },
  async ({ project, assetId, stride, status, asOf, freshnessWindowDays }) => {
    try {
      const result = await getThreatModelWorkspace({ project, assetId, stride, status, asOf, freshnessWindowDays });
      return ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

server.tool(
  "gc_finding",
  GC_FINDING_DESCRIPTION,
  gcFindingZodShape,
  async (args) => {
    try {
      const result = await gcFindingToolHandler(args);
      return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

// gc_audit: GC-U001 / ADR-048. Full lifecycle audit management.
server.tool(
  "gc_audit",
  GC_AUDIT_DESCRIPTION,
  gcAuditZodShape,
  async (args) => {
    try {
      const result = await gcAuditToolHandler(args);
      return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

// gc_evidence: GC-M016 / ADR-045. Append-only — create / supersede only;
// reads (list, get) route through gc_query at /api/v1/evidence-artifacts.
server.tool(
  "gc_evidence",
  GC_EVIDENCE_DESCRIPTION,
  gcEvidenceZodShape,
  async (args) => {
    try {
      const result = await gcEvidenceToolHandler(args);
      return ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

// gc_evidence_state_workspace: GC-Q012. Read-only composition endpoint returning
// evidence artifacts, observations, freshness, provenance, and downstream impact.
server.tool(
  "gc_evidence_state_workspace",
  "Read-only Evidence and State Explorer (GC-Q012). Returns project-scoped " +
    "evidence artifacts, observations, freshness counts, provenance source refs, " +
    "affected assets, linked controls, downstream assessments, and linked findings. " +
    "Responses contain bounded summaries and links, not raw evidence payloads. " +
    "Optional filters: assetId (UUID), controlId (UUID), asOf (ISO-8601 instant), " +
    "freshnessWindowDays (default 90), includeSuperseded (default false).",
  {
    project: z.string().optional(),
    assetId: z.string().uuid().optional(),
    controlId: z.string().uuid().optional(),
    asOf: z.string().optional(),
    freshnessWindowDays: z.number().int().positive().optional(),
    includeSuperseded: z.boolean().optional(),
  },
  async ({ project, assetId, controlId, asOf, freshnessWindowDays, includeSuperseded }) => {
    try {
      const result = await getEvidenceStateWorkspace({ project, assetId, controlId, asOf, freshnessWindowDays, includeSuperseded });
      return ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

// gc_control_assurance_workspace: GC-Q011. Read-only composition endpoint
// returning controls with implementations, tests, evidence, findings, risk
// mappings, assessments, and owner work-queue reasons.
server.tool(
  "gc_control_assurance_workspace",
  "Read-only Control and Assurance Workspace (GC-Q011). Returns project-scoped " +
    "control catalog entries with scoped implementations, control tests, " +
    "effectiveness assessments, observation-backed evidence summaries, linked " +
    "findings/exceptions, risk mappings, and owner queue reasons. Responses " +
    "contain bounded summaries and links, not raw evidence payloads. Optional " +
    "filters: status (ControlStatus), controlFunction (ControlFunction), owner " +
    "substring, queue (owner queue reason), asOf (ISO-8601 instant), " +
    "freshnessWindowDays (default 90).",
  {
    project: z.string().optional(),
    status: z.enum(CONTROL_STATUSES).optional(),
    controlFunction: z.enum(CONTROL_FUNCTIONS).optional(),
    owner: z.string().optional(),
    queue: z.enum(CONTROL_WORKSPACE_QUEUE_REASONS).optional(),
    asOf: z.string().optional(),
    freshnessWindowDays: z.number().int().positive().optional(),
  },
  async ({ project, status, controlFunction, owner, queue, asOf, freshnessWindowDays }) => {
    try {
      const result = await getControlAssuranceWorkspace({
        project,
        status,
        controlFunction,
        owner,
        queue,
        asOf,
        freshnessWindowDays,
      });
      return ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

// gc_derivation: GC-GRC-001 normalized system-model facts and capture limits.
server.tool(
  "gc_derivation",
  GC_DERIVATION_DESCRIPTION,
  gcDerivationZodShape,
  async (args) => {
    try {
      const result = await gcDerivationToolHandler(args);
      return ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

// gc_control: control + control_test (GC-I012) + control_effectiveness_assessment
// (GC-I013). Handler logic (Zod shape, per-entity per-action allowlist dispatch
// via lib.js CONTROL_FIELDS) lives in gc-control.js so the adapter is testable
// in isolation. The entity discriminator defaults to "control" so callers from
// before the GC-I012/GC-I013 split keep working unchanged.
server.tool(
  "gc_control",
  GC_CONTROL_DESCRIPTION,
  gcControlZodShape,
  async (args) => {
    try {
      const result = await gcControlToolHandler(args);
      return result === null ? ok("Deleted") : ok(JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

// gc_test_case: TC-001 / ADR-040 + TC-002 / ADR-041 + TC-004 / ADR-042.
// Reusable test-definition aggregate at /api/v1/test-cases, its ordered child
// step aggregate at /api/v1/test-cases/{id}/steps, and the BDD/Gherkin
// singleton sub-resource at /api/v1/test-cases/{id}/gherkin. Reads (list, get,
// get-by-uid, step-list, step-get, gherkin-get) route through gc_query.
const TEST_CASE_ACTIONS = [
  "create", "update", "delete", "transition",
  "step-create", "step-update", "step-delete",
  "gherkin-create", "gherkin-update", "gherkin-delete",
  // TC-005 / ADR-043 — Hierarchical organisation actions.
  "folder-create", "folder-update", "folder-delete", "folder-move", "folder-reorder",
  "move", "copy", "reorder",
];

server.tool(
  "gc_test_case",
  `Test case operations (TC-001 / ADR-040 + TC-002 / ADR-041 + TC-004 / ADR-042). ` +
    `Actions: ${TEST_CASE_ACTIONS.join(", ")}. ` +
    `Reads (list, get, get-by-uid, step-list, step-get, gherkin-get) route through gc_query.`,
  {
    action: z.enum(TEST_CASE_ACTIONS),
    id: z.string().uuid().optional(),
    project: z.string().optional(),
    uid: z.string().optional(),
    title: z.string().optional(),
    type: z.enum(TEST_CASE_TYPES).optional(),
    priority: z.enum(TEST_CASE_PRIORITIES).optional(),
    // TC-004 / ADR-042 — authored format axis. Optional on create (defaults to
    // STEP_BASED server-side). Immutable after create.
    format: z.enum(TEST_CASE_FORMATS).optional(),
    description: z.string().optional(),
    preconditions: z.string().optional(),
    postconditions: z.string().optional(),
    estimated_duration_seconds: z.number().int().nonnegative().nullable().optional(),
    status: z.enum(TEST_CASE_STATUSES).optional(),
    // Partial-update clear flags (TC-001 codex cycle 1) — UpdateTestCaseRequest
    // accepts these on update so a client can wipe a nullable text/duration
    // field. Sending clearX=true overrides any non-null value in the same body.
    clear_description: z.boolean().optional(),
    clear_preconditions: z.boolean().optional(),
    clear_postconditions: z.boolean().optional(),
    clear_estimated_duration: z.boolean().optional(),
    // TC-002 step actions. test_case_id is the parent test case; step_id is the
    // step itself (step-update / step-delete). step_number is the per-test-case
    // ordering value. action / expected_result / actual_result are the step's
    // rich-text fields (CommonMark Markdown by convention per ADR-041).
    test_case_id: z.string().uuid().optional(),
    step_id: z.string().uuid().optional(),
    step_number: z.number().int().positive().optional(),
    step_action: z.string().optional(),
    expected_result: z.string().optional(),
    actual_result: z.string().nullable().optional(),
    clear_actual_result: z.boolean().optional(),
    // TC-004 Gherkin action body — `gherkin_source` is the MCP arg (namespaced
    // to avoid clashing with any other "source" field on future test_case
    // sub-resources); handler maps it to backend body `{ source }`.
    gherkin_source: z.string().optional(),
    // TC-005 / ADR-043 — folder + move/copy/reorder action fields.
    folder_id: z.string().uuid().optional(),
    parent_folder_id: z.string().uuid().nullable().optional(),
    sort_order: z.number().int().nonnegative().nullable().optional(),
    folder_title: z.string().optional(),
    folder_description: z.string().nullable().optional(),
    clear_folder_description: z.boolean().optional(),
    new_uid: z.string().optional(),
    ordered_folder_ids: z.array(z.string().uuid()).optional(),
    ordered_test_case_ids: z.array(z.string().uuid()).optional(),
  },
  async (args) => {
    try {
      const ENTITY_FIELDS = [
        "uid", "title", "type", "priority", "format", "description",
        "preconditions", "postconditions", "estimated_duration_seconds",
        "clear_description", "clear_preconditions", "clear_postconditions",
        "clear_estimated_duration",
        // TC-005 / ADR-043 — Placement fields on create only. The
        // backend's UpdateTestCaseRequest does NOT carry parent_folder_id
        // or sort_order; if those were in the update allowlist the MCP
        // call would accept them silently and Spring would drop them at
        // deserialization (codex cycle-2 finding). Update uses a separate
        // allowlist below; move/copy/reorder are dedicated actions.
        "parent_folder_id", "sort_order",
      ];
      const UPDATE_ENTITY_FIELDS = [
        "title", "type", "priority", "description",
        "preconditions", "postconditions", "estimated_duration_seconds",
        "clear_description", "clear_preconditions", "clear_postconditions",
        "clear_estimated_duration",
      ];
      // TC-005 / ADR-043 — TestCaseFolder request bodies. `folder_title` and
      // `folder_description` are MCP-side names that map to the backend's
      // `title` / `description` via lib.js FIELD_NAME_MAP; on update,
      // `clear_folder_description` maps to `clearDescription`.
      const folderCreateBody = () => {
        const body = pick(args, ["parent_folder_id", "sort_order"]);
        if (args.folder_title !== undefined) body.title = args.folder_title;
        if (args.folder_description !== undefined) body.description = args.folder_description;
        return body;
      };
      const folderUpdateBody = () => {
        const body = {};
        if (args.folder_title !== undefined) body.title = args.folder_title;
        if (args.folder_description !== undefined) body.description = args.folder_description;
        if (args.clear_folder_description !== undefined) body.clearDescription = args.clear_folder_description;
        return body;
      };
      const STEP_FIELDS = [
        "step_number", "expected_result", "actual_result", "clear_actual_result",
      ];
      // Map step_action → action so the MCP arg shape (which uses step_action
      // to avoid clashing with the existing action discriminator) lines up with
      // the backend's TestCaseStepRequest.action / .expectedResult / .actualResult.
      const stepBody = (extra) => {
        const body = pick(args, STEP_FIELDS);
        if (extra && extra.includeAction && args.step_action !== undefined) {
          body.action = args.step_action;
        }
        return body;
      };
      switch (args.action) {
        case "create": {
          reqArg(args, "uid", "create");
          reqArg(args, "title", "create");
          reqArg(args, "type", "create");
          reqArg(args, "priority", "create");
          return ok(JSON.stringify(await createTestCase(pick(args, ENTITY_FIELDS), args.project), null, 2));
        }
        case "update": {
          reqArg(args, "id", "update");
          return ok(JSON.stringify(
            await updateTestCase(args.id, pick(args, UPDATE_ENTITY_FIELDS), args.project),
            null,
            2,
          ));
        }
        case "delete": {
          reqArg(args, "id", "delete");
          await deleteTestCase(args.id, args.project);
          return ok("Deleted");
        }
        case "transition": {
          reqArg(args, "id", "transition");
          reqArg(args, "status", "transition");
          return ok(JSON.stringify(
            await transitionTestCaseStatus(args.id, args.status, args.project),
            null,
            2,
          ));
        }
        case "step-create": {
          reqArg(args, "test_case_id", "step-create");
          reqArg(args, "step_number", "step-create");
          reqArg(args, "step_action", "step-create");
          reqArg(args, "expected_result", "step-create");
          return ok(JSON.stringify(
            await createTestCaseStep(args.test_case_id, stepBody({ includeAction: true }), args.project),
            null,
            2,
          ));
        }
        case "step-update": {
          reqArg(args, "test_case_id", "step-update");
          reqArg(args, "step_id", "step-update");
          return ok(JSON.stringify(
            await updateTestCaseStep(
              args.test_case_id,
              args.step_id,
              stepBody({ includeAction: true }),
              args.project,
            ),
            null,
            2,
          ));
        }
        case "step-delete": {
          reqArg(args, "test_case_id", "step-delete");
          reqArg(args, "step_id", "step-delete");
          await deleteTestCaseStep(args.test_case_id, args.step_id, args.project);
          return ok("Deleted");
        }
        case "gherkin-create": {
          reqArg(args, "test_case_id", "gherkin-create");
          reqArg(args, "gherkin_source", "gherkin-create");
          return ok(JSON.stringify(
            await createTestCaseGherkin(args.test_case_id, { source: args.gherkin_source }, args.project),
            null,
            2,
          ));
        }
        case "gherkin-update": {
          reqArg(args, "test_case_id", "gherkin-update");
          reqArg(args, "gherkin_source", "gherkin-update");
          return ok(JSON.stringify(
            await updateTestCaseGherkin(args.test_case_id, { source: args.gherkin_source }, args.project),
            null,
            2,
          ));
        }
        case "gherkin-delete": {
          reqArg(args, "test_case_id", "gherkin-delete");
          await deleteTestCaseGherkin(args.test_case_id, args.project);
          return ok("Deleted");
        }
        case "folder-create": {
          reqArg(args, "folder_title", "folder-create");
          return ok(JSON.stringify(
            await createTestCaseFolder(folderCreateBody(), args.project),
            null,
            2,
          ));
        }
        case "folder-update": {
          reqArg(args, "folder_id", "folder-update");
          return ok(JSON.stringify(
            await updateTestCaseFolder(args.folder_id, folderUpdateBody(), args.project),
            null,
            2,
          ));
        }
        case "folder-delete": {
          reqArg(args, "folder_id", "folder-delete");
          await deleteTestCaseFolder(args.folder_id, args.project);
          return ok("Deleted");
        }
        case "folder-move": {
          reqArg(args, "folder_id", "folder-move");
          return ok(JSON.stringify(
            await moveTestCaseFolder(
              args.folder_id,
              { parentFolderId: args.parent_folder_id ?? null, sortOrder: args.sort_order ?? null },
              args.project,
            ),
            null,
            2,
          ));
        }
        case "folder-reorder": {
          reqArg(args, "ordered_folder_ids", "folder-reorder");
          await reorderTestCaseFolders(
            { parentFolderId: args.parent_folder_id ?? null, orderedFolderIds: args.ordered_folder_ids },
            args.project,
          );
          return ok("Reordered");
        }
        case "move": {
          reqArg(args, "id", "move");
          return ok(JSON.stringify(
            await moveTestCase(
              args.id,
              { parentFolderId: args.parent_folder_id ?? null, sortOrder: args.sort_order ?? null },
              args.project,
            ),
            null,
            2,
          ));
        }
        case "copy": {
          reqArg(args, "id", "copy");
          reqArg(args, "new_uid", "copy");
          return ok(JSON.stringify(
            await copyTestCase(
              args.id,
              {
                newUid: args.new_uid,
                parentFolderId: args.parent_folder_id ?? null,
                sortOrder: args.sort_order ?? null,
              },
              args.project,
            ),
            null,
            2,
          ));
        }
        case "reorder": {
          reqArg(args, "ordered_test_case_ids", "reorder");
          await reorderTestCases(
            { parentFolderId: args.parent_folder_id ?? null, orderedTestCaseIds: args.ordered_test_case_ids },
            args.project,
          );
          return ok("Reordered");
        }
        default: return err(new Error(`Unknown action: ${args.action}`));
      }
    } catch (e) { return err(e); }
  },
);

// gc_test_plan: TC-006 / ADR-044. Top-level planning aggregate at
// /api/v1/test-plans. Plans are flat (no hierarchy) and carry release
// coordinates (product / version / build) plus a planned schedule
// (start_date / end_date). Reads (list, get, get-by-uid) route through
// gc_query.
const TEST_PLAN_ACTIONS = ["create", "update", "delete", "transition"];

server.tool(
  "gc_test_plan",
  `Test plan operations (TC-006 / ADR-044). ` +
    `Actions: ${TEST_PLAN_ACTIONS.join(", ")}. ` +
    `Reads (list, get, get-by-uid) route through gc_query.`,
  {
    action: z.enum(TEST_PLAN_ACTIONS),
    id: z.string().uuid().optional(),
    project: z.string().optional(),
    uid: z.string().optional(),
    name: z.string().optional(),
    description: z.string().optional(),
    product: z.string().optional(),
    version: z.string().optional(),
    build: z.string().optional(),
    status: z.enum(TEST_PLAN_STATUSES).optional(),
    // Dates accepted as ISO-8601 strings (YYYY-MM-DD); Jackson binds them to
    // LocalDate on the backend.
    start_date: z.string().optional(),
    end_date: z.string().optional(),
    // Partial-update clear flags. Sending clear_*: true overrides any non-null
    // value for the same field in the same request body.
    clear_description: z.boolean().optional(),
    clear_product: z.boolean().optional(),
    clear_version: z.boolean().optional(),
    clear_build: z.boolean().optional(),
    clear_start_date: z.boolean().optional(),
    clear_end_date: z.boolean().optional(),
  },
  async (args) => {
    try {
      const TEST_PLAN_CREATE_FIELDS = [
        "uid", "name", "description", "product", "version", "build",
        "start_date", "end_date",
      ];
      const TEST_PLAN_UPDATE_FIELDS = [
        "name", "description", "product", "version", "build",
        "start_date", "end_date",
        "clear_description", "clear_product", "clear_version", "clear_build",
        "clear_start_date", "clear_end_date",
      ];
      switch (args.action) {
        case "create": {
          reqArg(args, "uid", "create");
          reqArg(args, "name", "create");
          return ok(JSON.stringify(
            await createTestPlan(pick(args, TEST_PLAN_CREATE_FIELDS), args.project),
            null,
            2,
          ));
        }
        case "update": {
          reqArg(args, "id", "update");
          return ok(JSON.stringify(
            await updateTestPlan(args.id, pick(args, TEST_PLAN_UPDATE_FIELDS), args.project),
            null,
            2,
          ));
        }
        case "delete": {
          reqArg(args, "id", "delete");
          await deleteTestPlan(args.id, args.project);
          return ok("Deleted");
        }
        case "transition": {
          reqArg(args, "id", "transition");
          reqArg(args, "status", "transition");
          return ok(JSON.stringify(
            await transitionTestPlanStatus(args.id, args.status, args.project),
            null,
            2,
          ));
        }
        default: return err(new Error(`Unknown action: ${args.action}`));
      }
    } catch (e) { return err(e); }
  },
);

// gc_test_suite: TC-007 / ADR-047. Selection container for test cases inside a
// project, with three population modes (STATIC, REQUIREMENTS_BASED,
// QUERY_BASED). Mode is set on create and immutable; resolve dispatches on
// mode at read time. Reads (list, get, get-by-uid) route through gc_query.
const TEST_SUITE_ACTIONS = [
  "create",
  "update",
  "delete",
  "resolve",
  "add_member",
  "remove_member",
  "reorder_members",
  "add_source_requirement",
  "remove_source_requirement",
];

server.tool(
  "gc_test_suite",
  `Test suite operations (TC-007 / ADR-047). ` +
    `Actions: ${TEST_SUITE_ACTIONS.join(", ")}. ` +
    `Reads (list, get, get-by-uid) route through gc_query.`,
  {
    action: z.enum(TEST_SUITE_ACTIONS),
    id: z.string().uuid().optional(),
    project: z.string().optional(),
    uid: z.string().optional(),
    name: z.string().optional(),
    description: z.string().optional(),
    population_mode: z.enum(TEST_SUITE_POPULATION_MODES).optional(),
    // QUERY_BASED criteria (only valid for QUERY_BASED suites — backend
    // rejects with 422 invalid_test_suite_mode_field on other modes).
    // Codex pre-push cycle 1 F6: use the test-case enum mirrors (not the
    // requirements `STATUSES` mirror, which would let "ACTIVE" reach the
    // backend and miss "APPROVED").
    criteria_status: z.enum(TEST_CASE_STATUSES).optional(),
    criteria_type: z.enum(TEST_CASE_TYPES).optional(),
    criteria_priority: z.enum(TEST_CASE_PRIORITIES).optional(),
    criteria_format: z.enum(TEST_CASE_FORMATS).optional(),
    criteria_folder_id: z.string().uuid().optional(),
    criteria_text_search: z.string().optional(),
    // Partial-update clear flags for the criteria block.
    clear_description: z.boolean().optional(),
    clear_criteria_status: z.boolean().optional(),
    clear_criteria_type: z.boolean().optional(),
    clear_criteria_priority: z.boolean().optional(),
    clear_criteria_format: z.boolean().optional(),
    clear_criteria_folder_id: z.boolean().optional(),
    clear_criteria_text_search: z.boolean().optional(),
    // STATIC-mode member ops.
    test_case_id: z.string().uuid().optional(),
    position: z.number().int().nonnegative().optional(),
    ordered_test_case_ids: z.array(z.string().uuid()).optional(),
    // REQUIREMENTS_BASED-mode source ops.
    requirement_id: z.string().uuid().optional(),
  },
  async (args) => {
    try {
      const TEST_SUITE_CREATE_FIELDS = [
        "uid", "name", "description", "population_mode",
        "criteria_status", "criteria_type", "criteria_priority",
        "criteria_format", "criteria_folder_id", "criteria_text_search",
      ];
      const TEST_SUITE_UPDATE_FIELDS = [
        "name", "description",
        "criteria_status", "criteria_type", "criteria_priority",
        "criteria_format", "criteria_folder_id", "criteria_text_search",
        "clear_description",
        "clear_criteria_status", "clear_criteria_type", "clear_criteria_priority",
        "clear_criteria_format", "clear_criteria_folder_id", "clear_criteria_text_search",
      ];
      switch (args.action) {
        case "create": {
          reqArg(args, "uid", "create");
          reqArg(args, "name", "create");
          reqArg(args, "population_mode", "create");
          return ok(JSON.stringify(
            await createTestSuite(pick(args, TEST_SUITE_CREATE_FIELDS), args.project),
            null,
            2,
          ));
        }
        case "update": {
          reqArg(args, "id", "update");
          return ok(JSON.stringify(
            await updateTestSuite(args.id, pick(args, TEST_SUITE_UPDATE_FIELDS), args.project),
            null,
            2,
          ));
        }
        case "delete": {
          reqArg(args, "id", "delete");
          await deleteTestSuite(args.id, args.project);
          return ok("Deleted");
        }
        case "resolve": {
          reqArg(args, "id", "resolve");
          return ok(JSON.stringify(
            await resolveTestSuiteTestCases(args.id, args.project),
            null,
            2,
          ));
        }
        case "add_member": {
          reqArg(args, "id", "add_member");
          reqArg(args, "test_case_id", "add_member");
          return ok(JSON.stringify(
            await addTestSuiteMember(
              args.id,
              pick(args, ["test_case_id", "position"]),
              args.project,
            ),
            null,
            2,
          ));
        }
        case "remove_member": {
          reqArg(args, "id", "remove_member");
          reqArg(args, "test_case_id", "remove_member");
          await removeTestSuiteMember(args.id, args.test_case_id, args.project);
          return ok("Removed");
        }
        case "reorder_members": {
          reqArg(args, "id", "reorder_members");
          reqArg(args, "ordered_test_case_ids", "reorder_members");
          return ok(JSON.stringify(
            await reorderTestSuiteMembers(args.id, args.ordered_test_case_ids, args.project),
            null,
            2,
          ));
        }
        case "add_source_requirement": {
          reqArg(args, "id", "add_source_requirement");
          reqArg(args, "requirement_id", "add_source_requirement");
          return ok(JSON.stringify(
            await addTestSuiteSourceRequirement(
              args.id,
              { requirement_id: args.requirement_id },
              args.project,
            ),
            null,
            2,
          ));
        }
        case "remove_source_requirement": {
          reqArg(args, "id", "remove_source_requirement");
          reqArg(args, "requirement_id", "remove_source_requirement");
          await removeTestSuiteSourceRequirement(args.id, args.requirement_id, args.project);
          return ok("Removed");
        }
        default: return err(new Error(`Unknown action: ${args.action}`));
      }
    } catch (e) { return err(e); }
  },
);

// gc_test_run: TC-008 / ADR-049. Execution-time record for one pass through
// a TestSuite against a TestPlan; references plan + suite via FK, snapshots
// resolved test cases as child rows on create, and owns assigned-tester and
// per-case result child rows. Reads (list, get, get-by-uid, list testers,
// list results) route through gc_query.
const TEST_RUN_ACTIONS = [
  "create",
  "update",
  "delete",
  "transition",
  "add_tester",
  "remove_tester",
  "update_result",
  // TC-009 / ADR-050 — runner ops.
  "list_step_results",
  "update_step_result",
  "update_cursor",
];

server.tool(
  "gc_test_run",
  `Test run operations (TC-008 / ADR-049). ` +
    `Actions: ${TEST_RUN_ACTIONS.join(", ")}. ` +
    `Reads (list, get, get-by-uid, testers, results) route through gc_query.`,
  {
    action: z.enum(TEST_RUN_ACTIONS),
    id: z.string().uuid().optional(),
    project: z.string().optional(),
    uid: z.string().optional(),
    name: z.string().optional(),
    test_plan_id: z.string().uuid().optional(),
    test_suite_id: z.string().uuid().optional(),
    environment: z.string().optional(),
    version: z.string().optional(),
    build: z.string().optional(),
    status: z.enum(TEST_RUN_STATUSES).optional(),
    // Timestamps accepted as ISO-8601 strings (e.g. "2026-06-01T00:00:00Z");
    // Jackson binds them to Instant on the backend.
    start_at: z.string().optional(),
    end_at: z.string().optional(),
    // Partial-update clear flags.
    clear_environment: z.boolean().optional(),
    clear_version: z.boolean().optional(),
    clear_build: z.boolean().optional(),
    clear_start_at: z.boolean().optional(),
    clear_end_at: z.boolean().optional(),
    // Tester ops.
    tester_name: z.string().optional(),
    // Per-case result ops.
    test_case_id: z.string().uuid().optional(),
    result_status: z.enum(TEST_RUN_CASE_RESULT_STATUSES).optional(),
    notes: z.string().optional(),
    clear_notes: z.boolean().optional(),
    // TC-009 / ADR-050 — runner ops.
    case_result_id: z.string().uuid().optional(),
    step_result_id: z.string().uuid().optional(),
    step_status: z.enum(TEST_RUN_CASE_RESULT_STATUSES).optional(),
    comment: z.string().optional(),
    clear_comment: z.boolean().optional(),
    executed_at: z.string().optional(),
    clear_executed_at: z.boolean().optional(),
    current_case_result_id: z.string().uuid().optional(),
    current_step_result_id: z.string().uuid().optional(),
    clear_cursor: z.boolean().optional(),
  },
  async (args) => {
    try {
      const TEST_RUN_CREATE_FIELDS = [
        "uid", "name", "test_plan_id", "test_suite_id",
        "environment", "version", "build", "start_at", "end_at",
      ];
      const TEST_RUN_UPDATE_FIELDS = [
        "name", "environment", "version", "build", "start_at", "end_at",
        "clear_environment", "clear_version", "clear_build",
        "clear_start_at", "clear_end_at",
      ];
      switch (args.action) {
        case "create": {
          reqArg(args, "uid", "create");
          reqArg(args, "name", "create");
          reqArg(args, "test_plan_id", "create");
          reqArg(args, "test_suite_id", "create");
          return ok(JSON.stringify(
            await createTestRun(pick(args, TEST_RUN_CREATE_FIELDS), args.project),
            null,
            2,
          ));
        }
        case "update": {
          reqArg(args, "id", "update");
          return ok(JSON.stringify(
            await updateTestRun(args.id, pick(args, TEST_RUN_UPDATE_FIELDS), args.project),
            null,
            2,
          ));
        }
        case "delete": {
          reqArg(args, "id", "delete");
          await deleteTestRun(args.id, args.project);
          return ok("Deleted");
        }
        case "transition": {
          reqArg(args, "id", "transition");
          reqArg(args, "status", "transition");
          return ok(JSON.stringify(
            await transitionTestRunStatus(args.id, args.status, args.project),
            null,
            2,
          ));
        }
        case "add_tester": {
          reqArg(args, "id", "add_tester");
          reqArg(args, "tester_name", "add_tester");
          return ok(JSON.stringify(
            await addTestRunTester(args.id, args.tester_name, args.project),
            null,
            2,
          ));
        }
        case "remove_tester": {
          reqArg(args, "id", "remove_tester");
          reqArg(args, "tester_name", "remove_tester");
          await removeTestRunTester(args.id, args.tester_name, args.project);
          return ok("Removed");
        }
        case "update_result": {
          reqArg(args, "id", "update_result");
          reqArg(args, "test_case_id", "update_result");
          reqArg(args, "result_status", "update_result");
          // Field renaming: the MCP surface exposes `result_status` to keep
          // it disambiguated from the run-level `status`; the backend DTO
          // takes `status`. Build the payload explicitly so toCamelCase
          // does the snake-camel mapping for the rest of the body.
          const payload = { status: args.result_status };
          if (args.notes !== undefined) payload.notes = args.notes;
          if (args.clear_notes !== undefined) payload.clearNotes = args.clear_notes;
          return ok(JSON.stringify(
            await updateTestRunCaseResult(args.id, args.test_case_id, payload, args.project),
            null,
            2,
          ));
        }
        case "list_step_results": {
          // TC-009 — explicit MCP surface for the step-result read. The same
          // GET is reachable via gc_query under the /api/v1/test-runs
          // allow-list, but exposing it as a discoverable action makes the
          // runner end-to-end usable without callers having to know the URL
          // shape.
          reqArg(args, "id", "list_step_results");
          reqArg(args, "case_result_id", "list_step_results");
          return ok(JSON.stringify(
            await listTestRunStepResults(args.id, args.case_result_id, args.project),
            null,
            2,
          ));
        }
        case "update_step_result": {
          reqArg(args, "id", "update_step_result");
          reqArg(args, "case_result_id", "update_step_result");
          reqArg(args, "step_result_id", "update_step_result");
          reqArg(args, "step_status", "update_step_result");
          // Same `result_status`-style disambiguation: the runner-side
          // status is exposed as `step_status` so it never collides with
          // the run-level enum; the backend DTO field is plain `status`.
          const payload = { status: args.step_status };
          if (args.comment !== undefined) payload.comment = args.comment;
          if (args.clear_comment !== undefined) payload.clearComment = args.clear_comment;
          if (args.executed_at !== undefined) payload.executedAt = args.executed_at;
          if (args.clear_executed_at !== undefined) payload.clearExecutedAt = args.clear_executed_at;
          return ok(JSON.stringify(
            await updateTestRunStepResult(
              args.id,
              args.case_result_id,
              args.step_result_id,
              payload,
              args.project,
            ),
            null,
            2,
          ));
        }
        case "update_cursor": {
          reqArg(args, "id", "update_cursor");
          const payload = {};
          if (args.current_case_result_id !== undefined) payload.currentCaseResultId = args.current_case_result_id;
          if (args.current_step_result_id !== undefined) payload.currentStepResultId = args.current_step_result_id;
          if (args.clear_cursor !== undefined) payload.clearCursor = args.clear_cursor;
          return ok(JSON.stringify(
            await updateTestRunCursor(args.id, payload, args.project),
            null,
            2,
          ));
        }
        default: return err(new Error(`Unknown action: ${args.action}`));
      }
    } catch (e) { return err(e); }
  },
);

// gc_risk_governance: methodology profile / risk register record / risk
// assessment result / treatment plan / verification result. Handler logic
// (Zod shape, per-entity per-action allowlist dispatch via lib.js
// GOVERNANCE_FIELDS, backend dispatch) lives in gc-risk-governance.js so the
// adapter is testable in isolation; index.js just registers it.
server.tool(
  "gc_risk_governance",
  GC_RISK_GOVERNANCE_DESCRIPTION,
  gcRiskGovernanceZodShape,
  async (args) => {
    try {
      const result = await gcRiskGovernanceToolHandler(args);
      return ok(result === null ? "Deleted" : JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

// gc_risk_control_mapping: risk-control mapping aggregate (GC-T003 / ADR-052).
// Covers ScopedControlImplementation CRUD, RiskControlMapping CRUD + observation/evidence
// management, and coverage-analysis queries (C5a, C5b, C6, C7/C8 feed).
const RISK_CONTROL_MAPPING_ACTIONS = [
  // SCI
  "sci-create", "sci-update", "sci-delete",
  // Mapping CRUD
  "create", "update", "delete",
  // Mapping observation/evidence (C8)
  "attach-observation", "detach-observation", "add-evidence",
  // Coverage queries
  "unmapped-scenarios", "unmapped-records", "unmapped-controls", "assessment-feed",
];
server.tool(
  "gc_risk_control_mapping",
  `Risk-control mapping operations (GC-T003 / ADR-052). ` +
    `Actions: ${RISK_CONTROL_MAPPING_ACTIONS.join(", ")}. ` +
    `Reads (list, get) route through gc_query. ` +
    `control_role values: ${MAPPING_CONTROL_ROLES.join(", ")}.`,
  {
    action: z.enum(RISK_CONTROL_MAPPING_ACTIONS),
    id: z.string().uuid().optional(),
    project: z.string().optional(),
    // SCI fields
    uid: z.string().max(50).optional(),
    control_id: z.string().uuid().optional(),
    name: z.string().max(200).optional(),
    implementation_scope: z.string().optional(),
    // Mapping fields
    scoped_implementation_id: z.string().uuid().optional(),
    risk_scenario_id: z.string().uuid().optional(),
    risk_register_record_id: z.string().uuid().optional(),
    operational_asset_id: z.string().uuid().optional(),
    control_role: z.enum(MAPPING_CONTROL_ROLES).optional(),
    mapping_objective: z.string().optional(),
    mapping_scope: z.string().optional(),
    methodology_profile_id: z.string().uuid().optional(),
    methodology_influence: z.record(z.unknown()).optional(),
    // C8 fields
    observation_id: z.string().uuid().optional(),
    evidence_ref: z.string().optional(),
    evidence_note: z.string().optional(),
    evidence_artifact_id: z.string().uuid().optional(),
    // Coverage query options
    transitive: z.boolean().optional(),
    assessment_result_id: z.string().uuid().optional(),
  },
  async (args) => {
    try {
      const p = args.project;
      let result;
      switch (args.action) {
        // ---- SCI ----
        case "sci-create":
          result = await createScopedControlImplementation({
            uid: reqArg(args, "uid"), controlId: reqArg(args, "control_id"),
            name: reqArg(args, "name"), implementationScope: args.implementation_scope,
            operationalAssetId: args.operational_asset_id,
          }, p);
          break;
        case "sci-update":
          result = await updateScopedControlImplementation(reqArg(args, "id"), pick(args, {
            name: "name", implementationScope: "implementation_scope",
            operationalAssetId: "operational_asset_id",
          }), p);
          break;
        case "sci-delete":
          await deleteScopedControlImplementation(reqArg(args, "id"), p);
          return ok("Deleted");
        // ---- Mapping CRUD ----
        case "create":
          result = await createRiskControlMapping({
            controlId: args.control_id, scopedImplementationId: args.scoped_implementation_id,
            riskScenarioId: args.risk_scenario_id, riskRegisterRecordId: args.risk_register_record_id,
            operationalAssetId: args.operational_asset_id,
            controlRole: reqArg(args, "control_role"),
            mappingObjective: args.mapping_objective, mappingScope: args.mapping_scope,
            methodologyProfileId: args.methodology_profile_id,
            methodologyInfluence: args.methodology_influence,
          }, p);
          break;
        case "update":
          result = await updateRiskControlMapping(reqArg(args, "id"), pick(args, {
            controlRole: "control_role", mappingObjective: "mapping_objective",
            mappingScope: "mapping_scope", methodologyProfileId: "methodology_profile_id",
            methodologyInfluence: "methodology_influence",
          }), p);
          break;
        case "delete":
          await deleteRiskControlMapping(reqArg(args, "id"), p);
          return ok("Deleted");
        // ---- C8 ----
        case "attach-observation":
          result = await attachMappingObservation(reqArg(args, "id"), reqArg(args, "observation_id"), p);
          break;
        case "detach-observation":
          result = await detachMappingObservation(reqArg(args, "id"), reqArg(args, "observation_id"), p);
          break;
        case "add-evidence":
          result = await addMappingEvidenceRef(reqArg(args, "id"), {
            evidenceRef: reqArg(args, "evidence_ref"),
            evidenceNote: args.evidence_note,
            evidenceArtifactId: args.evidence_artifact_id,
          }, p);
          break;
        // ---- Coverage queries ----
        case "unmapped-scenarios":
          result = await getUnmappedScenarios(p);
          break;
        case "unmapped-records":
          result = await getUnmappedRecords(p, args.transitive !== false);
          break;
        case "unmapped-controls":
          result = await getUnmappedControls(p);
          break;
        case "assessment-feed":
          result = await getAssessmentFeed(reqArg(args, "assessment_result_id"), p);
          break;
        default:
          throw new Error(`Unknown action: ${args.action}`);
      }
      return ok(result === null ? "Deleted" : JSON.stringify(result, null, 2));
    } catch (e) { return err(e); }
  },
);

const ADMIN_ACTIONS = [
  "import_strictdoc", "import_reqif", "sync_github", "sync_github_prs",
  "embed_requirement", "embed_project", "embedding_status",
  "materialize_graph", "create_project", "list_projects",
  "replace_research_intake",
  "run_sweep", "run_sweep_all",
  "export_audit_timeline", "export_requirements", "export_sweep_report", "export_document",
];

if (ADMIN_TOOLS_ENABLED) {
  server.tool(
    "gc_admin",
    `Admin operations: imports, GitHub sync, embeddings, materialization, project create, sweep, exports. ` +
      `Registered only when GC_MCP_ADMIN=1 (these operations require ROLE_ADMIN at the backend per ADR-026). ` +
      `Actions: ${ADMIN_ACTIONS.join(", ")}.`,
    {
      action: z.enum(ADMIN_ACTIONS),
      project: z.string().optional(),
      file_path: z.string().optional(),
      owner: z.string().optional(),
      repo: z.string().optional(),
      requirement_id: z.string().uuid().optional(),
      force: z.boolean().optional(),
      identifier: z.string().optional(),
      name: z.string().optional(),
      description: z.string().optional(),
      document_id: z.string().uuid().optional(),
      format: z.string().optional(),
      from: z.string().optional(),
      to: z.string().optional(),
      // Project type + research intake (ADR-056, issue #999).
      type: z.enum(["SOFTWARE", "GRC", "RESEARCH"]).optional(),
      research_intake: z.object({
        goal: z.string(),
        paperContext: z.string().optional(),
        contributionType: z.enum([
          "TAXONOMY", "REVIEW", "EMPIRICAL_STUDY", "METHODOLOGY", "POSITION", "OTHER",
        ]),
        intendedOutput: z.enum([
          "SCOPING_REVIEW", "SYSTEMATIC_REVIEW", "SYSTEMATIC_MAP", "CRITICAL_REVIEW",
          "NARRATIVE_REVIEW", "TARGETED_RELATED_WORK", "TAXONOMY_PAPER", "OTHER",
        ]),
        autonomyLevel: z.enum(["COPILOT", "AUTONOMOUS"]),
        allowedTools: z.array(z.string()),
        privacyConstraints: z.string().optional(),
        budgetTokens: z.number().int().nonnegative().optional(),
        budgetWallClockMinutes: z.number().int().nonnegative().optional(),
        budgetCostUsdMicros: z.number().int().nonnegative().optional(),
      }).optional(),
    },
    async (args) => {
      try {
        switch (args.action) {
          case "import_strictdoc": reqArg(args, "file_path", "import_strictdoc"); return ok(JSON.stringify(await importStrictdoc(args.file_path, args.project), null, 2));
          case "import_reqif": reqArg(args, "file_path", "import_reqif"); return ok(JSON.stringify(await importReqif(args.file_path, args.project), null, 2));
          case "sync_github": reqArg(args, "owner", "sync_github"); reqArg(args, "repo", "sync_github"); return ok(JSON.stringify(await syncGithub(args.owner, args.repo), null, 2));
          case "sync_github_prs": reqArg(args, "owner", "sync_github_prs"); reqArg(args, "repo", "sync_github_prs"); return ok(JSON.stringify(await syncGithubPrs(args.owner, args.repo), null, 2));
          case "embed_requirement": reqArg(args, "requirement_id", "embed_requirement"); return ok(JSON.stringify(await embedRequirement(args.requirement_id), null, 2));
          case "embed_project": return ok(JSON.stringify(await embedProject(args.project, args.force), null, 2));
          case "embedding_status": reqArg(args, "requirement_id", "embedding_status"); return ok(JSON.stringify(await getEmbeddingStatus(args.requirement_id), null, 2));
          case "materialize_graph": return ok(JSON.stringify(await materializeGraph(), null, 2));
          case "list_projects": return ok(JSON.stringify(await listProjects(), null, 2));
          case "create_project": {
            reqArg(args, "identifier", "create_project"); reqArg(args, "name", "create_project");
            // type + researchIntake are optional (ADR-056); the backend defaults
            // type to SOFTWARE and enforces "researchIntake iff type=RESEARCH".
            const body = {
              identifier: args.identifier,
              name: args.name,
              description: args.description,
            };
            if (args.type) body.type = args.type;
            if (args.research_intake) body.researchIntake = args.research_intake;
            return ok(JSON.stringify(await createProject(body), null, 2));
          }
          case "replace_research_intake": {
            reqArg(args, "identifier", "replace_research_intake");
            reqArg(args, "research_intake", "replace_research_intake");
            return ok(JSON.stringify(await replaceResearchIntake(args.identifier, args.research_intake), null, 2));
          }
          case "run_sweep": return ok(JSON.stringify(await runSweep(args.project), null, 2));
          case "run_sweep_all": return ok(JSON.stringify(await runSweepAll(), null, 2));
          case "export_audit_timeline": return ok(JSON.stringify(await exportAuditTimeline(pick(args, ["project", "from", "to", "format"])), null, 2));
          case "export_requirements": return ok(JSON.stringify(await exportRequirements(args.project, args.format), null, 2));
          case "export_sweep_report": return ok(JSON.stringify(await exportSweepReport(args.project, args.format), null, 2));
          case "export_document": reqArg(args, "document_id", "export_document"); return ok(JSON.stringify(await exportDocument(args.document_id, args.format), null, 2));
          default: return err(new Error(`Unknown action: ${args.action}`));
        }
      } catch (e) { return err(e); }
    },
  );

  // ADR-037 admin-user lifecycle. Registered alongside gc_admin so an
  // ADMIN-role bearer token can drive user management programmatically.
  // Humans manage users via the curl/session flow documented in
  // DEPLOYMENT.md — this PR does not ship a SPA user-management page.
  //
  // **`create_user` is intentionally NOT exposed via MCP.** Passing a new
  // account password as a JSON-RPC tool argument means the password lands in
  // agent transcripts, client logs, debug output, and any observability trace
  // that captures tool-call payloads. Create users via the DEPLOYMENT.md
  // curl flow where the password stays in a mode-600 file. The actions
  // surfaced here mutate state but never accept password material;
  // createAdminUser is exported from lib.js for callers that have an
  // out-of-band secret channel, not for agents.
  const USER_ADMIN_ACTIONS = [
    "list_users", "update_role", "update_enabled", "delete_user",
  ];
  server.tool(
    "gc_user_admin",
    `Admin user lifecycle (ADR-037): list / change-role / enable-disable / delete. ` +
      `Registered only when GC_MCP_ADMIN=1; backend enforces ROLE_ADMIN. ` +
      `User CREATION is intentionally not exposed here — see DEPLOYMENT.md. ` +
      `Actions: ${USER_ADMIN_ACTIONS.join(", ")}.`,
    {
      action: z.enum(USER_ADMIN_ACTIONS),
      username: z.string().optional(),
      role: z.enum(["USER", "ADMIN"]).optional(),
      enabled: z.boolean().optional(),
    },
    async (args) => {
      try {
        switch (args.action) {
          case "list_users":
            return ok(JSON.stringify(await listAdminUsers(), null, 2));
          case "update_role":
            reqArg(args, "username", "update_role");
            reqArg(args, "role", "update_role");
            return ok(JSON.stringify(await updateAdminUserRole(args.username, args.role), null, 2));
          case "update_enabled":
            reqArg(args, "username", "update_enabled");
            if (typeof args.enabled !== "boolean") {
              return err(new Error("update_enabled requires boolean 'enabled'"));
            }
            return ok(JSON.stringify(await updateAdminUserEnabled(args.username, args.enabled), null, 2));
          case "delete_user":
            reqArg(args, "username", "delete_user");
            await deleteAdminUser(args.username);
            return ok(`Deleted user '${args.username}'`);
          default:
            return err(new Error(`Unknown action: ${args.action}`));
        }
      } catch (e) {
        return err(e);
      }
    },
  );
}

const PACK_SUBSYSTEMS = ["plugin", "control_pack", "registry", "trust_policy", "install"];
const PACK_ACTIONS = [
  // plugin
  "register", "unregister", "list_plugins", "get_plugin",
  // control_pack
  "deprecate", "remove", "list_control_packs", "get_control_pack",
  "list_control_pack_entries", "get_control_pack_entry",
  "override_create", "override_delete", "list_control_pack_overrides",
  // registry
  "registry_register", "import", "registry_update", "withdraw",
  "registry_delete", "resolve", "check_compatibility",
  "list_pack_registry_entries", "list_pack_versions", "get_pack_registry_entry",
  // trust_policy
  "create_trust_policy", "update_trust_policy", "delete_trust_policy",
  "list_trust_policies", "get_trust_policy",
  // install
  "install", "upgrade", "list_pack_install_records", "get_pack_install_record",
];

// Per-subsystem field allowlists for create/update DTOs.
const PACK_FIELDS = {
  plugin: ["name", "plugin_type", "version", "endpoint_url", "config", "metadata"],
  control_pack_override: ["status", "rationale", "metadata"],
  registry: ["pack_id", "pack_type", "version", "description", "metadata", "signature", "source_url"],
  trust_policy: ["name", "field", "operator", "value", "outcome", "priority", "metadata"],
  install: ["pack_id", "version", "scope", "config", "metadata"],
};

if (ADMIN_TOOLS_ENABLED) {
  server.tool(
    "gc_pack",
    `Pack ecosystem: plugins, control packs, pack registry, trust policies, install records. ` +
      `Registered only when GC_MCP_ADMIN=1 (these endpoints are ROLE_ADMIN per ADR-026 and denylisted by gc_query). ` +
      `Subsystem: ${PACK_SUBSYSTEMS.join(", ")}. Actions: ${PACK_ACTIONS.join(", ")}.`,
    {
      subsystem: z.enum(PACK_SUBSYSTEMS),
      action: z.enum(PACK_ACTIONS),
      project: z.string().optional(),
      // plugin
      name: z.string().optional(),
      plugin_type: z.enum(PLUGIN_TYPES).optional(),
      capability: z.string().optional(),
      version: z.string().optional(),
      endpoint_url: z.string().optional(),
      config: z.record(z.any()).optional(),
      metadata: z.record(z.any()).optional(),
      // control_pack
      pack_id: z.string().uuid().optional(),
      entry_uid: z.string().optional(),
      override_id: z.string().uuid().optional(),
      status: z.enum(CONTROL_PACK_ENTRY_STATUSES).optional(),
      rationale: z.string().optional(),
      // registry
      pack_type: z.enum(PACK_TYPES).optional(),
      file_path: z.string().optional(),
      description: z.string().optional(),
      signature: z.string().optional(),
      source_url: z.string().optional(),
      // trust_policy
      policy_id: z.string().uuid().optional(),
      field: z.enum(TRUST_POLICY_FIELDS).optional(),
      operator: z.enum(TRUST_POLICY_RULE_OPERATORS).optional(),
      value: z.string().optional(),
      outcome: z.enum(TRUST_OUTCOMES).optional(),
      priority: z.number().int().optional(),
      // install
      install_record_id: z.string().uuid().optional(),
      scope: z.string().optional(),
    },
    async (args) => {
      try {
        switch (args.subsystem) {
          case "plugin": {
            const data = pick(args, PACK_FIELDS.plugin);
            switch (args.action) {
              case "register": return ok(JSON.stringify(await registerPlugin(data, args.project), null, 2));
              case "unregister": reqArg(args, "name", "unregister"); await unregisterPlugin(args.name, args.project); return ok("Unregistered");
              case "list_plugins": return ok(JSON.stringify(await listPlugins(pick(args, ["plugin_type", "capability", "project"])), null, 2));
              case "get_plugin": reqArg(args, "name", "get_plugin"); return ok(JSON.stringify(await getPlugin(args.name), null, 2));
              default: return err(new Error(`Action '${args.action}' not valid for plugin`));
            }
          }
          case "control_pack": {
            const overrideData = pick(args, PACK_FIELDS.control_pack_override);
            switch (args.action) {
              case "deprecate": reqArg(args, "pack_id", "deprecate"); return ok(JSON.stringify(await deprecateControlPack(args.pack_id, args.project), null, 2));
              case "remove": reqArg(args, "pack_id", "remove"); await removeControlPack(args.pack_id, args.project); return ok("Removed");
              case "list_control_packs": return ok(JSON.stringify(await listControlPacks(args.project), null, 2));
              case "get_control_pack": reqArg(args, "pack_id", "get_control_pack"); return ok(JSON.stringify(await getControlPack(args.pack_id, args.project), null, 2));
              case "list_control_pack_entries": reqArg(args, "pack_id", "list_control_pack_entries"); return ok(JSON.stringify(await listControlPackEntries(args.pack_id, args.project), null, 2));
              case "get_control_pack_entry": reqArg(args, "pack_id", "get_control_pack_entry"); reqArg(args, "entry_uid", "get_control_pack_entry"); return ok(JSON.stringify(await getControlPackEntry(args.pack_id, args.entry_uid, args.project), null, 2));
              case "override_create": reqArg(args, "pack_id", "override_create"); reqArg(args, "entry_uid", "override_create"); return ok(JSON.stringify(await createControlPackOverride(args.pack_id, args.entry_uid, overrideData, args.project), null, 2));
              case "override_delete": reqArg(args, "pack_id", "override_delete"); reqArg(args, "entry_uid", "override_delete"); reqArg(args, "override_id", "override_delete"); await deleteControlPackOverride(args.pack_id, args.entry_uid, args.override_id, args.project); return ok("Deleted");
              case "list_control_pack_overrides": reqArg(args, "pack_id", "list_control_pack_overrides"); reqArg(args, "entry_uid", "list_control_pack_overrides"); return ok(JSON.stringify(await listControlPackOverrides(args.pack_id, args.entry_uid, args.project), null, 2));
              default: return err(new Error(`Action '${args.action}' not valid for control_pack`));
            }
          }
          case "registry": {
            const data = pick(args, PACK_FIELDS.registry);
            switch (args.action) {
              case "registry_register": return ok(JSON.stringify(await registerPackRegistryEntry(data, args.project), null, 2));
              case "import": reqArg(args, "file_path", "import"); return ok(JSON.stringify(await importPackRegistryEntry(args.file_path, data, args.project), null, 2));
              case "registry_update": reqArg(args, "pack_id", "registry_update"); reqArg(args, "version", "registry_update"); return ok(JSON.stringify(await updatePackRegistryEntry(args.pack_id, args.version, data, args.project), null, 2));
              case "withdraw": reqArg(args, "pack_id", "withdraw"); reqArg(args, "version", "withdraw"); return ok(JSON.stringify(await withdrawPackRegistryEntry(args.pack_id, args.version, args.project), null, 2));
              case "registry_delete": reqArg(args, "pack_id", "registry_delete"); reqArg(args, "version", "registry_delete"); await deletePackRegistryEntry(args.pack_id, args.version, args.project); return ok("Deleted");
              case "resolve": return ok(JSON.stringify(await resolvePack(data, args.project), null, 2));
              case "check_compatibility": return ok(JSON.stringify(await checkPackCompatibility(data, args.project), null, 2));
              case "list_pack_registry_entries": return ok(JSON.stringify(await listPackRegistryEntries(args.project, pick(args, ["pack_type"])), null, 2));
              case "list_pack_versions": reqArg(args, "pack_id", "list_pack_versions"); return ok(JSON.stringify(await listPackVersions(args.pack_id, args.project), null, 2));
              case "get_pack_registry_entry": reqArg(args, "pack_id", "get_pack_registry_entry"); reqArg(args, "version", "get_pack_registry_entry"); return ok(JSON.stringify(await getPackRegistryEntry(args.pack_id, args.version, args.project), null, 2));
              default: return err(new Error(`Action '${args.action}' not valid for registry`));
            }
          }
          case "trust_policy": {
            const data = pick(args, PACK_FIELDS.trust_policy);
            switch (args.action) {
              case "create_trust_policy": return ok(JSON.stringify(await createTrustPolicy(data, args.project), null, 2));
              case "update_trust_policy": reqArg(args, "policy_id", "update_trust_policy"); return ok(JSON.stringify(await updateTrustPolicy(args.policy_id, data), null, 2));
              case "delete_trust_policy": reqArg(args, "policy_id", "delete_trust_policy"); await deleteTrustPolicy(args.policy_id); return ok("Deleted");
              case "list_trust_policies": return ok(JSON.stringify(await listTrustPolicies(args.project), null, 2));
              case "get_trust_policy": reqArg(args, "policy_id", "get_trust_policy"); return ok(JSON.stringify(await getTrustPolicy(args.policy_id), null, 2));
              default: return err(new Error(`Action '${args.action}' not valid for trust_policy`));
            }
          }
          case "install": {
            const data = pick(args, PACK_FIELDS.install);
            switch (args.action) {
              case "install": return ok(JSON.stringify(await installPackFromRegistry(data, args.project), null, 2));
              case "upgrade": return ok(JSON.stringify(await upgradePackFromRegistry(data, args.project), null, 2));
              case "list_pack_install_records": return ok(JSON.stringify(await listPackInstallRecords(args.project, pick(args, ["pack_id"])), null, 2));
              case "get_pack_install_record": reqArg(args, "install_record_id", "get_pack_install_record"); return ok(JSON.stringify(await getPackInstallRecord(args.install_record_id), null, 2));
              default: return err(new Error(`Action '${args.action}' not valid for install`));
            }
          }
          default: return err(new Error(`Unknown subsystem: ${args.subsystem}`));
        }
      } catch (e) { return err(e); }
    },
  );
}

// ============================================================================
// GC_INTEGRATION_MANAGER — approved-PR integration manager (GC-O011).
// ============================================================================

// Registered via server.tool (Zod-raw-shape form) to match every other tool
// surface in this file. The earlier server.registerTool({inputSchema: <raw
// JSON Schema>}) shape silently broke at call time with
// `v3Schema.safeParseAsync is not a function`: the SDK's registerTool wraps
// the inputSchema in z.object() and invokes safeParseAsync, which is only
// defined on Zod schemas. A raw JSON Schema object passes the registration
// gate but blows up on the first invocation. server.tool with a Zod raw
// shape gives the same validation contract through the path the SDK
// actually exercises. GC_INTEGRATION_MANAGER_INPUT_SCHEMA stays exported
// for documentation and external schema-readers.
server.tool(
  "gc_integration_manager",
  GC_INTEGRATION_MANAGER_DESCRIPTION,
  {
    action: z.enum(["plan", "prepare", "status", "release"]),
    repo_path: z.string().min(1),
    mode: z.enum(["prepare", "enqueue", "merge"]).optional(),
  },
  async (args) => {
    try {
      return ok(JSON.stringify(await runIntegrationManager(args), null, 2));
    } catch (e) { return err(e); }
  },
);

// ============================================================================
// Startup
// ============================================================================

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  const adminNote = ADMIN_TOOLS_ENABLED
    ? "gc_admin + gc_pack enabled (GC_MCP_ADMIN=1)"
    : "gc_admin + gc_pack NOT registered (set GC_MCP_ADMIN=1 to enable)";
  console.error(
    `[ground-control] consolidated MCP surface (ADR-035): ~25-27 tools (was 215). ${adminNote}. Read-only ad-hoc queries via gc_query.`,
  );
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

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
//     gc_control, gc_risk_governance, gc_risk_control_mapping, gc_analyze, gc_graph, gc_baseline,
//     gc_quality_gate, gc_admin, gc_pack, gc_user_admin, gc_identity_admin
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
  getRequirementByUid, listRequirements, getTraceabilityMatrix, createRequirement, updateRequirement,
  transitionStatus, bulkTransitionStatus, archiveRequirement, cloneRequirement,
  createRelation, getRelations, deleteRelation,
  getTraceabilityLinks, getTraceabilityByArtifact, createTraceabilityLink,
  deleteTraceabilityLink,
  // ---- analysis ----
  detectCycles, findOrphans, findCoverageGaps, impactAnalysis,
  crossWaveValidation, detectConsistencyViolations, analyzeCompleteness,
  analyzeStatusDrift, analyzeSemanticSimilarity, getWorkOrder,
  getDashboardStats,
  // ---- history / exports / session (kept for completeness even though tools route to gc_query) ----
  getRequirementHistory, getRelationHistory, getTraceabilityLinkHistory,
  getRequirementTimeline, getRequirementDiff, getProjectTimeline,
  getCurrentSession,
  exportAuditTimeline, exportRequirements, exportSweepReport, exportDocument,
  // ---- graph ----
  materializeGraph, getAncestors, getDescendants, findPaths,
  getGraphVisualization, extractSubgraph, traverseGraph, findGraphPaths,
  // ---- github / codex workflow ----
  createGitHubIssueFromRequirement,
  runSweep, runSweepAll,
  getRepoGroundControlContext,
  runCodexArchitecturePreflight, runCodexReview, runCodexVerifyFinding,
  runTestQualityReview, TEST_QUALITY_REVIEW_HARD_CAP,
  runPostImplementationPlan,
  runAssertTraceabilityReconciled, runAssertQualityGates, runCloseIssueAfterMerge,
  runAssertCompletion,
  runPostDecisionRecord, runPostFinalReport, runRenderPrBody, runLogStepTelemetry,
  runGetIssueThread, runWatchCiRun, runWatchSonarAnalysis,
  runCodexReviewCycle, runTestQualityReviewCycle,
  runReviewCapDisposition,
  runPrepareImplementBranch, runMarkImplementIssuePickedUp,
  runSynchronizeImplementBranch, runCreateSynchronizedImplementPr,
  runAuthorizeExecutionObligationWontfix, runRecordExecutionObligation,
  runResolveWorkflowRoute,
  DECISION_RECORD_REVIEWERS, DECISION_RECORD_DECISIONS, DECISION_RECORD_CLASSIFICATIONS,
  PR_BODY_CHANGE_CLASSES, EXACT_REQUIREMENT_UID_RE,

  TELEMETRY_TIERS, TELEMETRY_OUTCOMES,
  buildCodexReviewToolDescription, buildCodexReviewOverrideCapDescription,
  buildCodexReviewOverrideReasonDescription,
  CODEX_REVIEW_HARD_CAP, CODEX_REVIEW_PREPUSH_HARD_CAP,
  IMPLEMENT_CHECKOUT_MODES,
  IMPLEMENT_BASE_SYNC_ACTIONS, IMPLEMENT_BASE_SYNC_OUTCOMES,
  EXECUTION_OBLIGATION_EVENTS, EXECUTION_OBLIGATION_CATEGORIES,
  EXECUTION_OBLIGATION_PAUSE_CLASSES, EXECUTION_OBLIGATION_DISPOSITIONS,
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
  createRiskScenario, listRiskScenarios, getRiskScenario, updateRiskScenario,
  deleteRiskScenario, transitionRiskScenarioStatus, getRiskScenarioRequirements,
  createRiskScenarioLink, listRiskScenarioLinks, deleteRiskScenarioLink,
  createThreatModel, listThreatModels, getThreatModel, updateThreatModel,
  deleteThreatModel, transitionThreatModelStatus, getThreatModelLinkedRequirements,
  createThreatModelLink, listThreatModelLinks, deleteThreatModelLink,
  // ---- risk-control mapping (GC-T003 / ADR-052) ----
  createScopedControlImplementation, listScopedControlImplementations,
  getScopedControlImplementation, updateScopedControlImplementation,
  deleteScopedControlImplementation,
  createRiskControlMapping, listRiskControlMappings, getRiskControlMapping,
  updateRiskControlMapping, deleteRiskControlMapping,
  attachMappingObservation, detachMappingObservation, addMappingEvidenceRef,
  getUnmappedScenarios, getUnmappedRecords, getUnmappedControls, getAssessmentFeed,
  getUnmappedThreats, getThreatUnmappedControls,
  MAPPING_CONTROL_ROLES,
  createVerificationResult, listVerificationResults, getVerificationResult,
  updateVerificationResult, deleteVerificationResult,
  // ---- packs + plugins ----
  listPlugins, getPlugin, registerPlugin, unregisterPlugin,
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
  // ---- research runs (GC-RSCH-R001/R003/F003/F034/F036/N007/N011/N012/N013, ADR-064 / ADR-065 / ADR-066 / ADR-067 / ADR-068) ----
  startResearchRun, listResearchRuns, getResearchRun, getResearchRunByUid,
  getResearchRunSnapshot, listResearchRunArtifacts, listResearchRunGates,
  recordResearchRunArtifact, advanceResearchRun, decideResearchRunGate,
  stopResearchRun, failResearchRun, resumeResearchRun, completeResearchRun,
  recordResearchRunUsage,
  listResearchRunGateDecisionLog,
  addResearchRunReviewComment, listResearchRunReviewComments, resolveResearchRunReviewComment,
  addResearchRunRationaleEntry, listResearchRunRationale,
  createResearchRunDisclosure, getResearchRunDisclosure, addResearchRunDisclosureEntry,
  selectMethodology, getMethodologySelection, recordMethodologySource,
  updateMethodologySourceState, listMethodologySources, listMethodologyCatalog,
  recordMethodologyRequirementsContract, getMethodologyRequirementsContract,
  recordProtocolPlan, getProtocolPlan,
  METHODOLOGY_SOURCE_STATES, CONTRACT_ENTRY_KINDS,
  PROTOCOL_COVERAGE_DISPOSITIONS, PROTOCOL_ANSWER_PROVENANCES,
  PROTOCOL_SECTION_KINDS, PROTOCOL_SOURCE_ROLES,
  RESEARCH_RUN_AUTONOMY_LEVELS, RESEARCH_RUN_INTENDED_OUTPUTS,
  RESEARCH_RUN_STAGES, RESEARCH_ARTIFACT_TYPES, RESEARCH_GATE_POINTS,
  RESEARCH_GATE_BEHAVIORS, RESEARCH_GATE_DECISION_OUTCOMES,
  GATE_RECOMMENDATION_PROVENANCES,
  REVIEW_COMMENT_TARGETS, REVIEW_COMMENT_PROVENANCES, REVIEW_COMMENT_STATUSES,
  RATIONALE_ENTRY_KINDS, RATIONALE_EVIDENCE_BASES, RATIONALE_PROVENANCES,
  DISCLOSURE_STATUSES, DISCLOSURE_ENTRY_FAMILIES, DISCLOSURE_UNCERTAINTY_CATEGORIES,
  // ---- enums ----
  STATUSES, REQUIREMENT_TYPES, PRIORITIES, RELATION_TYPES,
  ARTIFACT_TYPES, LINK_TYPES, CHANGE_CATEGORIES, CONFIDENCE_LEVELS,
  METRIC_TYPES, COMPARISON_OPERATORS, ADR_STATUSES,
  ASSET_TYPES, ASSET_RELATION_TYPES, ASSET_LINK_TARGET_TYPES, ASSET_LINK_TYPES,
  ASSET_CRITICALITIES, ASSET_ENVIRONMENTS, ASSET_SCOPES, KNOWLEDGE_STATES,
  OBSERVATION_CATEGORIES, RISK_SCENARIO_STATUSES,
  RISK_SCENARIO_LINK_TARGET_TYPES, RISK_SCENARIO_LINK_TYPES,
  THREAT_MODEL_STATUSES, STRIDE_CATEGORIES,
  THREAT_MODEL_LINK_TARGET_TYPES, THREAT_MODEL_LINK_TYPES,
  VERIFICATION_STATUSES, ASSURANCE_LEVELS,
  PLUGIN_TYPES, PLUGIN_LIFECYCLE_STATES,
  PACK_TYPES, PACK_IMPORT_FORMATS, CATALOG_STATUSES,
  TRUST_OUTCOMES, INSTALL_OUTCOMES,
  TRUST_POLICY_FIELDS, TRUST_POLICY_RULE_OPERATORS,
  pick, reqArg,
  validateGovernanceStatus,
  GOVERNANCE_FIELDS,
  PR_BODY_SUMMARY_MAX,
  FINAL_REPORT_SUMMARY_MAX,
  FINAL_REPORT_PLAIN_ENGLISH_OUTCOME_MAX,
  FINAL_REPORT_REVIEW_SUMMARY_MAX,
  KNOWLEDGE_SOURCE_TYPES,
  writeKnowledgeInbox,
  classifyChangedSurface,
} from "./lib.js";
import {
  GC_IMPLEMENT_MECHANICAL_DESCRIPTION,
  gcImplementMechanicalToolHandler,
  gcImplementMechanicalZodShape,
} from "./gc-implement-mechanical.js";
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
  gcResearchProvenanceZodShape,
  gcResearchProvenanceToolHandler,
  GC_RESEARCH_PROVENANCE_DESCRIPTION,
} from "./gc-research-provenance.js";
import {
  gcResearchOperationAuthorizationZodShape,
  gcResearchOperationAuthorizationToolHandler,
  GC_RESEARCH_OPERATION_AUTHORIZATION_DESCRIPTION,
} from "./gc-research-operation-authorization.js";
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
  gcIdentityAdminSchema,
  gcIdentityAdminToolHandler,
  GC_IDENTITY_ADMIN_DESCRIPTION,
} from "./gc-identity-admin.js";
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
import {
  gcWorkflowRunZodShape,
  gcWorkflowRunToolHandler,
  GC_WORKFLOW_RUN_DESCRIPTION,
} from "./gc-workflow-run.js";
import {
  gcWorkflowRunIngestZodShape,
  gcWorkflowRunIngestHandler,
  GC_WORKFLOW_RUN_INGEST_DESCRIPTION,
} from "./gc-workflow-run-ingest.js";
import { installToolTelemetry } from "./telemetry.js";
import { registerQuery } from "./tools/query.js";
import { registerPostDecisionRecord } from "./tools/post-decision-record.js";
import { registerReviewCapDisposition } from "./tools/review-cap-disposition.js";
import { ok, err } from "./tools/respond.js";


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

// pick / reqArg moved to lib.js so the extracted per-tool modules
// (gc-query.js, gc-threat-model.js, link-create.js) share the same helpers
// without coupling to this module (which boots the MCP server at import).

// Admin gating: gc_admin, gc_pack, gc_user_admin, and gc_identity_admin expose
// privileged operations. They register only when GC_MCP_ADMIN is set, so a
// default MCP session does not surface admin operations even if the launching
// environment happens to have an admin bearer token configured.
const ADMIN_TOOLS_ENABLED =
  process.env.GC_MCP_ADMIN === "1" ||
  process.env.GC_MCP_ADMIN === "true" ||
  process.env.GC_MCP_ADMIN === "yes";

const server = new McpServer({ name: "ground-control", version: "1.0.0" });

// Install per-tool telemetry capture (ADR-059, issue #1104).
// Must run BEFORE any server.tool / server.registerTool registration so all
// tools are wrapped. Fail-open: a telemetry write failure never affects the
// original tool result.
installToolTelemetry(server);

// Tool registrations live in ./tools/*. ADMIN_TOOLS_ENABLED travels in a
// context object rather than a shared module because it reads the environment
// loaded above, and an imported module evaluates before this file's body.
const ctx = { ADMIN_TOOLS_ENABLED };

registerQuery(server, ctx);
registerPostDecisionRecord(server, ctx);
registerReviewCapDisposition(server, ctx);

// ============================================================================
// Startup
// ============================================================================

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error(
    "[ground-control] MCP surface over repo-local files (issue #1500): requirements and ADRs live " +
      "in the repo, no backend and no database. The surviving surface is the /implement workflow " +
      "mechanics plus the coding-agent↔reviewer separation tools.",
  );
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

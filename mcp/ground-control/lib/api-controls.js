// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

export function buildReviewCoverage({ slicePlan, reviewerResults, unreviewedUntrackedPaths = [] }) {
  const chunksTotal = slicePlan.slices.length;
  const perReviewer = (Array.isArray(reviewerResults) ? reviewerResults : []).map((r) =>
    Number.isInteger(r?.slices_completed) ? r.slices_completed : 0,
  );
  // The weakest reviewer bounds coverage: a slice only counts as reviewed when
  // BOTH reviewers returned a valid envelope for it.
  const chunksCompleted = perReviewer.length > 0 ? Math.min(...perReviewer) : 0;
  return {
    strategy: slicePlan.strategy,
    chunks_total: chunksTotal,
    chunks_completed: chunksCompleted,
    files_total: slicePlan.files_total,
    files_covered: slicePlan.files_covered,
    oversized_slices: Number.isInteger(slicePlan.oversized_slices) ? slicePlan.oversized_slices : 0,
    // Untracked paths are never transmitted to the reviewer, so the caller is
    // told exactly what the review did NOT cover rather than the omission
    // being silent. Paths only, never content.
    unreviewed_untracked_paths: Array.isArray(unreviewedUntrackedPaths) ? unreviewedUntrackedPaths : [],
    complete:
      chunksTotal > 0 &&
      chunksCompleted === chunksTotal &&
      slicePlan.files_covered === slicePlan.files_total,
  };
}
export function buildReviewCoverageIncompleteEnvelope({
  repoRoot,
  baseBranch,
  uncommitted,
  effectivePr,
  prePushOwnership,
  diffMode,
  reviewCoverage,
  parseErrors,
  core,
  security,
}) {
  return {
    repo_path: repoRoot,
    base_branch: baseBranch,
    uncommitted,
    pr_number: effectivePr,
    issue_number: prePushOwnership ? prePushOwnership.issueNumber : null,
    branch: prePushOwnership ? prePushOwnership.branchName : null,
    ok: false,
    error: "review_coverage_incomplete",
    status: "post_failed",
    message:
      `gc_codex_review did not establish complete diff coverage: ` +
      `${reviewCoverage.chunks_completed} of ${reviewCoverage.chunks_total} review slice(s) ` +
      `returned a valid reviewer envelope (strategy '${reviewCoverage.strategy}', ` +
      `${reviewCoverage.files_covered} of ${reviewCoverage.files_total} file(s) planned). ` +
      `No findings record, decision record, or cycle marker has been written and no cycle was ` +
      `consumed, so a retry is free. A verdict over a partially reviewed diff is not a review.`,
    next_action: "retry_review_after_resolving_coverage_failure",
    diff_mode: diffMode,
    review_coverage: reviewCoverage,
    cycle: null,
    cap: null,
    finding_count: 0,
    comments: [],
    post_failures: [],
    parse_errors: parseErrors,
    reviewers: [
      { name: "core", finding_count: core?.findings?.length ?? 0 },
      { name: "security", finding_count: security?.findings?.length ?? 0 },
    ],
  };
}
// Resolved on every call, not once at module-import time (issue #1562): this
// module evaluates during the static-import graph, which completes before the
// entry point binds `<launch dir>/.env`, so a module-level constant would
// permanently miss a value declared only in that file. Same reasoning as
// getDefaultCodexTimeoutMs in lib/model-subprocess.js.
export function getDefaultCodexReviewParallel() {
  const raw = Number.parseInt(process.env.GC_CODEX_REVIEW_PARALLEL || "", 10);
  return raw === 2 ? 2 : 1;
}
export function dedupFindings(comments) {
  const seen = new Map();
  for (const c of comments) {
    const titlePrefix = String(c.title || "").slice(0, 80).toLowerCase().trim();
    const key = `${c.path || ""}:${c.line ?? ""}:${titlePrefix}`;
    if (!seen.has(key)) {
      seen.set(key, c);
    }
  }
  return Array.from(seen.values());
}
export const CONTROL_STATUSES = ["DRAFT", "PROPOSED", "IMPLEMENTED", "OPERATIONAL", "DEPRECATED", "RETIRED"];
export const CONTROL_FUNCTIONS = ["PREVENTIVE", "DETECTIVE", "CORRECTIVE", "COMPENSATING"];
export const CONTROL_LINK_TARGET_TYPES = [
  "ASSET", "RISK_SCENARIO", "RISK_REGISTER_RECORD", "RISK_ASSESSMENT_RESULT",
  "TREATMENT_PLAN", "METHODOLOGY_PROFILE", "OBSERVATION", "REQUIREMENT",
  "EVIDENCE", "FINDING", "CODE", "CONFIGURATION", "OPERATIONAL_ARTIFACT", "EXTERNAL",
];
export const CONTROL_LINK_TYPES = [
  "PROTECTS", "IMPLEMENTS", "EVIDENCED_BY", "OBSERVED_IN", "MITIGATES", "MAPS_TO", "ASSOCIATED",
];
export const TEST_CASE_STATUSES = ["DRAFT", "APPROVED", "DEPRECATED", "ARCHIVED"];
export const TEST_CASE_TYPES = ["MANUAL", "AUTOMATED", "HYBRID"];
export const TEST_CASE_PRIORITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW"];
export const TEST_CASE_FORMATS = ["STEP_BASED", "GHERKIN"];
export const TEST_PLAN_STATUSES = ["DRAFT", "ACTIVE", "IN_PROGRESS", "COMPLETED", "ARCHIVED"];
export const TEST_SUITE_POPULATION_MODES = ["STATIC", "REQUIREMENTS_BASED", "QUERY_BASED"];
export const TEST_RUN_STATUSES = ["PLANNED", "IN_PROGRESS", "COMPLETED", "ABORTED", "ARCHIVED"];
export const TEST_RUN_CASE_RESULT_STATUSES = ["NOT_RUN", "PASSED", "FAILED", "BLOCKED", "SKIPPED"];
export const PROVENANCE_NODE_KINDS = [
  "USER_GOAL",
  "METHODOLOGY_SOURCE",
  "QUERY",
  "CANDIDATE_SOURCE",
  "FULL_TEXT_ACCESS",
  "CHARTING_CELL",
  "EVIDENCE_MATRIX_CELL",
  "SYNTHESIS_CLAIM",
  "ARGUMENT_MOVE",
  "FINAL_PROSE",
];
export const PROVENANCE_EDGE_RELATIONS = [
  "DERIVED_FROM",
  "SUPPORTS",
  "SELECTED",
  "CITED",
  "CONTRIBUTED_TO",
];
export const RESEARCH_HIGH_RISK_OPERATION_KINDS = [
  "GENERATED_CODE_EXECUTION",
  "BROWSER_ACTIVITY",
  "LAB_HARDWARE_ACTION",
  "EXTERNAL_WRITE",
];
export const RESEARCH_DATA_CLASSES = ["PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED"];
export const RESEARCH_DESTINATION_CLASSES = [
  "LOCAL",
  "AI_PROVIDER",
  "CITATION_PROVIDER",
  "VERSION_CONTROL",
  "REFERENCE_MANAGER",
  "BROWSER_TARGET",
  "EXTERNAL_STORAGE",
  "LAB_HARDWARE",
  "OTHER_EXTERNAL",
];
export const RESEARCH_DATA_FORMS = ["NONE", "DERIVED_METADATA", "SUMMARY", "RAW_CONTENT"];
export const RESEARCH_RUN_AUTONOMY_LEVELS = ["COPILOT", "AUTONOMOUS"];
export const RESEARCH_RUN_INTENDED_OUTPUTS = [
  "SCOPING_REVIEW",
  "SYSTEMATIC_REVIEW",
  "SYSTEMATIC_MAP",
  "CRITICAL_REVIEW",
  "NARRATIVE_REVIEW",
  "TARGETED_RELATED_WORK",
  "TAXONOMY_PAPER",
  "OTHER",
];
export const RESEARCH_RUN_STAGES = [
  "METHODOLOGY_SELECTION",
  "PROTOCOL_PLANNING",
  "SOURCE_SEARCH",
  "SCREENING",
  "CHARTING",
  "SYNTHESIS",
  "ARGUMENT_CONSTRUCTION",
  "PROSE_DRAFTING",
];
export const RESEARCH_ARTIFACT_TYPES = [
  "METHODOLOGY_REQUIREMENTS",
  "PROTOCOL_PLAN",
  "SEARCH_LOG",
  "SCREENING_RESULT",
  "CHARTING_DATA",
  "SYNTHESIS",
  "ARGUMENT_MAP",
  "MANUSCRIPT",
];
export const CONTRACT_ENTRY_KINDS = [
  "REQUIREMENT",
  "METHOD_LIMIT",
  "NON_CLAIM",
  "OPEN_PROTOCOL_QUESTION",
];
export const PROTOCOL_COVERAGE_DISPOSITIONS = [
  "FILLED",
  "RESOLVED_BY_USER_DECISION",
  "DEFERRED_NON_BLOCKING",
  "NOT_APPLICABLE_WITH_RATIONALE",
  "BLOCKING_DECISION_REQUIRED",
];
export const PROTOCOL_ANSWER_PROVENANCES = [
  "METHODOLOGY_SOURCE",
  "RESEARCH_INTAKE",
  "USER_DECISION",
  "CITED_SOURCE",
  "DEFERRED_PILOT",
  "ADAPTER_OUTPUT",
];
export const PROTOCOL_SECTION_KINDS = [
  "PCC_SCOPE_FRAMING",
  "INFORMATION_SOURCES",
  "SEARCH_STRATEGY",
  "ELIGIBILITY_CRITERIA",
  "DATABASES_SEARCH_STRINGS",
  "SCREENING",
  "DATA_EXTRACTION",
  "CHARTING",
  "RISK_OF_BIAS_POSTURE",
  "SYNTHESIS_PLAN",
  "SYNTHESIS_REPORTING",
  "REPORTING_STANDARD",
  "CERTAINTY_CLAIM_LIMITS",
  "CONSULTATION_POSTURE",
  "CRITICAL_APPRAISAL_DECISION",
  "PROTOCOL_REGISTRATION",
  "MAPPING_QUESTIONS",
  "SEARCH_SCREENING_PLAN",
  "CODING_MAP_SCHEMA",
  "CLASSIFICATION_PROVENANCE",
  "VISUALIZATION_OUTPUT",
  "CLAIM_LIMITS",
  "THEORETICAL_FRAME",
  "SELECTION_RATIONALE",
  "APPRAISAL_CRITIQUE_DIMENSIONS",
  "SYNTHESIS_ARGUMENT_POSTURE",
  "INCLUSION_LIMITS",
  "BOUNDED_PURPOSE",
  "SEED_SOURCE_STRATEGY",
  "INCLUSION_RATIONALE",
  "COMPARISON_DIMENSIONS",
  "NON_EXHAUSTIVENESS_DISCLOSURE",
  "META_CHARACTERISTIC",
  "UNIT_OF_ANALYSIS",
  "SOURCE_ROLES",
  "STARTING_CONCEPTS",
  "CONSTRUCTION_PROCEDURE",
  "ITERATION_LOG_PROTOCOL",
  "ENDING_CONDITIONS",
  "EVALUATION_PLAN",
  "VALIDITY_THREATS",
  "METHOD_LIMITS",
  "NON_CLAIMS",
];
export const PROTOCOL_SOURCE_ROLES = [
  "TAXONOMY_INSTANCE_CORPUS",
  "BACKGROUND_FRAMING",
  "METHODOLOGY_LITERATURE",
  "VALIDATION_EVALUATION",
];
export const RESEARCH_GATE_POINTS = [
  "METHOD_DECISION",
  "PROTOCOL_DECISION",
  "SEARCH_DECISION",
  "SYNTHESIS_DECISION",
  "WRITING_DECISION",
];
export const RESEARCH_GATE_BEHAVIORS = ["REQUIRE_HUMAN", "AUTONOMOUS_DEFAULT", "DISABLED"];
export const RESEARCH_GATE_DECISION_OUTCOMES = ["APPROVED", "REJECTED", "AUTO_ACCEPTED"];
export const GATE_RECOMMENDATION_PROVENANCES = ["AGENT", "SYSTEM_POLICY", "HUMAN_REVIEWER"];
export const REVIEW_COMMENT_TARGETS = ["RUN", "GATE_POINT", "STAGE", "ARTIFACT", "DECISION_LOG"];
export const REVIEW_COMMENT_PROVENANCES = ["HUMAN_REVIEW", "AGENT_RECOMMENDATION", "SYSTEM_CHECK"];
export const REVIEW_COMMENT_STATUSES = ["OPEN", "RESOLVED"];
export const RATIONALE_ENTRY_KINDS = [
  "METHODOLOGY_CHOICE",
  "SEARCH_DECISION",
  "EXCLUSION",
  "CHARTED_VALUE",
  "SYNTHESIS_CLAIM",
  "WRITING_CLAIM",
];
export const RATIONALE_EVIDENCE_BASES = [
  "METHODOLOGY_SOURCE",
  "USER_DECISION",
  "CITED_SOURCE",
  "FULL_TEXT_SPAN",
  "CHARTED_CELL",
  "EVIDENCE_MATRIX_CELL",
  "ARGUMENT_MAP_PREMISE",
  "MANUSCRIPT_CITATION",
  "POLICY_DEFAULT",
  "EXPLICIT_LIMITATION",
];
export const RATIONALE_PROVENANCES = [
  "HUMAN",
  "AGENT_RECOMMENDATION",
  "AUTONOMOUS_DEFAULT",
  "IMPORTED_ARTIFACT",
  "ADAPTER",
];
export const DISCLOSURE_STATUSES = ["CURRENT", "STALE"];
export const DISCLOSURE_ENTRY_FAMILIES = ["AI_GENERATED_PART", "UNRESOLVED_UNCERTAINTY"];
export const DISCLOSURE_UNCERTAINTY_CATEGORIES = [
  "SCIENTIFIC",
  "ACCESS_GAP",
  "WORKFLOW_ERROR",
  "UNRESOLVED_REVIEW",
];
export const METHODOLOGY_SOURCE_STATES = ["ATTEMPTED", "OBTAINED", "READ", "BLOCKED"];

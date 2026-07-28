// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { request } from "./api-controls-2.js";
import { getRequirementByUid, getTraceabilityLinks } from "./api-controls-3.js";
import { evaluateQualityGates } from "./api-history.js";
import { runAssertTraceabilityReconciled } from "./assert-traceability.js";
import { resolvePrForClose } from "./close-issue.js";
import { runPostFinalReport } from "./doc-coverage-2.js";
import { getOwnerRepo } from "./grc-legacy-compat-3.js";
import { ensureGitRepo, readTrustedExecutionObligationState } from "./grc-legacy-compat-4.js";
import { validateFinalReportInput } from "./plan-posting.js";
import { buildQualityGateAssertion, isActiveDocumentsCoverageGate } from "./sonar-watcher.js";

export async function runSweep(project) {
  return request("POST", "/api/v1/analysis/sweep", { params: { project } });
}
export async function runSweepAll() {
  return request("POST", "/api/v1/analysis/sweep/all");
}
async function findMissingInScopeDocumentsLinks({ evaluation, project, requirements }) {
  const gates = Array.isArray(evaluation?.gates) ? evaluation.gates : [];
  const documentsGateActive = gates.some(isActiveDocumentsCoverageGate);
  if (!documentsGateActive || !Array.isArray(requirements) || requirements.length === 0) {
    return { ok: true, checked: false, missing: [] };
  }

  const missing = [];
  for (const item of requirements) {
    if (!item || typeof item !== "object" || typeof item.uid !== "string" || item.uid.trim() === "") {
      return {
        ok: false,
        error: "in_scope_requirement_input_invalid",
        message: "requirements[] entries must be { uid: <non-empty string>, statusIntent?: <status> }",
      };
    }
    const uid = item.uid.trim();
    const requirement = await getRequirementByUid(uid, project);
    const actualStatus = typeof requirement?.status === "string" ? requirement.status : null;
    const links = await getTraceabilityLinks(requirement.id);
    const hasDocumentsLink = Array.isArray(links) && links.some((link) => link?.link_type === "DOCUMENTS");
    if (!hasDocumentsLink) {
      missing.push({ uid, status: actualStatus, missing_link_type: "DOCUMENTS" });
    }
  }

  return { ok: true, checked: true, missing };
}
export async function runAssertQualityGates({ project, requirements }) {
  if (typeof project !== "string" || project.trim() === "") {
    throw new Error("gc_assert_quality_gates requires a non-empty project");
  }
  if (!Array.isArray(requirements)) {
    throw new Error("gc_assert_quality_gates requires requirements[]; pass [] when there are no in-scope requirements");
  }
  const trimmed = project.trim();
  const evaluation = await evaluateQualityGates(trimmed);
  const assertion = buildQualityGateAssertion(evaluation, trimmed);
  if (assertion.ok !== true) {
    return assertion;
  }

  const inScopeDocuments = await findMissingInScopeDocumentsLinks({
    evaluation,
    project: trimmed,
    requirements,
  });
  if (inScopeDocuments.ok !== true) {
    return {
      ok: false,
      error: inScopeDocuments.error,
      message: inScopeDocuments.message,
      project: trimmed,
      next_action: "fix_in_scope_requirement_input_and_retry",
    };
  }
  if (inScopeDocuments.missing.length > 0) {
    return {
      ok: false,
      error: "in_scope_documentation_coverage_failed",
      message:
        "Active DOCUMENTS Coverage requires every in-scope requirement to carry a DOCUMENTS traceability link, " +
        "regardless of status: " +
        inScopeDocuments.missing.map((m) => `${m.uid} (${m.status ?? "unknown"}) missing ${m.missing_link_type}`).join("; "),
      project: trimmed,
      missing_documents: inScopeDocuments.missing,
      next_action: "add_documents_traceability_links_and_retry",
    };
  }

  return {
    ...assertion,
    in_scope_documents_checked: inScopeDocuments.checked,
  };
}
export async function runAssertCompletion(input) {
  const {
    repoPath,
    issueNumber,
    prNumber,
    requirements = [],
    files,
    reviews,
    traceability,
    ciStatus,
    sonarStatus,
    planCommentUrl,
    summary,
    plainEnglishOutcome,
    documentation_outcome,
    touchedFiles = [],
    project = null,
    override = false,
    overrideReason = null,
    phase = "post_merge",
  } = input;

  const assertions = [];

  // Fail-fast: validate the final-report sub-input BEFORE any side effects.
  const subInput = {
    issueNumber,
    prNumber,
    requirements: requirements.map((r) => ({
      uid: r.uid,
      title: r.title ?? r.uid,
      status: r.status ?? r.statusIntent ?? "ACTIVE",
      note: r.note ?? undefined,
    })),
    files: files ?? {},
    reviews: reviews ?? [],
    traceability: traceability ?? {},
    ciStatus,
    sonarStatus,
    planCommentUrl: planCommentUrl ?? null,
    summary: summary ?? null,
    plainEnglishOutcome: plainEnglishOutcome ?? null,
    documentation_outcome: documentation_outcome ?? null,
    lane: "implement",
  };
  const validation = validateFinalReportInput(subInput);
  if (!validation.ok) {
    return {
      ok: false,
      error: "completion_final_report_input_invalid",
      message: validation.errors.join("; "),
      issue_number: issueNumber,
      assertions,
      final_report: null,
    };
  }

  // A discovered real problem is a durable obligation of the current
  // /implement run. Both the Phase D readiness record and Phase E completion
  // fail closed while a trusted issue-thread obligation remains open. Cached
  // arrays and caller summaries are intentionally ignored.
  let obligationRepoRoot;
  let obligationOwnerRepo;
  try {
    obligationRepoRoot = await ensureGitRepo(repoPath);
    obligationOwnerRepo = await getOwnerRepo(obligationRepoRoot);
  } catch (error) {
    return {
      ok: false,
      error: "completion_obligation_state_failed",
      message: error.message,
      issue_number: issueNumber,
      assertions,
      final_report: null,
    };
  }
  const obligationState = await readTrustedExecutionObligationState(
    obligationRepoRoot,
    obligationOwnerRepo.owner,
    obligationOwnerRepo.name,
    issueNumber,
  );
  if (!obligationState.ok) {
    return {
      ok: false,
      error: obligationState.error,
      message: obligationState.message,
      issue_number: issueNumber,
      assertions,
      final_report: null,
      next_action: "repair_execution_obligation_record_and_retry",
    };
  }
  if (!obligationState.clear) {
    return {
      ok: false,
      error: "completion_open_execution_obligations",
      message:
        `gc_assert_completion refuses readiness/completion while execution obligations remain open: ` +
        obligationState.open_obligation_ids.join(", "),
      issue_number: issueNumber,
      open_obligation_ids: obligationState.open_obligation_ids,
      assertions,
      final_report: null,
      next_action: "fix_and_resolve_open_obligations_then_retry",
    };
  }

  // Phase D terminal (phase="pre_merge", issue #963): post the ready-for-review
  // record only. The requirement-status transition and traceability
  // reconciliation have NOT run yet — they are Phase E work that lands after the
  // PR merges — so this path skips that assertion and posts no `gc:final-report`
  // marker. Every input gate (CI green, Sonar pass/legit-skip, codex review
  // present, sensitive/reserved/defer scrubs) still runs inside runPostFinalReport.
  // Traceability reconciliation is deliberately NOT asserted here — it depends
  // on the post-merge DRAFT→ACTIVE transition and is verified by the
  // phase="post_merge" completion.
  if (phase === "pre_merge") {
    const readiness = await runPostFinalReport({
      ...subInput,
      repoPath,
      issueNumber,
      prNumber,
      phase: "pre_merge",
    });
    if (!readiness.ok) {
      return {
        ok: false,
        error: readiness.error,
        message: readiness.message,
        issue_number: issueNumber,
        assertions,
        final_report: null,
        next_action: readiness.next_action ?? null,
      };
    }
    return {
      ok: true,
      repo_path: readiness.repo_path,
      issue_number: issueNumber,
      pr_number: prNumber,
      phase: "pre_merge",
      assertions,
      readiness_report: {
        comment_url: readiness.comment_url,
        comment_id: readiness.comment_id,
      },
      final_report: null,
    };
  }

  // Phase E (phase="post_merge", default): the reconciled completion record is
  // merge-gated (issue #963). Refuse to run the assertions or post the final
  // report unless the linked PR is actually merged — this is the structural
  // guarantee that Ground Control state (ACTIVE transitions, IMPLEMENTS/TESTS
  // links, the durable final report) never lands ahead of shipped code, mirroring
  // gc_close_issue_after_merge. resolvePrForClose validates a supplied pr_number
  // is linked to the issue and otherwise resolves the merged PR from the timeline;
  // its `close_*` resolver errors are re-mapped to `completion_*` here.
  const mergeRepoRoot = await ensureGitRepo(repoPath);
  const { owner: mergeOwner, name: mergeName } = await getOwnerRepo(mergeRepoRoot);
  const resolvedPr = await resolvePrForClose({
    repoRoot: mergeRepoRoot,
    owner: mergeOwner,
    name: mergeName,
    issueNumber,
    prNumber,
  });
  if (resolvedPr.earlyReturn) {
    return {
      ok: false,
      error: String(resolvedPr.earlyReturn.error).replace(/^close_/, "completion_"),
      message: resolvedPr.earlyReturn.message,
      issue_number: issueNumber,
      assertions,
      final_report: null,
      next_action: resolvedPr.earlyReturn.next_action ?? null,
    };
  }
  const mergedPr = resolvedPr.pr;
  if (!mergedPr?.mergedAt || mergedPr.state !== "MERGED") {
    return {
      ok: false,
      error: "completion_pr_not_merged",
      message:
        `gc_assert_completion refuses to post the reconciled completion record for issue #${issueNumber}: ` +
        `linked PR #${mergedPr?.number ?? "?"} state=${mergedPr?.state ?? "unknown"}, merged_at=${mergedPr?.mergedAt ?? "null"}. ` +
        `The Phase E completion gate requires merged_at non-null AND state='MERGED'.`,
      issue_number: issueNumber,
      pr_state: mergedPr?.state ?? null,
      pr_merged_at: mergedPr?.mergedAt ?? null,
      assertions,
      final_report: null,
      next_action: "wait_for_user_to_merge_the_pr",
    };
  }

  // Step 1: traceability assertion
  const trace = await runAssertTraceabilityReconciled({
    repoPath,
    issueNumber,
    requirements: requirements.map((r) => ({
      uid: r.uid,
      statusIntent: r.statusIntent ?? r.status ?? "ACTIVE",
    })),
    project,
    touchedFiles,
    override,
    overrideReason,
  });
  assertions.push({
    name: "traceability_reconciled",
    ok: trace.ok,
    comment_url: trace.comment_url ?? null,
    comment_id: trace.comment_id ?? null,
  });
  if (!trace.ok) {
    return {
      ok: false,
      error: trace.error,
      message: trace.message,
      issue_number: issueNumber,
      assertions,
      final_report: null,
      next_action: trace.next_action ?? null,
    };
  }

  // Step 2: final report (use internalVerifiedPhases to avoid read-after-write race)
  const report = await runPostFinalReport({
    ...subInput,
    repoPath,
    issueNumber,
    prNumber,
    internalVerifiedPhases: ["traceability_reconciled"],
  });
  if (!report.ok) {
    return {
      ok: false,
      error: report.error,
      message: report.message,
      issue_number: issueNumber,
      assertions,
      final_report: null,
      next_action: report.next_action ?? null,
    };
  }

  return {
    ok: true,
    repo_path: report.repo_path,
    issue_number: issueNumber,
    pr_number: prNumber,
    assertions,
    final_report: {
      comment_url: report.comment_url,
      comment_id: report.comment_id,
    },
  };
}

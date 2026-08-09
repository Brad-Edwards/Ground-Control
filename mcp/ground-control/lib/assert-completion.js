// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { resolvePrForClose } from "./close-issue.js";
import { runPostFinalReport } from "./doc-coverage-2.js";
import { getOwnerRepo } from "./grc-legacy-compat-3.js";
import { ensureGitRepo, readTrustedExecutionObligationState } from "./grc-legacy-compat-4.js";
import { validateFinalReportInput } from "./plan-posting.js";

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

  // Traceability reconciliation is retired as an MCP operation (issue #1500): the
  // requirement files ARE the record now, and the agent edits each in-scope
  // requirement's frontmatter status (DRAFT→ACTIVE) and `## Traceability` section as
  // part of its diff, reviewed in the PR like any code. The post-merge final report
  // therefore depends only on the real gates runPostFinalReport still enforces — CI
  // green, Sonar pass-or-legit-skipped, the mandatory Codex review, and the
  // sensitive/defer/reserved-marker scrubs.
  const report = await runPostFinalReport({
    ...subInput,
    repoPath,
    issueNumber,
    prNumber,
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

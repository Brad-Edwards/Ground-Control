// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { join } from "node:path";
import { readAbsoluteTextFile } from "./api-requirements.js";
import { parseGroundControlYaml } from "./ground-control-config.js";
import { buildFinalReportMarker, renderCiStatus, renderDocumentationSection, renderSonarStatus } from "./doc-coverage.js";
import { detectSensitiveBodyContent, extractGhErrorMessage } from "./grc-legacy-compat-2.js";
import { getOwnerRepo } from "./grc-legacy-compat-3.js";
import { ensureGitRepo } from "./grc-legacy-compat-4.js";
import { buildQuickfixCloseComment, validateFinalReportInput } from "./plan-posting.js";
import { GITHUB_ISSUE_COMMENT_BODY_MAX, rejectReservedMarkerSequence } from "./repo-vocabulary.js";
import { detectDeferralDisposition, execFile } from "./runtime-primitives.js";
import { FINAL_REPORT_FILE_KINDS } from "./test-quality-prompt.js";

export function buildFinalReport(input) {
  const validation = validateFinalReportInput(input);
  if (!validation.ok) {
    throw new Error(`buildFinalReport input invalid: ${validation.errors.join("; ")}`);
  }
  const { issueNumber, prNumber, requirements, files = {}, reviews, traceability = {}, ciStatus, sonarStatus, planCommentUrl, summary, lane, plainEnglishOutcome, phase = "post_merge" } = input;
  // Slim quickfix renderer (issue #906 codex cycle-3 F2). When lane='quickfix'
  // the close comment is structurally smaller: no "In-scope requirements",
  // no "Traceability reconciliation", no "Reviews" section when empty.
  // The /implement final-report sections become empty noise on a /quickfix
  // run; the slim renderer matches the SKILL.md Step Q19 contract.
  if (lane === "quickfix") {
    return buildQuickfixCloseComment({
      issueNumber, prNumber, files, reviews, ciStatus, sonarStatus, planCommentUrl, summary,
    });
  }
  // Phase D (pre_merge) renders a "ready for review" record carrying a
  // `ready_for_review` phase marker; the requirement-status transition and
  // traceability reconciliation have NOT run yet — they land in Phase E after
  // the PR merges (issue #963). Phase E (post_merge, default) renders the
  // reconciled final report carrying the `gc:final-report` marker.
  const isPreMerge = phase === "pre_merge";
  const lines = [];
  lines.push(
    isPreMerge
      ? `<!-- gc:phase phase="ready_for_review" issue="${issueNumber}" -->`
      : buildFinalReportMarker({ issueNumber, prNumber }),
  );
  lines.push("");
  lines.push(
    isPreMerge
      ? `## Ready for review — issue #${issueNumber}`
      : `## Final report — issue #${issueNumber} complete`,
  );
  lines.push("");
  lines.push(`**PR:** #${prNumber}  `);
  if (planCommentUrl) lines.push(`**Plan:** ${planCommentUrl}`);
  lines.push("");
  lines.push("### Outcome");
  lines.push("");
  lines.push(plainEnglishOutcome.trim());
  if (summary) {
    lines.push("");
    lines.push(summary.trim());
  }
  if (requirements.length > 0) {
    lines.push("");
    lines.push(`### In-scope requirements`);
    lines.push("");
    for (const r of requirements) {
      const note = r.note ? ` — ${r.note}` : "";
      lines.push(`- \`${r.uid}\` (${r.title}) — ${r.status}${note}`);
    }
  }
  lines.push("");
  lines.push(`### Files changed`);
  lines.push("");
  let anyFiles = false;
  for (const kind of FINAL_REPORT_FILE_KINDS) {
    const list = Array.isArray(files[kind]) ? files[kind] : [];
    if (list.length === 0) continue;
    anyFiles = true;
    lines.push(`**${kind[0].toUpperCase() + kind.slice(1)}:**`);
    lines.push("");
    for (const p of list) lines.push(`- \`${p}\``);
    lines.push("");
  }
  if (!anyFiles) {
    lines.push("- (none)");
    lines.push("");
  }
  if (reviews.length > 0) {
    lines.push(`### Reviews`);
    lines.push("");
    for (const r of reviews) lines.push(`- **${r.reviewer}:** ${r.summary}`);
    lines.push("");
  }
  lines.push(`### Traceability reconciliation`);
  lines.push("");
  if (isPreMerge) {
    lines.push(`- Pending — requirement status transition and IMPLEMENTS/TESTS reconciliation run in Phase E once the PR merges.`);
  } else {
    const tAdded = Array.isArray(traceability.added) ? traceability.added : [];
    const tUpdated = Array.isArray(traceability.updated) ? traceability.updated : [];
    const tDeleted = Array.isArray(traceability.deleted) ? traceability.deleted : [];
    lines.push(`- IMPLEMENTS / TESTS / DOCUMENTS added: ${tAdded.length}`);
    lines.push(`- Links updated: ${tUpdated.length}`);
    lines.push(`- Stale links removed: ${tDeleted.length}`);
    if (typeof traceability.notes === "string" && traceability.notes.trim() !== "") {
      lines.push("");
      lines.push(traceability.notes.trim());
    }
  }
  lines.push("");
  lines.push(`### Status`);
  lines.push("");
  lines.push(`- CI: ${renderCiStatus(ciStatus)}`);
  lines.push(`- SonarCloud: ${renderSonarStatus(sonarStatus)}`);
  lines.push(
    isPreMerge
      ? `- PR ready for user review and merge. Ground Control reconciliation (requirement status + traceability) runs on merge (Phase E).`
      : `- PR ready for user review and merge.`,
  );
  // Optional documentation outcome section (issue #896, ADR-054).
  if (input.documentation_outcome != null) {
    lines.push("");
    for (const l of renderDocumentationSection(input.documentation_outcome)) lines.push(l);
  }
  return lines.join("\n");
}
export async function runPostFinalReport(input) {
  const { repoPath } = input;
  const rest = { ...input };
  delete rest.repoPath;
  const validation = validateFinalReportInput(rest);
  if (!validation.ok) {
    return {
      ok: false,
      error: "final_report_input_invalid",
      message: validation.errors.join("; "),
      issue_number: rest.issueNumber ?? null,
    };
  }
  // A Step 19 final report says "PR ready for user review and merge." That
  // claim is FALSE when CI is anything other than green or SonarCloud failed.
  // Refuse to publish a durable ready-for-merge marker against non-green
  // gates. Sonar 'skipped' remains legitimate (the repo has no sonarcloud
  // config — cfg.sonarcloud null path); CI 'skipped' is NOT legitimate for a
  // real PR — Step 10 makes CI mandatory. The schema permits 'skipped' for
  // test fixtures and pure renderer tests, but the runner refuses it.
  if (rest.ciStatus !== "green") {
    return {
      ok: false,
      error: "final_report_ci_not_green",
      message: `ciStatus='${rest.ciStatus}' — a Step 19 final report claims PR-ready-for-merge; only ciStatus='green' is accepted by the runner`,
      issue_number: rest.issueNumber,
      next_action: "fix_ci_to_green_and_retry",
    };
  }
  if (rest.sonarStatus === "failed") {
    return {
      ok: false,
      error: "final_report_sonar_failed",
      message: "sonarStatus='failed' — a final report claims PR-ready-for-merge; resolve SonarCloud findings before publishing the Step 19 record",
      issue_number: rest.issueNumber,
      next_action: "fix_sonar_and_retry",
    };
  }
  // Step 19 is supposed to preserve review evidence for the run. An empty
  // reviews[] (or one without a codex entry) would render an incomplete
  // record while still posting a `gc:final-report` marker. The pre-push
  // Codex review is mandatory for every /implement run; the runner refuses
  // a final report without at least one codex review entry. (codex cycle-3
  // F4 widened by cycle-4 F3.)
  //
  // The `lane: "quickfix"` carve-out (issue #906) intentionally relaxes
  // these two checks: /quickfix runs with AI-assisted reviews off by
  // default and the Q19 close comment is structurally smaller than a
  // /implement Step 19 final report. The relaxation is bounded — every
  // other gate (CI green, Sonar pass-or-legit-skipped, sensitive-content
  // scrub, no-defer scrub, reserved-marker scrub) still applies — so the
  // server-side filters that make this tool the only driver-neutral
  // close-comment surface remain in force.
  const isQuickfixLane = rest.lane === "quickfix";
  // The `lane: "quickfix"` carve-out is bounded by the lane's own
  // requirement-free invariant: a /quickfix run cannot have requirements in
  // scope (per the SKILL.md hard precondition). Reject the combination
  // server-side so a caller cannot bypass the review-evidence gate by
  // setting `lane: "quickfix"` on an `/implement`-shape payload (codex
  // cycle-3 F1 + security F1). The tool cannot verify the issue body's
  // `## Requirements` section without an extra GitHub round-trip, but
  // rejecting the inconsistent payload shape covers the realistic case.
  if (isQuickfixLane && Array.isArray(rest.requirements) && rest.requirements.length > 0) {
    return {
      ok: false,
      error: "final_report_quickfix_with_requirements",
      message:
        "lane='quickfix' is incompatible with requirements.length > 0; /quickfix runs are " +
        "requirement-free by precondition. If the run actually has requirements in scope, drop " +
        "lane='quickfix' and provide the mandatory codex review entry; if it does not, pass " +
        "requirements: [].",
      issue_number: rest.issueNumber,
      next_action: "drop_lane_quickfix_or_drop_requirements_and_retry",
    };
  }
  if (!isQuickfixLane) {
    if (!Array.isArray(rest.reviews) || rest.reviews.length === 0) {
      return {
        ok: false,
        error: "final_report_no_reviews",
        message: "reviews[] is empty — Step 19 requires at least the pre-push Codex review summary; pass a reviews entry like { reviewer: 'codex', summary: '<cycle history + outcome>' } (or pass lane='quickfix' for the /quickfix slim path where AI reviews are opt-in)",
        issue_number: rest.issueNumber,
        next_action: "collect_review_summaries_and_retry",
      };
    }
    const hasCodexReview = rest.reviews.some((r) => r && typeof r === "object" && r.reviewer === "codex");
    if (!hasCodexReview) {
      return {
        ok: false,
        error: "final_report_codex_review_missing",
        message: "reviews[] does not include a 'codex' entry — the pre-push Codex review is mandatory per ADR-029; add a reviews entry with reviewer:'codex' (or pass lane='quickfix' for the /quickfix slim path)",
        issue_number: rest.issueNumber,
        next_action: "add_codex_review_entry_and_retry",
      };
    }
  }
  // If the caller claims sonarStatus='skipped', validate that the repo
  // actually has no sonarcloud config (codex cycle-4 F3). Otherwise a caller
  // could publish a "PR ready" record for a sonar-configured repo without
  // having run SonarCloud. Load .ground-control.yaml and check `sonarcloud`.
  // If yaml is missing or invalid, surface that distinctly rather than
  // accepting 'skipped' by accident.
  if (rest.sonarStatus === "skipped") {
    let cfgRepoRoot;
    try {
      cfgRepoRoot = await ensureGitRepo(repoPath);
    } catch (error) {
      return { ok: false, error: "final_report_repo_not_git", message: error.message, issue_number: rest.issueNumber };
    }
    let yamlText;
    try {
      yamlText = readAbsoluteTextFile(join(cfgRepoRoot, ".ground-control.yaml"));
    } catch (error) {
      if (error.code !== "ENOENT") {
        return { ok: false, error: "final_report_config_read_failed", message: error.message, issue_number: rest.issueNumber };
      }
      // No config file → 'skipped' is legitimate (no Ground Control wiring)
      yamlText = null;
    }
    if (yamlText != null) {
      const parsed = parseGroundControlYaml(yamlText);
      if (!parsed.ok) {
        return { ok: false, error: "final_report_config_invalid", message: parsed.errors.join("; "), issue_number: rest.issueNumber };
      }
      if (parsed.value.sonarcloud != null) {
        return {
          ok: false,
          error: "final_report_sonar_skipped_but_configured",
          message: "sonarStatus='skipped' but .ground-control.yaml has a sonarcloud block; SonarCloud must be run for sonar-configured repos before publishing the Step 19 record",
          issue_number: rest.issueNumber,
          next_action: "run_sonarcloud_and_pass_sonar_status_passed_or_failed",
        };
      }
    }
  }
  // Reject caller-controlled fields carrying reserved `<!-- gc:` marker
  // syntax (codex cycle-2 security finding; same shape as runPostDecisionRecord).
  const callerStringFields = [
    ["plainEnglishOutcome", rest.plainEnglishOutcome],
    ["summary", rest.summary],
    ["planCommentUrl", rest.planCommentUrl],
  ];
  if (rest.traceability && typeof rest.traceability === "object") {
    callerStringFields.push(["traceability.notes", rest.traceability.notes]);
  }
  for (const [k, v] of callerStringFields) {
    const err = rejectReservedMarkerSequence(v, k);
    if (err) {
      return {
        ok: false,
        error: "final_report_reserved_marker",
        message: err,
        issue_number: rest.issueNumber,
        next_action: "remove_reserved_marker_prefix_and_retry",
      };
    }
  }
  if (Array.isArray(rest.requirements)) {
    for (let i = 0; i < rest.requirements.length; i++) {
      const r = rest.requirements[i];
      if (!r || typeof r !== "object") continue;
      for (const [k, v] of [["uid", r.uid], ["title", r.title], ["status", r.status], ["note", r.note]]) {
        const err = rejectReservedMarkerSequence(v, `requirements[${i}].${k}`);
        if (err) return { ok: false, error: "final_report_reserved_marker", message: err, issue_number: rest.issueNumber, next_action: "remove_reserved_marker_prefix_and_retry" };
      }
    }
  }
  if (Array.isArray(rest.reviews)) {
    for (let i = 0; i < rest.reviews.length; i++) {
      const r = rest.reviews[i];
      if (!r || typeof r !== "object") continue;
      for (const [k, v] of [["reviewer", r.reviewer], ["summary", r.summary]]) {
        const err = rejectReservedMarkerSequence(v, `reviews[${i}].${k}`);
        if (err) return { ok: false, error: "final_report_reserved_marker", message: err, issue_number: rest.issueNumber, next_action: "remove_reserved_marker_prefix_and_retry" };
      }
    }
  }
  if (rest.files && typeof rest.files === "object") {
    for (const kind of Object.keys(rest.files)) {
      const arr = Array.isArray(rest.files[kind]) ? rest.files[kind] : [];
      for (let i = 0; i < arr.length; i++) {
        const err = rejectReservedMarkerSequence(arr[i], `files.${kind}[${i}]`);
        if (err) return { ok: false, error: "final_report_reserved_marker", message: err, issue_number: rest.issueNumber, next_action: "remove_reserved_marker_prefix_and_retry" };
      }
    }
  }
  if (rest.traceability && typeof rest.traceability === "object") {
    for (const k of ["added", "updated", "deleted"]) {
      const arr = Array.isArray(rest.traceability[k]) ? rest.traceability[k] : [];
      for (let i = 0; i < arr.length; i++) {
        const err = rejectReservedMarkerSequence(arr[i], `traceability.${k}[${i}]`);
        if (err) return { ok: false, error: "final_report_reserved_marker", message: err, issue_number: rest.issueNumber, next_action: "remove_reserved_marker_prefix_and_retry" };
      }
    }
  }
  // Cheap in-memory checks BEFORE any network I/O — same rationale as in
  // runPostDecisionRecord (codex cycle-2 F3).
  const body = buildFinalReport(rest);
  const nonActionError = detectDeferralDisposition(body);
  if (nonActionError) {
    return {
      ok: false,
      error: "final_report_unresolved_work_excuse",
      message: nonActionError,
      issue_number: rest.issueNumber,
      next_action: "fix_and_verify_the_real_problem_then_retry",
    };
  }
  const sensitiveError = detectSensitiveBodyContent(body);
  if (sensitiveError) {
    return {
      ok: false,
      error: "final_report_body_rejected",
      message: sensitiveError,
      issue_number: rest.issueNumber,
      next_action: "scrub_secrets_and_retry",
    };
  }
  if (Buffer.byteLength(body, "utf8") > GITHUB_ISSUE_COMMENT_BODY_MAX) {
    return {
      ok: false,
      error: "final_report_body_too_large",
      message: `rendered body is ${Buffer.byteLength(body, "utf8")} bytes; GitHub's issue-comment body cap is ${GITHUB_ISSUE_COMMENT_BODY_MAX} bytes`,
      issue_number: rest.issueNumber,
      next_action: "trim_summary_or_reviews_and_retry",
    };
  }
  const repoRoot = await ensureGitRepo(repoPath);
  const { owner, name } = await getOwnerRepo(repoRoot);
  // The traceability-reconciliation prerequisite (former issue #1058) is retired
  // with the backend (issue #1500): reconciliation is no longer a workflow phase,
  // so there is no `traceability_reconciled` marker to require. The report's real
  // gates — CI green, Sonar pass-or-legit-skipped, mandatory Codex review, and the
  // sensitive/defer/reserved-marker scrubs above — remain the bar for a "PR ready"
  // record. The agent records requirement status and traceability directly in the
  // requirement files, reviewed in the PR.
  let apiResponse = null;
  try {
    const { stdout } = await execFile(
      "gh",
      [
        "api",
        "--method",
        "POST",
        `/repos/${owner}/${name}/issues/${rest.issueNumber}/comments`,
        "-f",
        `body=${body}`,
      ],
      { cwd: repoRoot },
    );
    try {
      apiResponse = JSON.parse(stdout);
    } catch {
      apiResponse = null;
    }
  } catch (error) {
    return {
      ok: false,
      error: "final_report_post_failed",
      message: extractGhErrorMessage(error),
      issue_number: rest.issueNumber,
      next_action: "retry_after_resolving_gh_failure",
    };
  }
  return {
    repo_path: repoRoot,
    issue_number: rest.issueNumber,
    pr_number: rest.prNumber,
    ok: true,
    comment_url: apiResponse && typeof apiResponse.html_url === "string" ? apiResponse.html_url : null,
    comment_id: apiResponse && Number.isInteger(apiResponse.id) ? apiResponse.id : null,
  };
}

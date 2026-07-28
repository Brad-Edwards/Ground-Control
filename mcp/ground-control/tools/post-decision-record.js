// Split from index.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Registration bodies are unchanged.

import { z } from "zod";
import {
  DECISION_RECORD_CLASSIFICATIONS,
  DECISION_RECORD_DECISIONS,
  DECISION_RECORD_REVIEWERS,
  EXACT_REQUIREMENT_UID_RE,
  FINAL_REPORT_PLAIN_ENGLISH_OUTCOME_MAX,
  FINAL_REPORT_REVIEW_SUMMARY_MAX,
  FINAL_REPORT_SUMMARY_MAX,
  PR_BODY_CHANGE_CLASSES,
  PR_BODY_SUMMARY_MAX,
  TELEMETRY_OUTCOMES,
  TELEMETRY_TIERS,
  classifyChangedSurface,
  runAssertCompletion,
  runCodexReviewCycle,
  runGetIssueThread,
  runLogStepTelemetry,
  runPostDecisionRecord,
  runPostFinalReport,
  runRenderPrBody,
  runTestQualityReviewCycle,
  runWatchCiRun,
  startAsyncJob,
} from "../lib.js";
import { ASYNC_REVIEW_PARAM_DESC } from "./query.js";
import { ok, err } from "./respond.js";


export function registerPostDecisionRecord(server, ctx) {
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
    "gc_post_final_report",
    "Post the canonical /implement Step 19 final report (or the /quickfix Step Q19 slim close comment) as a comment on the GitHub issue. Renders structured input (plain_english_outcome, in-scope requirements, files-by-change-kind, reviews, traceability reconciliation, CI/SonarCloud status) into the standard final-report Markdown layout. `plain_english_outcome` is required for /implement and renders the short product/operator outcome section; lane='quickfix' keeps the slim payload where AI reviews and the outcome field are optional. Every gate (CI green, Sonar pass-or-legit-skipped, sensitive-content / no-defer / reserved-marker scrubs) still applies. Replaces free-prose Step 19 comments. Returns the posted comment's URL and id. A GitHub update gives exactly what's needed — not more, not less. No restating context the reader already has, no padding sections, no hedging prose.",
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
      plain_english_outcome: z.string().min(1).max(FINAL_REPORT_PLAIN_ENGLISH_OUTCOME_MAX).optional(),
      lane: z.enum(["implement", "quickfix"]).optional(),
      documentation_outcome: z.object({
        outcome: z.enum(["updated", "verified_unchanged", "not_updated_authorized"]),
        rationale: z.string().optional(),
      }).optional(),
      override_traceability_gate: z.boolean().optional(),
      override_traceability_reason: z.string().optional(),
    },
    async ({ repo_path, issue_number, pr_number, requirements, files, reviews, traceability, ci_status, sonar_status, plan_comment_url, summary, plain_english_outcome, lane, documentation_outcome, override_traceability_gate, override_traceability_reason }) => {
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
          plainEnglishOutcome: plain_english_outcome ?? null,
          documentation_outcome: documentation_outcome ?? null,
          overrideTraceabilityGate: Boolean(override_traceability_gate),
          overrideTraceabilityReason: override_traceability_reason ?? null,
        }), null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_assert_completion",
    "Run the completion assertions (traceability reconciliation) then post the final report in one deterministic call. " +
    "phase='post_merge' (default) is the Phase E completion: it is MERGE-GATED — it refuses with error='completion_pr_not_merged' unless the linked PR is merged (merged_at non-null AND state='MERGED'), so the ACTIVE transition, IMPLEMENTS/TESTS links, and the durable final report never land ahead of shipped code (issue #963, mirrors gc_close_issue_after_merge). " +
    "phase='pre_merge' is the Phase D terminal readiness record: it skips the traceability assertion and the merge gate, posts a 'Ready for review' comment carrying a `ready_for_review` phase marker (no `gc:final-report` marker), and returns {ok, phase:'pre_merge', readiness_report}; all input gates (CI green, Sonar pass/skip, codex review present, scrubs) still run. " +
    "Composes gc_assert_traceability_reconciled and gc_post_final_report. " +
    "When `project` is omitted, traceability reconciliation infers it from `repo_path`'s `.ground-control.yaml`; an explicit `project` overrides the config. " +
    "Fail-fast: validates the final-report input before any side effects. " +
    "Returns assertions[] (one entry per assertion: {name, ok, comment_url, comment_id}) plus final_report {comment_url, comment_id}. " +
    "Gates inherited from the composed runners: traceability reconciliation (ACTIVE requirements must have IMPLEMENTS links + TESTS links on executable surfaces), " +
    "CI green, Sonar pass-or-skipped, sensitive-content/no-defer/reserved-marker scrubs, reviews present, body size. " +
    "The in-progress label removal is optional best-effort and is NOT a gate here. " +
    "Do NOT remove or call the individual gc_assert_traceability_reconciled / gc_post_final_report tools separately when using this composite tool.",
    {
      repo_path: z.string(),
      issue_number: z.number().int().positive(),
      pr_number: z.number().int().positive(),
      requirements: z.array(z.object({
        uid: z.string().regex(EXACT_REQUIREMENT_UID_RE),
        title: z.string().min(1),
        status: z.enum(["ACTIVE", "DRAFT", "DEPRECATED", "ARCHIVED"]),
        note: z.string().optional(),
      })).default([]),
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
      plain_english_outcome: z.string().min(1).max(FINAL_REPORT_PLAIN_ENGLISH_OUTCOME_MAX).optional(),
      documentation_outcome: z.object({
        outcome: z.enum(["updated", "verified_unchanged", "not_updated_authorized"]),
        rationale: z.string().optional(),
      }).optional(),
      touched_files: z.array(z.string()).optional(),
      project: z.string().optional(),
      override: z.boolean().optional(),
      override_reason: z.string().optional(),
      phase: z.enum(["pre_merge", "post_merge"]).optional(),
    },
    async ({ repo_path, issue_number, pr_number, requirements, files, reviews, traceability, ci_status, sonar_status, plan_comment_url, summary, plain_english_outcome, documentation_outcome, touched_files, project, override, override_reason, phase }) => {
      try {
        return ok(JSON.stringify(await runAssertCompletion({
          repoPath: repo_path,
          issueNumber: issue_number,
          prNumber: pr_number,
          requirements: requirements.map((r) => ({
            uid: r.uid,
            title: r.title,
            status: r.status,
            note: r.note ?? undefined,
            statusIntent: r.status,
          })),
          files: files ?? {},
          reviews,
          traceability: traceability ?? {},
          ciStatus: ci_status,
          sonarStatus: sonar_status,
          planCommentUrl: plan_comment_url ?? null,
          summary: summary ?? null,
          plainEnglishOutcome: plain_english_outcome ?? null,
          documentation_outcome: documentation_outcome ?? null,
          touchedFiles: touched_files ?? [],
          project: project ?? null,
          override: Boolean(override),
          overrideReason: override_reason ?? null,
          phase: phase ?? "post_merge",
        }), null, 2));
      } catch (e) { return err(e); }
    },
  );

  server.tool(
    "gc_render_pr_body",
    "Render a PR body that satisfies the Ground Control policy gates (template sections, requirement UIDs, ADR impact, three Ground Control Checks, IMPLEMENTS/TESTS markers, no defer language). Returns the rendered body string for the caller to pass to `gh pr create --body`. change_class shapes a few cells: doc-only marks integration tests / changelog fragment N/A; source requires changelog fragment; source+migration adds the MigrationSmokeTest reminder. In `release-please` changelog_mode no per-PR changelog fragment is required or accepted (Release Please owns CHANGELOG.md, #1399). Pass dev_start_gate when the repo's configured PR policy requires a ## Dev-Start Gate section. A GitHub update gives exactly what's needed — not more, not less. No restating context the reader already has, no padding sections, no hedging prose.",
    {
      repo_path: z.string(),
      issue_number: z.number().int().positive(),
      change_class: z.enum(PR_BODY_CHANGE_CLASSES),
      // Renderer input uses the anchored recognizer, not the looser identity
      // contract, so every UID this tool renders is one the `pr-requirement-uid`
      // policy gate can find in the emitted body (issue #1425). Each array
      // element must BE a UID, not contain one.
      requirement_uids: z.array(z.string().regex(EXACT_REQUIREMENT_UID_RE)),
      adr_refs: z.array(z.string().min(1)),
      summary: z.string().min(1).max(PR_BODY_SUMMARY_MAX),
      changes: z.array(z.string().min(1)),
      traceability: z.object({
        implements: z.array(z.string()),
        tests: z.array(z.string()),
      }),
      changelog_fragment: z.string().optional(),
      changelog_mode: z.enum(["fragments", "release-please"]).optional(),
      test_notes: z.string().optional(),
      dev_start_gate: z.string().optional(),
      documentation_outcome: z.object({
        outcome: z.enum(["updated", "verified_unchanged", "not_updated_authorized"]),
        rationale: z.string().optional(),
      }).optional(),
    },
    async ({ repo_path, issue_number, change_class, requirement_uids, adr_refs, summary, changes, traceability, changelog_fragment, changelog_mode, test_notes, dev_start_gate, documentation_outcome }) => {
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
          changelogMode: changelog_mode ?? "fragments",
          testNotes: test_notes ?? null,
          devStartGate: dev_start_gate ?? null,
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
    "Pre-push codex-review cycle wrapper. Runs gc_codex_review (uncommitted=true) AND auto-posts the canonical per-cycle decision record (every finding gets decision='fix' with auto-rationale, the only decision the cycle tool can record without user authorization). Returns a compact envelope: {ok, reviewer, cycle, cap, status, next_action, findings_summary, findings_record_url, decision_record_url, diff_mode, review_coverage}. `diff_mode` is 'inline' when the complete diff fit one prompt and 'manifest' when it did not; `review_coverage` reports how much of it was reviewed {strategy, chunks_total, chunks_completed, files_total, files_covered, complete}. An over-cap diff is reviewed as bounded server-supplied slices inside this SAME logical cycle — slices never count as cycles (issue #1414). A cycle whose slices did not all return a valid reviewer envelope comes back ok=false, status='post_failed', error='review_coverage_incomplete' with no durable record written and no cycle consumed. Verbatim review prose and per-finding bodies stay server-side via the underlying review's findings record — they never reach the agent through this tool. The subagent that drives the loop calls this tool once per cycle; on next_action='fix_findings_and_reinvoke' it fixes, self-verifies locally, re-stages, and re-invokes. wontfix / not-applicable decisions still require an explicit gc_post_decision_record call after user authorization.",
    {
      repo_path: z.string(),
      issue_number: z.number().int().positive(),
      base_branch: z.string().nullable().optional(),
      uncommitted: z.boolean().optional(),
      override_cap: z.boolean().optional(),
      override_reason: z.string().nullable().optional(),
      auto_grant: z.boolean().optional(),
      async: z.boolean().optional().describe(ASYNC_REVIEW_PARAM_DESC),
    },
    async ({ repo_path, issue_number, base_branch, uncommitted, override_cap, override_reason, auto_grant, async: asyncMode }) => {
      try {
        const params = {
          repoPath: repo_path,
          issueNumber: issue_number,
          baseBranch: base_branch ?? null,
          uncommitted: uncommitted ?? true,
          overrideCap: Boolean(override_cap),
          overrideReason: override_reason ?? null,
          autoGrant: Boolean(auto_grant),
        };
        if (asyncMode) {
          return ok(JSON.stringify(startAsyncJob(
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
      auto_grant: z.boolean().optional(),
      model: z.string().optional(),
      async: z.boolean().optional().describe(ASYNC_REVIEW_PARAM_DESC),
    },
    async ({ repo_path, issue_number, base_branch, override_cap, override_reason, auto_grant, model, async: asyncMode }) => {
      try {
        const params = {
          repoPath: repo_path,
          issueNumber: issue_number,
          baseBranch: base_branch ?? null,
          overrideCap: Boolean(override_cap),
          overrideReason: override_reason ?? null,
          autoGrant: Boolean(auto_grant),
          model,
        };
        if (asyncMode) {
          return ok(JSON.stringify(startAsyncJob(
            "test_quality_review_cycle",
            (signal) => runTestQualityReviewCycle({ ...params, signal }),
          ), null, 2));
        }
        return ok(JSON.stringify(await runTestQualityReviewCycle(params), null, 2));
      } catch (e) { return err(e); }
    },
  );
}

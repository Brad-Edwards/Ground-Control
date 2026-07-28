// Split from index.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Registration bodies are unchanged.

import { z } from "zod";
import {
  ARTIFACT_TYPES,
  CODEX_REVIEW_HARD_CAP,
  CODEX_REVIEW_PREPUSH_HARD_CAP,
  EXACT_REQUIREMENT_UID_RE,
  KNOWLEDGE_SOURCE_TYPES,
  LINK_TYPES,
  STATUSES,
  TEST_QUALITY_REVIEW_HARD_CAP,
  buildCodexReviewOverrideCapDescription,
  buildCodexReviewOverrideReasonDescription,
  buildCodexReviewToolDescription,
  bulkTransitionStatus,
  createGitHubIssueFromRequirement,
  createTraceabilityLink,
  deleteTraceabilityLink,
  getDashboardStats,
  getRepoGroundControlContext,
  getRequirementByUid,
  getTraceabilityByArtifact,
  getTraceabilityLinks,
  pick,
  runAssertQualityGates,
  runAssertTraceabilityReconciled,
  runCloseIssueAfterMerge,
  runCodexArchitecturePreflight,
  runCodexReview,
  runPostImplementationPlan,
  runTestQualityReview,
  startReviewJob,
  transitionStatus,
  writeKnowledgeInbox,
} from "../lib.js";
import {
  GC_QUERY_BODY_BYTE_CAP,
  GC_QUERY_PATH_ALLOWLIST,
  GC_QUERY_PATH_DENYLIST,
  GC_QUERY_TIMEOUT_MS,
  gcQuerySchema,
  gcQueryToolHandler,
} from "../gc-query.js";
import { ok, err } from "./respond.js";

export const ASYNC_REVIEW_PARAM_DESC =
  "When true, start the review/preflight as a background job and return " +
  "{ok,status:'running',job_id} immediately instead of blocking the MCP call. " +
  "Poll the job with gc_codex_job (action='poll') until status='done', then dispatch " +
  "on result.next_action exactly as for the synchronous call. Use this in the /implement " +
  "workflow so a multi-minute review never trips the MCP client's tool-call timeout (issue #937).";

export const CODEX_REVIEW_CAPS = { postPushCap: CODEX_REVIEW_HARD_CAP, prepushCap: CODEX_REVIEW_PREPUSH_HARD_CAP };


export function registerQuery(server, ctx) {
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
      // Same bounded structured-UID contract as every other curated tool that
      // takes a requirement UID (issue #1425); a bare z.string() here is how the
      // direct read and the completion tools drifted apart.
      uid: z.string().regex(EXACT_REQUIREMENT_UID_RE).describe("Requirement UID"),
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
    "Reverse lookup: find all traceability links for an artifact (file path, issue number, etc.). The backend always scopes the lookup to one project: pass `project` to disambiguate. A single-project backend resolves it automatically; a multi-project backend rejects an absent project with project_required rather than returning another project's links (avoids cross-project issue-number collisions).",
    {
      artifact_type: z.enum(ARTIFACT_TYPES),
      artifact_identifier: z.string(),
      project: z.string().optional(),
    },
    async ({ artifact_type, artifact_identifier, project }) => {
      try { return ok(JSON.stringify(await getTraceabilityByArtifact(artifact_type, artifact_identifier, project), null, 2)); }
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
    "Transition a requirement's status. Valid: DRAFT->ACTIVE, DRAFT->DEPRECATED (withdraw work that was never implemented), ACTIVE->DEPRECATED, ACTIVE->ARCHIVED, DEPRECATED->ARCHIVED.",
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
    "Create a GitHub issue from a requirement and auto-link it back. Required for /implement's UID-first path. The title and body are rendered from the requirement (the body seeds the `## Requirements` section /implement parses); `extra_body` is appended. Auto-link uses IMPLEMENTS for ACTIVE requirements and DOCUMENTS otherwise. If the issue is created but the traceability link fails, the result still returns the issue plus a `traceability_error`.",
    {
      uid: z.string(),
      project: z.string().optional(),
      repo_path: z.string().describe("Absolute path to the target Git repository; its origin remote is the authoritative repository identity (GC-P026)"),
      repo: z.string().regex(/^[a-zA-Z0-9][a-zA-Z0-9._-]*\/[a-zA-Z0-9][a-zA-Z0-9._-]*$/).optional().describe("Optional owner/repo assertion; validated against the checkout remote and rejected on mismatch, never used as an alternate destination"),
      labels: z.array(z.string()).optional(),
      extra_body: z.string().optional(),
    },
    async (args) => {
      try {
        return ok(JSON.stringify(await createGitHubIssueFromRequirement({
          uid: args.uid,
          project: args.project,
          repo: args.repo,
          repoRoot: args.repo_path,
          labels: args.labels,
          extraBody: args.extra_body,
        }), null, 2));
      } catch (e) { return err(e); }
    },
  );

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
    "Post the implementation plan as a comment on the GitHub issue. Refuses unless a 'preflight' phase marker exists for the issue. Scrubs sensitive content, rejects forged machine blocks / reserved markers, and caps body size. On success writes a 'plan' phase marker.",
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
    "Assert that traceability reconciliation has landed for the issue and post a 'traceability_reconciled' phase marker. Re-fetches each in-scope requirement (status_intent: ACTIVE or DRAFT) and its links from the Ground Control REST API and refuses unless every ACTIVE requirement has an IMPLEMENTS link AND, when the IMPLEMENTS link points at an executable surface (backend/src/main/**, frontend/src/**, mcp/**, tools/policy/**), at least one TESTS link. DRAFT requirements are TESTS-exempt. Empty requirements[] runs the orphaned-link audit instead. When `project` is omitted, it is inferred from `repo_path`'s `.ground-control.yaml`; an explicit `project` overrides the config. Downstream: gc_post_final_report refuses unless this marker exists for the issue. override=true + override_reason allows the user to authorize a skip with a quoted rationale.",
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
    "Canonical post-merge close path for the /implement workflow's Phase E (Step 20). Verifies the issue's linked PR is merged (merged_at non-null AND state=MERGED) before running `gh issue close`; refuses otherwise. Idempotent — re-running on an already-closed issue returns ok with already_closed=true. The PR body's `Closes #<n>` keyword remains the GitHub cross-link for sidebar / timeline purposes, but this tool is the gate-enforcing close path. pr_number is optional; when omitted the tool resolves the merged PR for the issue via the GitHub timeline. This tool performs ONLY linked-PR resolution, merge-state verification, and idempotent issue closure — it does not list open issues, rank next-work candidates, or return any recommendation field (ADR-089 §5).",
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
      `\`claude\` CLI (Sonnet 5 by default) with the review-tests rubric and the ` +
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
}

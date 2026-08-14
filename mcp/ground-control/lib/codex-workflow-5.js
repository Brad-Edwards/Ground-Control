// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { realpathSync } from "node:fs";
import { TEST_QUALITY_REVIEW_DEFAULT_MODEL, TEST_QUALITY_REVIEW_TIMEOUT_MS } from "./ci-watcher.js";
import { assertImplementSyncCheckout, fetchImplementBase, isImplementAncestor, readImplementGitOid, readImplementTreeOid, readRemoteImplementBranchSha } from "./codex-workflow-2.js";
import { validateExistingSynchronizedImplementPr, validateImplementBranchName, validateImplementPrTitle } from "./codex-workflow.js";
import { detectSensitiveBodyContent, extractGhErrorMessage } from "./grc-legacy-compat-2.js";
import { assertSafeImplementCheckoutConfiguration, authorizeImplementRepoRoot, ensureGitRepo, resolveMcpLaunchWorkspaceAuthorization } from "./grc-legacy-compat-4.js";
import { readTrustedImplementSyncRecord } from "./knowledge-capture.js";
import { getRepoGroundControlContext } from "./repo-vocabulary-2.js";
import { rejectReservedMarkerSequence } from "./repo-vocabulary.js";
import { checkPrBodyShape, execFile, execFileWithInput, reviewEngineEnv } from "./runtime-primitives.js";
import { TEST_QUALITY_REVIEW_FINDINGS_SCHEMA } from "./test-quality-prompt.js";
import { ReviewerCapConfigError } from "./test-quality-runner.js";

export async function runCreateSynchronizedImplementPr(input, {
  workspaceAuthorizationResolver = resolveMcpLaunchWorkspaceAuthorization,
  commandRunner = execFile,
  contextResolver = getRepoGroundControlContext,
  syncRecordReader = readTrustedImplementSyncRecord,
} = {}) {
  if (
    input == null
    || !Number.isInteger(input.issueNumber)
    || input.issueNumber <= 0
    || typeof input.recordId !== "string"
    || !/^[0-9a-f]{32}$/.test(input.recordId)
  ) {
    return {
      ok: false,
      error: "implement_pr_input_invalid",
      message: "issueNumber and a synchronization record ID are required",
    };
  }
  const branchValidation = validateImplementBranchName(input.branchName, input.issueNumber);
  if (!branchValidation.ok) return branchValidation;
  if (typeof input.body !== "string" || !checkPrBodyShape(input.body).ok) {
    return {
      ok: false,
      error: "implement_pr_body_invalid",
      message: "body must satisfy the canonical Ground Control PR-body shape",
    };
  }
  const sensitiveError = detectSensitiveBodyContent(input.body);
  if (sensitiveError) {
    return { ok: false, error: "implement_pr_body_rejected", message: sensitiveError };
  }
  const reservedError = rejectReservedMarkerSequence(input.body, "body");
  if (reservedError) {
    return { ok: false, error: "implement_pr_body_rejected", message: reservedError };
  }
  let repoRoot;
  let context;
  try {
    repoRoot = realpathSync(await ensureGitRepo(input.repoPath));
    context = await contextResolver(repoRoot);
  } catch (error) {
    return { ok: false, error: "implement_pr_context_failed", message: error.message };
  }
  if (context?.status !== "ok") {
    return {
      ok: false,
      error: "implement_pr_context_invalid",
      message: "The repository Ground Control context is invalid",
      next_action: "repair_ground_control_configuration_and_retry",
    };
  }
  const repoAuthorization = await authorizeImplementRepoRoot(
    repoRoot,
    workspaceAuthorizationResolver,
  );
  if (!repoAuthorization.ok) return repoAuthorization;
  const baseBranch = context?.workflow?.base_branch ?? "dev";
  const titleValidation = validateImplementPrTitle(input.title, context?.workflow?.pr_title);
  if (!titleValidation.ok) {
    return {
      ok: false,
      error: "implement_pr_title_invalid",
      message: titleValidation.message,
      next_action: "reshape_the_title_and_retry",
    };
  }
  try {
    await assertSafeImplementCheckoutConfiguration(repoRoot);
    const checkout = await assertImplementSyncCheckout({
      repoRoot,
      issueNumber: input.issueNumber,
      branchName: input.branchName,
      commandRunner,
    });
    if (!checkout.ok) return checkout;
    const trusted = await syncRecordReader(
      repoRoot,
      repoAuthorization.owner,
      repoAuthorization.name,
      input.issueNumber,
      input.recordId,
    );
    if (!trusted.ok) {
      return { ...trusted, next_action: "return_to_the_synchronization_boundary" };
    }
    const record = trusted.record;
    if (
      record.issueNumber !== input.issueNumber
      || record.branchName !== input.branchName
      || record.baseBranch !== baseBranch
      || record.remoteRef !== `refs/remotes/origin/${baseBranch}`
    ) {
      return {
        ok: false,
        error: "implement_pr_sync_record_identity_mismatch",
        message: "The synchronization record does not belong to this issue, branch, or configured base",
        next_action: "return_to_the_synchronization_boundary",
      };
    }
    const { fetchedBaseSha } = await fetchImplementBase(repoRoot, baseBranch, commandRunner);
    const localSha = await readImplementGitOid(repoRoot, "HEAD", commandRunner);
    const localTreeSha = await readImplementTreeOid(repoRoot, "HEAD", commandRunner);
    const remoteSha = await readRemoteImplementBranchSha(repoRoot, input.branchName, commandRunner);
    if (
      fetchedBaseSha !== record.fetchedBaseSha
      || localSha !== record.resultingFeatureSha
      || localTreeSha !== record.verifiedTreeSha
      || remoteSha !== record.resultingFeatureSha
      || !await isImplementAncestor(
        repoRoot,
        record.fetchedBaseSha,
        record.resultingFeatureSha,
        commandRunner,
      )
    ) {
      return {
        ok: false,
        error: "implement_pr_sync_stale",
        message: "The base or feature branch changed after synchronization",
        next_action: "return_to_the_synchronization_boundary",
      };
    }
    const repoSlug = `${repoAuthorization.owner}/${repoAuthorization.name}`;
    try {
      const { stdout } = await commandRunner(
        "gh",
        [
          "pr", "list",
          "--repo", repoSlug,
          "--state", "open",
          // `gh pr list --head` accepts the branch name for a same-repository
          // PR; owner:branch produces an empty result and makes the later
          // create attempt collide with the existing PR. Repository identity
          // remains pinned by --repo and by the candidate validation below.
          "--head", input.branchName,
          "--json",
          "number,url,baseRefName,headRefName,headRefOid,headRepository,headRepositoryOwner,isCrossRepository,title,body",
          "--limit", "2",
        ],
        { cwd: repoRoot },
      );
      const existing = JSON.parse(stdout);
      if (!Array.isArray(existing) || existing.length > 1) {
        return {
          ok: false,
          error: "implement_pr_existing_ambiguous",
          message: "The synchronized feature branch must have at most one open pull request",
          next_action: "inspect_the_existing_prs_without_bypassing_the_sync_gate",
        };
      }
      if (existing.length === 1) {
        const validation = validateExistingSynchronizedImplementPr(existing[0], {
          owner: repoAuthorization.owner,
          name: repoAuthorization.name,
          baseBranch,
          branchName: input.branchName,
          featureSha: localSha,
          title: input.title,
          body: input.body,
        });
        if (!validation.ok) return validation;
        return {
          ok: true,
          already_exists: true,
          pr_number: existing[0].number,
          pr_url: existing[0].url,
          synchronization_record_id: record.recordId,
        };
      }
    } catch (error) {
      return {
        ok: false,
        error: "implement_pr_existing_lookup_failed",
        message: extractGhErrorMessage(error),
        next_action: "repair_the_repository_scoped_pr_lookup_and_retry",
      };
    }
    const { stdout } = await commandRunner(
      "gh",
      [
        "pr", "create",
        "--repo", repoSlug,
        "--base", baseBranch,
        "--head", input.branchName,
        "--title", input.title,
        "--body", input.body,
      ],
      { cwd: repoRoot },
    );
    const prUrl = stdout.trim();
    const expectedUrlPrefix =
      `https://github.com/${repoAuthorization.owner}/${repoAuthorization.name}/pull/`.toLowerCase();
    if (!prUrl.toLowerCase().startsWith(expectedUrlPrefix)) {
      return {
        ok: false,
        error: "implement_pr_created_repository_mismatch",
        message: "GitHub returned a PR outside the authorized repository",
        next_action: "inspect_the_repository_scoped_pr_write",
      };
    }
    const numberMatch = /\/pull\/(\d+)(?:\D|$)/.exec(prUrl);
    return {
      ok: true,
      already_exists: false,
      pr_number: numberMatch == null ? null : Number.parseInt(numberMatch[1], 10),
      pr_url: prUrl,
      synchronization_record_id: record.recordId,
      fetched_base_sha: fetchedBaseSha,
      feature_sha: localSha,
    };
  } catch (error) {
    return {
      ok: false,
      error: "implement_pr_create_failed",
      message: extractGhErrorMessage(error),
      next_action: "repair_the_refused_condition_and_retry_without_bypassing_the_sync_gate",
    };
  }
}
export async function resolveReviewerPrePushCap(repoPath, blockName, moduleDefault) {
  let ctx;
  try {
    ctx = await getRepoGroundControlContext(repoPath);
  } catch {
    // Hard IO / fs error reading the file — soft-fall back. This branch
    // covers cases like the repo path going away mid-run; it does NOT cover
    // schema validation failures, which surface as a structured `status:
    // "invalid_ground_control_yaml"` return rather than a thrown error.
    return moduleDefault;
  }
  // Legitimate absence — no cfg file or schema-clean cfg with no override
  // for this block / key. Use the module default.
  if (!ctx || ctx.status === "missing_ground_control_yaml") return moduleDefault;
  // Cfg is present but failed schema validation. The validator in
  // normalizeReviewerConfig rejects out-of-bounds / non-integer / unknown
  // keys; surfacing the error here preserves that strictness for the
  // resolver path. A silent fall-back would mask a mistyped knob.
  if (ctx.status === "invalid_ground_control_yaml") {
    throw new ReviewerCapConfigError(blockName, ctx.errors);
  }
  const block = ctx?.workflow?.[blockName];
  if (block && typeof block.pre_push_cap === "number" && Number.isInteger(block.pre_push_cap)) {
    return block.pre_push_cap;
  }
  return moduleDefault;
}
export async function runSingleClaudeTestQualityReview({
  repoRoot,
  prompt,
  model = TEST_QUALITY_REVIEW_DEFAULT_MODEL,
  schema = TEST_QUALITY_REVIEW_FINDINGS_SCHEMA,
  timeoutMs = TEST_QUALITY_REVIEW_TIMEOUT_MS,
  signal = undefined,
}) {
  const args = [
    "--print",
    "--model",
    model,
    "--output-format",
    "json",
    "--json-schema",
    JSON.stringify(schema),
    "--add-dir",
    repoRoot,
    "--permission-mode",
    "bypassPermissions",
    "--allowedTools",
    "Read Glob Grep",
  ];
  const childEnv = reviewEngineEnv();
  const { stdout } = await execFileWithInput("claude", args, {
    input: prompt,
    cwd: repoRoot,
    env: childEnv,
    maxBuffer: 10 * 1024 * 1024,
    timeoutMs,
    signal,
  });
  return stdout;
}

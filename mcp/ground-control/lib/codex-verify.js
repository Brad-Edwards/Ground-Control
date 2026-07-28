// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { getRequirementByUid, getTraceabilityLinks } from "./api-controls-3.js";
import { evaluateCodexVerifyCycleCap, postCodexVerifyCycleMarker, readPriorCodexVerifyCycleCount } from "./codex-verify-cap.js";
import { buildCodexArchitecturePreflightPrompt, getIssueContext } from "./codex-workflow-3.js";
import { buildCodexArchitectureExecArgs, findNewWorkingTreeChanges, readGeneratedCodexSummary } from "./codex-workflow.js";
import { getOwnerRepo, postPhaseMarker } from "./grc-legacy-compat-3.js";
import { enrichCommentsWithThreadIds, ensureGitRepo, fetchReviewCommentById } from "./grc-legacy-compat-4.js";
import { buildCodexVerifyPrompt, getRuntimeAllowedAuthors, parseCodexVerifyTail, postReviewCommentReply, resolveReviewThread } from "./issue-thread.js";
import { listWorkingTreeChanges } from "./knowledge-capture.js";
import { getRepoGroundControlContext } from "./repo-vocabulary-2.js";
import { DEFAULT_CODEX_TIMEOUT_MS, execFile, execFileWithInput, formatCommandFailure } from "./runtime-primitives.js";

export async function runCodexArchitecturePreflight({
  requirementUid,
  project,
  repoPath,
  issueNumber,
  repo,
  signal = undefined,
}) {
  // The /implement workflow supports two entry points: UID-first (a formal
  // Ground Control requirement) and issue-first (a requirement-free issue
  // for a bug, refactor, or maintenance run). Preflight must support both,
  // so at least one of the two anchors is required — an invocation with
  // neither has no subject to reason about.
  if (!requirementUid && issueNumber == null) {
    throw new Error(
      "gc_codex_architecture_preflight requires at least one of requirement_uid or issue_number",
    );
  }

  const repoRoot = await ensureGitRepo(repoPath);

  let requirement = null;
  let traceabilityLinks = [];
  if (requirementUid) {
    requirement = await getRequirementByUid(requirementUid, project);
    traceabilityLinks = await getTraceabilityLinks(requirement.id);
  }

  // `getIssueContext` is bound to `repoRoot` so `gh` resolves the target
  // repository from the checkout's git config even when `GH_REPO` is unset
  // and no explicit `repo` was supplied. This prevents the MCP server's own
  // working directory from leaking into the lookup.
  const issueContext = await getIssueContext(issueNumber, repo, { cwd: repoRoot });

  // Issue-first runs treat the GitHub issue as the authoritative contract.
  // If the issue body could not be loaded (wrong repo, missing scope, gh
  // CLI not authenticated, etc.), there is nothing for codex to reason about
  // and no way for the caller to catch silent drift. Fail fast with a
  // specific error that names the issue number and the underlying reason.
  //
  // Empty bodies are acceptable — GitHub returns `"body": ""` for valid
  // title-only issues (common shape for bugs and refactors), and the title
  // plus the diff still gives codex a usable context. Only fail when the
  // body field is missing entirely (lookup failure) or when getIssueContext
  // attached a `warning` field indicating the gh CLI call did not succeed.
  if (requirement == null) {
    const lookupFailed =
      !issueContext
      || issueContext.warning !== undefined
      || !("body" in issueContext);
    if (lookupFailed) {
      const detail = issueContext?.warning
        ?? (issueContext == null
          ? "getIssueContext returned null"
          : "issue context has no body field");
      throw new Error(
        `gc_codex_architecture_preflight: issue-only run requires a loadable GitHub issue but failed to fetch issue #${issueNumber}: ${detail}`,
      );
    }
  }

  const preexistingChangedFiles = await listWorkingTreeChanges(repoRoot);

  // Pass the repo's architecture.vocabulary (issue #931) so the preflight can
  // emit a "Design Vocabulary That Applies" section the pre-push reviewers
  // consume. Best-effort: a missing or invalid block does not block preflight.
  let preflightVocabulary = null;
  try {
    const cfg = await getRepoGroundControlContext(repoRoot);
    if (cfg.status === "ok" && cfg.architecture && cfg.architecture.vocabulary) {
      preflightVocabulary = cfg.architecture.vocabulary;
    }
  } catch {
    // best-effort
  }

  const tempDir = mkdtempSync(join(tmpdir(), "gc-codex-preflight-"));
  const outputPath = join(tempDir, "codex-last-message.txt");
  const prompt = buildCodexArchitecturePreflightPrompt({
    requirement,
    traceabilityLinks,
    issueContext,
    vocabulary: preflightVocabulary,
  });

  try {
    await execFileWithInput(
      "codex",
      buildCodexArchitectureExecArgs({ repoPath: repoRoot, outputPath }),
      {
        input: prompt,
        cwd: repoRoot,
        maxBuffer: 10 * 1024 * 1024,
        env: { ...process.env, NO_COLOR: "1" },
        timeoutMs: DEFAULT_CODEX_TIMEOUT_MS,
        signal,
      },
    );

    const summary = readGeneratedCodexSummary(outputPath);
    const changedFiles = findNewWorkingTreeChanges(preexistingChangedFiles, await listWorkingTreeChanges(repoRoot));

    // Record the `preflight` phase marker on the issue thread so downstream
    // tools (gc_post_implementation_plan etc.) can detect that preflight ran
    // before they let the workflow advance. Marker post is best-effort — a
    // failed post does not invalidate the preflight; the worst case is the
    // next gating tool sees no marker and refuses, prompting the agent to
    // re-run preflight (which is the correct fallback).
    let phaseMarker = null;
    if (issueNumber != null) {
      try {
        const { owner, name } = await getOwnerRepo(repoRoot);
        await postPhaseMarker(repoRoot, owner, name, issueNumber, "preflight");
        phaseMarker = { phase: "preflight", issue_number: issueNumber };
      } catch (markerError) {
        // eslint-disable-next-line no-console
        console.error(
          `[gc_codex_architecture_preflight] phase marker post failed for issue #${issueNumber}: ${markerError.message}`,
        );
      }
    }

    return {
      requirement_uid: requirementUid ?? null,
      issue_number: issueNumber ?? null,
      repo_path: repoRoot,
      preexisting_changed_files: preexistingChangedFiles,
      changed_files: changedFiles,
      summary,
      phase_marker: phaseMarker,
    };
  } catch (error) {
    throw new Error(`Codex architecture preflight failed: ${formatCommandFailure("codex", error)}`);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
}
export async function runCodexVerifyFinding({
  repoPath,
  prNumber,
  commentId,
  overrideCap = false,
  overrideReason = null,
}) {
  if (!Number.isInteger(prNumber) || prNumber <= 0) {
    throw new Error("pr_number must be a positive integer");
  }
  if (!Number.isInteger(commentId) || commentId <= 0) {
    throw new Error("comment_id must be a positive integer");
  }

  const repoRoot = await ensureGitRepo(repoPath);
  const { owner, name } = await getOwnerRepo(repoRoot);

  // Per-finding hard-cap-2 enforcement. Same template as the cycle cap but
  // keyed per (PR, comment_id). Refuses cycle 3+ unless overrideCap=true with
  // a non-empty reason.
  const priorVerifyCount = await readPriorCodexVerifyCycleCount(repoRoot, owner, name, prNumber, commentId);
  const verifyDecision = evaluateCodexVerifyCycleCap({
    priorCount: priorVerifyCount,
    prNumber,
    commentId,
    overrideCap,
    overrideReason,
  });
  if (!verifyDecision.ok) {
    return {
      repo_path: repoRoot,
      pr_number: prNumber,
      comment_id: commentId,
      ok: false,
      error: verifyDecision.error,
      message: verifyDecision.message,
      prior_cycles: verifyDecision.prior_cycles,
      cap: verifyDecision.cap,
      next_action: verifyDecision.next_action ?? null,
    };
  }

  const comment = await fetchReviewCommentById(repoRoot, owner, name, commentId);

  // Only accept comments authored by the allowlisted set or the PR author.
  const author = comment?.user?.login;
  const allowed = getRuntimeAllowedAuthors();
  // Also accept the PR author — in a local dev workflow gc_codex_review posts
  // via the user's gh auth, so the comments are authored by the user, not a bot.
  let prAuthorLogin = null;
  try {
    const { stdout } = await execFile(
      "gh",
      ["pr", "view", String(prNumber), "--json", "author"],
      { cwd: repoRoot },
    );
    prAuthorLogin = JSON.parse(stdout)?.author?.login || null;
  } catch {
    prAuthorLogin = null;
  }
  if (!author || (!allowed.has(author) && author !== prAuthorLogin)) {
    throw new Error(
      `Refusing to verify comment ${commentId}: author "${author}" is not in the allowlist and is not the PR author. ` +
        `Set GH_VERIFY_FINDING_AUTHORS to a comma-separated list of additional trusted logins if needed.`,
    );
  }

  // Path and line the finding is anchored to. Prefer the current-diff line
  // when present, fall back to the original commit position.
  const filePath = comment.path;
  const line = comment.line ?? comment.original_line ?? null;
  if (!filePath) {
    throw new Error(`Comment ${commentId} has no \`path\` field — not an inline review comment`);
  }

  let fileContents;
  try {
    fileContents = readFileSync(join(repoRoot, filePath), "utf8");
  } catch (error) {
    throw new Error(`Failed to read ${filePath} from the working tree: ${error.message}`);
  }

  // Resolve the REST comment id → GraphQL thread id before running codex,
  // because we'll need it for either the resolve or reply action.
  const threadMap = await enrichCommentsWithThreadIds({
    repoRoot,
    owner,
    name,
    prNumber,
    commentIds: [commentId],
  });
  const threadId = threadMap.get(commentId) || null;

  const prompt = buildCodexVerifyPrompt({
    findingBody: String(comment.body || ""),
    filePath,
    fileContents,
    line,
  });

  let stdout;
  try {
    ({ stdout } = await execFileWithInput(
      "codex",
      ["exec", "--sandbox", "read-only", "-C", repoRoot, "-"],
      {
        input: prompt,
        cwd: repoRoot,
        maxBuffer: 10 * 1024 * 1024,
        env: { ...process.env, NO_COLOR: "1" },
        timeoutMs: DEFAULT_CODEX_TIMEOUT_MS,
      },
    ));
  } catch (error) {
    throw new Error(`Codex verify failed: ${formatCommandFailure("codex", error)}`);
  }

  const parsed = parseCodexVerifyTail(stdout);

  // Record the verify cycle marker after a successful run (whether the
  // finding came back RESOLVED or UNRESOLVED — both are completed cycles).
  // Marker-post failures are non-fatal, same policy as the cycle marker.
  try {
    await postCodexVerifyCycleMarker(
      repoRoot,
      owner,
      name,
      prNumber,
      commentId,
      verifyDecision.nextCycle,
      { override: verifyDecision.override === true, overrideReason: verifyDecision.override_reason ?? null },
    );
  } catch (markerError) {
    // eslint-disable-next-line no-console
    console.error(
      `[gc_codex_verify_finding] cycle marker post failed for PR #${prNumber} comment #${commentId}: ${markerError.message}`,
    );
  }

  if (parsed.status === "resolved") {
    if (!threadId) {
      throw new Error(
        `Codex reported RESOLVED but no review thread was found for comment ${commentId}. Cannot mark the thread resolved.`,
      );
    }
    const resolved = await resolveReviewThread(repoRoot, threadId);
    return {
      repo_path: repoRoot,
      pr_number: prNumber,
      comment_id: commentId,
      thread_id: threadId,
      status: "resolved",
      thread_resolved: resolved,
      cycle: verifyDecision.nextCycle,
      cap: verifyDecision.cap,
      next_action: verifyDecision.next_action ?? null,
      override: verifyDecision.override === true,
      override_reason: verifyDecision.override_reason ?? null,
    };
  }

  // Unresolved — post the reply as a threaded reply on the original comment.
  const replyComment = await postReviewCommentReply(
    repoRoot,
    owner,
    name,
    prNumber,
    commentId,
    parsed.reply,
  );
  return {
    repo_path: repoRoot,
    pr_number: prNumber,
    comment_id: commentId,
    thread_id: threadId,
    status: "unresolved",
    reply_comment_id: replyComment.id,
    reply_body: parsed.reply,
    reply_html_url: replyComment.html_url || null,
    cycle: verifyDecision.nextCycle,
    cap: verifyDecision.cap,
    next_action: verifyDecision.next_action ?? null,
    override: verifyDecision.override === true,
    override_reason: verifyDecision.override_reason ?? null,
  };
}

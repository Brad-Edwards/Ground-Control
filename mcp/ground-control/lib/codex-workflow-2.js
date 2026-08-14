// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { realpathSync } from "node:fs";
import { isAbsolute } from "node:path";
import { GIT_OBJECT_ID_RE, IMPLEMENT_CHECKOUT_MODES, REQUIREMENT_UID_GATE_ENV_VAR, implementNetworkGitEnvironment, sanitizedImplementGitEnvironment, validateImplementBranchName } from "./codex-workflow.js";
import { extractGhErrorMessage } from "./grc-legacy-compat-2.js";
import { assertSafeImplementCheckoutConfiguration, authorizeImplementRepoRoot, ensureGitRepo, readGitIdentity, resolveMcpLaunchWorkspaceAuthorization } from "./grc-legacy-compat-4.js";
import { isSafeGitRefName, resolveWorkflowPrecommitCommand } from "./repo-context.js";
import { EXACT_REQUIREMENT_UID_RE, execFile, isRequirementUidToken } from "./runtime-primitives.js";
import { runVerifiedGateBoundary } from "./verification-gates.js";

export async function runPrepareImplementBranch({
  repoPath,
  invocationRoot,
  issueNumber,
  branchName,
  baseBranch = "dev",
  checkoutMode = "same_checkout",
}, {
  workspaceAuthorizationResolver = resolveMcpLaunchWorkspaceAuthorization,
} = {}) {
  if (!IMPLEMENT_CHECKOUT_MODES.includes(checkoutMode)) {
    return {
      ok: false,
      error: "implement_checkout_mode_unsupported",
      message: "Only checkout_mode='same_checkout' is supported; /implement cannot create or relocate into a worktree",
    };
  }
  if (typeof invocationRoot !== "string" || !isAbsolute(invocationRoot)) {
    return {
      ok: false,
      error: "implement_invocation_root_invalid",
      message: "invocationRoot must be an absolute path",
    };
  }
  const branchValidation = validateImplementBranchName(branchName, issueNumber);
  if (!branchValidation.ok) return branchValidation;
  if (!isSafeGitRefName(baseBranch)) {
    return {
      ok: false,
      error: "implement_base_branch_invalid",
      message: "baseBranch is not a safe Git ref name",
    };
  }

  let repoRoot;
  let pinnedRoot;
  let before;
  try {
    repoRoot = realpathSync(await ensureGitRepo(repoPath));
    pinnedRoot = realpathSync(invocationRoot);
    before = await readGitIdentity(repoRoot);
  } catch (error) {
    return {
      ok: false,
      error: "implement_checkout_identity_failed",
      message: `Unable to establish the invocation checkout identity: ${error.message}`,
    };
  }
  const repoAuthorization = await authorizeImplementRepoRoot(
    repoRoot,
    workspaceAuthorizationResolver,
  );
  if (!repoAuthorization.ok) return repoAuthorization;
  if (repoRoot !== pinnedRoot || before.topLevel !== pinnedRoot) {
    return {
      ok: false,
      error: "implement_invocation_root_mismatch",
      message: "The supplied repository is not the canonical checkout where /implement was invoked",
    };
  }
  try {
    await assertSafeImplementCheckoutConfiguration(repoRoot);
    await execFile(
      "gh",
      [
        "issue", "develop", String(issueNumber),
        "--checkout",
        "--base", baseBranch,
        "--name", branchName,
      ],
      { cwd: pinnedRoot, env: sanitizedImplementGitEnvironment() },
    );
  } catch (error) {
    return {
      ok: false,
      error: "implement_branch_prepare_failed",
      message: `Unable to create or switch the issue branch in the invocation checkout: ${extractGhErrorMessage(error)}`,
    };
  }

  let after;
  let activeBranch;
  try {
    after = await readGitIdentity(pinnedRoot);
    const branch = await execFile("git", ["-C", pinnedRoot, "branch", "--show-current"]);
    activeBranch = branch.stdout.trim();
  } catch (error) {
    return {
      ok: false,
      error: "implement_checkout_postcondition_failed",
      message: `Unable to verify the invocation checkout after branch preparation: ${error.message}`,
    };
  }
  if (
    after.topLevel !== pinnedRoot
    || after.gitDir !== before.gitDir
    || after.origin !== before.origin
  ) {
    return {
      ok: false,
      error: "implement_checkout_relocated",
      message: "Branch preparation changed the checkout root, Git directory, or origin; refusing to continue",
    };
  }
  const actualBranchValidation = validateImplementBranchName(activeBranch, issueNumber);
  if (!actualBranchValidation.ok) {
    return {
      ok: false,
      error: "implement_active_branch_noncompliant",
      message: actualBranchValidation.message,
      branch: activeBranch,
    };
  }
  return {
    ok: true,
    repo_path: pinnedRoot,
    invocation_root: pinnedRoot,
    checkout_mode: checkoutMode,
    branch: activeBranch,
  };
}
export function implementGateEnvironment(
  requestedRequirementUid,
  baseEnv = process.env,
) {
  if (requestedRequirementUid == null || requestedRequirementUid === "") {
    const { [REQUIREMENT_UID_GATE_ENV_VAR]: _ambientRequirementUid, ...cleanEnv } = baseEnv;
    return cleanEnv;
  }
  if (!EXACT_REQUIREMENT_UID_RE.test(requestedRequirementUid)) {
    const error = new Error(
      "The requested requirement UID is not a bounded requirement identifier",
    );
    error.code = "implement_requested_requirement_uid_invalid";
    throw error;
  }
  return { ...baseEnv, [REQUIREMENT_UID_GATE_ENV_VAR]: requestedRequirementUid };
}
// These rewrite the lazy `\s+(.+?)\s*$` that reads as super-linear backtracking
// (Sonar S8786), each exactly equivalent because heading titles are trimmed and
// bullet tokens split on whitespace. Heading uses an unquantified `\s` before
// `(.+)`, removing the quantifier-vs-quantifier ambiguity while still matching a
// whitespace-only title (`##␠␠`) as the original did, so section breaks are
// unchanged. Bullet keeps `\s+` (it must eat every space after the marker) and
// anchors the capture at the first non-space `\S`; since `\s+` already consumed
// the whitespace, that changes nothing behaviorally.
const REQUIREMENTS_HEADING_RE = /^(#{1,6})\s(.+)$/;
const REQUIREMENTS_BULLET_RE = /^\s*[-*+]\s+(\S.*)$/;

const UID_TOKEN_LEADING_WRAPPERS = "`[(";
const UID_TOKEN_TRAILING_WRAPPERS = "`)].:";

// Strip wrapping punctuation a UID token may carry in prose (backticks,
// brackets, parens, trailing sentence marks). A linear character scan; the
// equivalent `[...]+$` regex reads as super-linear to the analyzer (S8786).
function stripUidTokenWrappers(token) {
  let start = 0;
  let end = token.length;
  while (start < end && UID_TOKEN_LEADING_WRAPPERS.includes(token[start])) start += 1;
  while (end > start && UID_TOKEN_TRAILING_WRAPPERS.includes(token[end - 1])) end -= 1;
  return token.slice(start, end);
}

// Collect the lines under a level 2-4 `## Requirements` section, ending at the
// next heading of the same or higher level.
function requirementsSectionLines(issueBody) {
  const sectionLines = [];
  let sectionLevel = null;
  for (const line of issueBody.split(/\r?\n/)) {
    const heading = REQUIREMENTS_HEADING_RE.exec(line);
    if (!heading) {
      if (sectionLevel != null) sectionLines.push(line);
      continue;
    }
    const level = heading[1].length;
    const title = heading[2].trim().toLowerCase();
    if (sectionLevel == null) {
      if (level >= 2 && level <= 4 && title === "requirements") sectionLevel = level;
      continue;
    }
    if (level <= sectionLevel) break;
    sectionLines.push(line);
  }
  return sectionLines;
}

// Read the leading run of UID tokens from one bullet line, stopping at the first
// token that is not a recognizable UID. Recognition, not identity validation:
// these tokens come from free-form issue prose, so the bounded-identifier
// contract would accept ordinary words. The anchored recognizer still finds
// allocator-minted short UIDs like APP-2, so a requirement-backed run is not
// silently reduced to a requirement-free one (issue #1425).
function requirementUidsFromBullet(line) {
  const bullet = REQUIREMENTS_BULLET_RE.exec(line);
  if (!bullet) return [];
  const uids = [];
  for (const token of bullet[1].split(/[\s,;]+/)) {
    const candidate = stripUidTokenWrappers(token);
    if (!isRequirementUidToken(candidate)) break;
    uids.push(candidate);
  }
  return uids;
}

export function extractInScopeRequirementUids(issueBody) {
  if (typeof issueBody !== "string" || issueBody === "") return [];
  const seen = new Set();
  const result = [];
  for (const line of requirementsSectionLines(issueBody)) {
    for (const candidate of requirementUidsFromBullet(line)) {
      if (seen.has(candidate)) continue;
      seen.add(candidate);
      result.push(candidate);
    }
  }
  return result;
}
export function requestedRequirementUidAuthorization(issueBody, requestedRequirementUid) {
  if (requestedRequirementUid == null || requestedRequirementUid === "") {
    return { ok: true, requirementUid: null };
  }
  if (!EXACT_REQUIREMENT_UID_RE.test(requestedRequirementUid)) {
    return {
      ok: false,
      error: "implement_requested_requirement_uid_invalid",
      message: "The requested requirement UID is not a bounded requirement identifier",
      next_action: "supply_a_valid_requirement_uid_and_retry",
    };
  }
  if (!extractInScopeRequirementUids(issueBody).includes(requestedRequirementUid)) {
    return {
      ok: false,
      // The value stays out of the message: these envelopes propagate to tool
      // results, and the environment is the only place the requested UID is
      // allowed to exist. The caller supplied the value, so naming the failed
      // condition is enough to act on.
      error: "implement_requested_requirement_uid_out_of_scope",
      message: "The requested requirement UID is absent from the issue's Requirements section",
      next_action: "add_the_requested_requirement_to_the_authoritative_issue_section_and_retry",
    };
  }
  return { ok: true, requirementUid: requestedRequirementUid };
}
export async function runImplementGit(repoRoot, args, commandRunner = execFile) {
  return commandRunner(
    "git",
    ["-c", "core.hooksPath=/dev/null", "-c", "commit.gpgSign=false", "-C", repoRoot, ...args],
    { cwd: repoRoot, env: implementNetworkGitEnvironment() },
  );
}
export async function authorizeImplementMutationCheckout(repoPath, {
  workspaceAuthorizationResolver = resolveMcpLaunchWorkspaceAuthorization,
} = {}) {
  let repoRoot;
  try {
    repoRoot = realpathSync(await ensureGitRepo(repoPath));
  } catch (error) {
    return {
      ok: false,
      error: "implement_mutation_checkout_invalid",
      message: error.message,
    };
  }
  const authorization = await authorizeImplementRepoRoot(
    repoRoot,
    workspaceAuthorizationResolver,
  );
  if (!authorization.ok) return authorization;
  try {
    await assertSafeImplementCheckoutConfiguration(repoRoot);
  } catch (error) {
    return {
      ok: false,
      error: "implement_mutation_checkout_unsafe",
      message: error.message,
    };
  }
  return { ok: true, repoRoot };
}
export async function runImplementGitCommand(repoRoot, args, commandRunner = execFile) {
  return runImplementGit(repoRoot, args, commandRunner);
}
export async function runImplementPreCommit(
  repoRoot,
  commandRunner = execFile,
  context = null,
  requestedRequirementUid = null,
) {
  return commandRunner(
    "bash",
    ["-c", resolveWorkflowPrecommitCommand(context)],
    {
      cwd: repoRoot,
      env: implementGateEnvironment(
        requestedRequirementUid,
        implementNetworkGitEnvironment(),
      ),
    },
  );
}
export async function readImplementGitOid(repoRoot, ref, commandRunner = execFile) {
  const { stdout } = await runImplementGit(
    repoRoot,
    ["rev-parse", "--verify", `${ref}^{commit}`],
    commandRunner,
  );
  const oid = stdout.trim().toLowerCase();
  if (!GIT_OBJECT_ID_RE.test(oid)) {
    throw new Error("Git returned an invalid object ID");
  }
  return oid;
}
export async function readImplementTreeOid(repoRoot, ref, commandRunner = execFile) {
  const { stdout } = await runImplementGit(
    repoRoot,
    ["rev-parse", "--verify", `${ref}^{tree}`],
    commandRunner,
  );
  const oid = stdout.trim().toLowerCase();
  if (!GIT_OBJECT_ID_RE.test(oid)) {
    throw new Error("Git returned an invalid tree object ID");
  }
  return oid;
}
async function readImplementIndexTreeOid(repoRoot, commandRunner = execFile) {
  const { stdout } = await runImplementGit(repoRoot, ["write-tree"], commandRunner);
  const oid = stdout.trim().toLowerCase();
  if (!GIT_OBJECT_ID_RE.test(oid)) {
    throw new Error("Git returned an invalid index tree object ID");
  }
  return oid;
}
async function readImplementActiveBranch(repoRoot, commandRunner = execFile) {
  const { stdout } = await runImplementGit(
    repoRoot,
    ["symbolic-ref", "--quiet", "--short", "HEAD"],
    commandRunner,
  );
  return stdout.trim();
}
export async function assertImplementSyncCheckout({
  repoRoot,
  issueNumber,
  branchName,
  commandRunner = execFile,
  allowMergeState = false,
}) {
  const activeBranch = await readImplementActiveBranch(repoRoot, commandRunner);
  if (activeBranch !== branchName) {
    return {
      ok: false,
      error: "implement_base_sync_branch_mismatch",
      message: `The active branch must be '${branchName}'`,
      next_action: "return_to_the_issue_branch_and_retry",
    };
  }
  const branchValidation = validateImplementBranchName(activeBranch, issueNumber);
  if (!branchValidation.ok) return branchValidation;
  const { stdout } = await runImplementGit(
    repoRoot,
    ["status", "--porcelain=v1", "--untracked-files=normal"],
    commandRunner,
  );
  if (!allowMergeState && stdout.trim() !== "") {
    return {
      ok: false,
      error: "implement_base_sync_dirty_tree",
      message: "The pre-PR synchronization boundary requires a clean feature checkout",
      next_action: "finish_and_commit_feature_work_then_retry",
    };
  }
  return { ok: true };
}
export async function fetchImplementBase(repoRoot, baseBranch, commandRunner = execFile) {
  const remoteRef = `refs/remotes/origin/${baseBranch}`;
  await runImplementGit(
    repoRoot,
    [
      "fetch",
      "--no-tags",
      "origin",
      `+refs/heads/${baseBranch}:${remoteRef}`,
    ],
    commandRunner,
  );
  return {
    remoteRef,
    fetchedBaseSha: await readImplementGitOid(repoRoot, remoteRef, commandRunner),
  };
}
export async function isImplementAncestor(repoRoot, ancestor, descendant, commandRunner = execFile) {
  try {
    await runImplementGit(
      repoRoot,
      ["merge-base", "--is-ancestor", ancestor, descendant],
      commandRunner,
    );
    return true;
  } catch (error) {
    if (error?.code === 1) return false;
    throw error;
  }
}
export async function readRemoteImplementBranchSha(repoRoot, branchName, commandRunner = execFile) {
  const { stdout } = await runImplementGit(
    repoRoot,
    ["ls-remote", "--heads", "origin", `refs/heads/${branchName}`],
    commandRunner,
  );
  const lines = stdout.trim().split(/\r?\n/).filter(Boolean);
  if (lines.length !== 1) return null;
  const [oid, ref] = lines[0].split(/\s+/);
  if (ref !== `refs/heads/${branchName}` || !GIT_OBJECT_ID_RE.test(oid?.toLowerCase())) {
    return null;
  }
  return oid.toLowerCase();
}
export async function runImplementFinalTreeGates(
  repoRoot,
  context,
  commandRunner = execFile,
  requestedRequirementUid = null,
) {
  const completionCommand =
    context?.workflow?.completion_command ?? context?.workflow?.test_command;
  if (typeof completionCommand !== "string" || completionCommand.trim() === "") {
    const error = new Error("No completion command is configured");
    error.code = "implement_base_sync_completion_command_missing";
    throw error;
  }
  const readStatus = async () =>
    (await runImplementGit(repoRoot, ["status", "--porcelain=v1", "--untracked-files=normal"], commandRunner)).stdout;
  // The gates read the working tree, but the merge commit is built from the
  // index. An unstaged modification or an untracked file gets verified and then
  // left behind, so refuse before running the gates. Staged entries (`X ` in
  // porcelain v1) are the merge itself and are expected here.
  const unstaged = (await readStatus())
    .split(/\r?\n/)
    .filter((line) => line !== "")
    .filter((line) => line[1] !== " ");
  if (unstaged.length > 0) {
    const error = new Error(
      "The final-tree gates require every change to be staged; "
      + "stage or revert the working-tree changes and retry",
    );
    error.code = "implement_base_sync_worktree_not_staged";
    throw error;
  }
  // Completion and policy run through the ONE shared invariant-preserving
  // boundary (issue #1497) so this path and Step 6 verify bind identical inputs
  // and cannot drift; it re-validates the staged index tree after the fingerprint
  // and after each gate. Returns { treeOid, toolchainDigest, timings }.
  return runVerifiedGateBoundary({
    repoRoot,
    context,
    gateEnv: implementGateEnvironment(requestedRequirementUid),
    commandRunner,
    readTreeOid: () => readImplementIndexTreeOid(repoRoot, commandRunner),
    readStatus,
  });
}

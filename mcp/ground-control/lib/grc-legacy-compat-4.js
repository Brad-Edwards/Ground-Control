// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { mkdtempSync, realpathSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { isAbsolute, join } from "node:path";
import { parsePhaseMarkers } from "./codex-review.js";
import { codexEngineEnv } from "./codex-engine-env.js";
import { MCP_LAUNCH_CWD, evaluateExecutionObligations, isDefaultImplementHooksPath, parseExecutionObligationMarkers, readGeneratedCodexSummary } from "./codex-workflow.js";
import { ENRICH_THREAD_PAGE_CAP } from "./grc-legacy-compat-2.js";
import { getAuthenticatedGitHubLogin, getOwnerRepo, hasVerifiedStructuredWontfixAuthorization, readIssueCommentBodies, readIssueCommentsWithAuthors, resolveExecutionObligationTrust } from "./grc-legacy-compat-3.js";
import { STATION_OBSERVATION_DISPOSITION, hasVerifiedStationReobservation } from "./execution-obligation-v2.js";
import { buildCodexReviewExecArgs } from "./grc-legacy-compat.js";
import { getDefaultCodexTimeoutMs, execFile, execFileWithInput, formatCommandFailure } from "./runtime-primitives.js";

export async function ensureGitRepo(repoPath) {
  if (!repoPath || !isAbsolute(repoPath)) {
    throw new Error("repo_path must be an absolute path to a Git repository");
  }

  try {
    const { stdout } = await execFile("git", ["-C", repoPath, "rev-parse", "--show-toplevel"]);
    return stdout.trim();
  } catch (error) {
    throw new Error(`repo_path is not a valid Git repository: ${formatCommandFailure("git", error)}`);
  }
}
async function captureImplementWorkspaceAuthorization(cwd) {
  const { stdout } = await execFile("git", ["-C", cwd, "rev-parse", "--show-toplevel"]);
  const workspaceRoot = realpathSync(stdout.trim());
  const identity = await readGitIdentity(workspaceRoot);
  const { owner, name } = await getOwnerRepo(workspaceRoot, { allowGhFallback: false });
  return Object.freeze({
    workspaceRoot,
    gitDir: identity.gitDir,
    origin: identity.origin,
    owner: owner.toLowerCase(),
    name: name.toLowerCase(),
  });
}
const MCP_LAUNCH_WORKSPACE_AUTHORIZATION = captureImplementWorkspaceAuthorization(
  MCP_LAUNCH_CWD,
).catch(() => null);
export async function resolveMcpLaunchWorkspaceAuthorization() {
  return MCP_LAUNCH_WORKSPACE_AUTHORIZATION;
}
export async function authorizeImplementRepoRoot(repoRoot, workspaceAuthorizationResolver) {
  let authorization;
  try {
    authorization = await workspaceAuthorizationResolver();
  } catch {
    authorization = null;
  }
  if (
    authorization == null
    || typeof authorization !== "object"
    || typeof authorization.workspaceRoot !== "string"
    || typeof authorization.gitDir !== "string"
    || typeof authorization.origin !== "string"
    || typeof authorization.owner !== "string"
    || typeof authorization.name !== "string"
  ) {
    return {
      ok: false,
      error: "implement_workspace_root_unavailable",
      message: "The MCP launch workspace and repository identity could not be captured",
    };
  }
  const workspaceRoot = realpathSync(authorization.workspaceRoot);
  if (realpathSync(repoRoot) !== workspaceRoot) {
    return {
      ok: false,
      error: "implement_repo_not_authorized",
      message: "The requested repository is outside the MCP launch workspace authorized for this run",
    };
  }
  let current;
  let currentOwnerRepo;
  try {
    current = await readGitIdentity(repoRoot);
    currentOwnerRepo = await getOwnerRepo(repoRoot, { allowGhFallback: false });
  } catch {
    return {
      ok: false,
      error: "implement_repo_identity_unverifiable",
      message: "The requested repository identity could not be verified",
    };
  }
  if (
    current.gitDir !== realpathSync(authorization.gitDir)
    || current.origin !== authorization.origin
    || currentOwnerRepo.owner.toLowerCase() !== authorization.owner.toLowerCase()
    || currentOwnerRepo.name.toLowerCase() !== authorization.name.toLowerCase()
  ) {
    return {
      ok: false,
      error: "implement_repo_identity_changed",
      message: "The checkout origin or Git directory differs from the identity captured at MCP launch",
    };
  }
  return {
    ok: true,
    workspaceRoot,
    gitDir: current.gitDir,
    origin: authorization.origin,
    owner: authorization.owner,
    name: authorization.name,
  };
}
export async function readGitIdentity(repoRoot) {
  const [top, gitDir, origin] = await Promise.all([
    execFile("git", ["-C", repoRoot, "rev-parse", "--show-toplevel"]),
    execFile("git", ["-C", repoRoot, "rev-parse", "--absolute-git-dir"]),
    execFile("git", ["-C", repoRoot, "remote", "get-url", "origin"]),
  ]);
  return {
    topLevel: realpathSync(top.stdout.trim()),
    gitDir: realpathSync(gitDir.stdout.trim()),
    origin: origin.stdout.trim(),
  };
}
export async function assertSafeImplementCheckoutConfiguration(repoRoot) {
  const dangerousKey =
    /^(?:core\.(?:hookspath|sshcommand|askpass|fsmonitor)|credential(?:\.|$)|filter\..*\.(?:clean|smudge|process|required)|diff\..*\.command|merge\..*\.driver|include(?:if\..*)?\.path|url\..*\.(?:insteadof|pushinsteadof)|remote\..*\.(?:proxy|uploadpack|receivepack))$/i;
  const { stdout } = await execFile(
    "git",
    ["-C", repoRoot, "config", "--local", "--name-only", "--get-regexp", ".*"],
  ).catch((error) => {
    if (error.code === 1) return { stdout: "" };
    throw error;
  });
  let configuredDangerousKeys = stdout
    .split(/\r?\n/)
    .map((key) => key.trim())
    .filter((key) => key !== "" && dangerousKey.test(key));
  if (configuredDangerousKeys.some((key) => key.toLowerCase() === "core.hookspath")) {
    const [{ stdout: hooksPath }, { stdout: gitDir }, { stdout: gitCommonDir }] = await Promise.all([
      execFile("git", ["-C", repoRoot, "config", "--local", "--path", "--get", "core.hooksPath"]),
      execFile("git", ["-C", repoRoot, "rev-parse", "--absolute-git-dir"]),
      execFile("git", ["-C", repoRoot, "rev-parse", "--git-common-dir"]),
    ]);
    if (isDefaultImplementHooksPath({
      repoRoot,
      hooksPath: hooksPath.trim(),
      gitDir: gitDir.trim(),
      gitCommonDir: gitCommonDir.trim(),
    })) {
      configuredDangerousKeys = configuredDangerousKeys.filter(
        (key) => key.toLowerCase() !== "core.hookspath",
      );
    }
  }
  if (configuredDangerousKeys.length > 0) {
    throw new Error(
      `caller-controlled executable Git configuration is not permitted: ${configuredDangerousKeys.join(", ")}`,
    );
  }
}
export async function readTrustedExecutionObligationState(repoRoot, owner, name, issueNumber) {
  const comments = await readIssueCommentsWithAuthors(repoRoot, owner, name, issueNumber);
  const markerComments = comments
    .map((comment) => ({
      comment,
      events: parseExecutionObligationMarkers([comment.body], issueNumber),
    }))
    .filter(({ events }) => events.length > 0);
  if (markerComments.length === 0) {
    return { ok: true, ...evaluateExecutionObligations([]) };
  }
  const trust = await resolveExecutionObligationTrust(
    repoRoot,
    owner,
    name,
    comments,
  );
  const untrustedMarker = markerComments.find(({ comment }) => !trust.isTrusted(comment));
  if (untrustedMarker != null) {
    return {
      ok: false,
      error: "execution_obligation_provenance_unverifiable",
      message:
        "An execution-obligation marker was authored outside the repository's authorized signer set",
    };
  }
  const trustedEvents = await filterAttestedReobservations(repoRoot, markerComments, comments);
  for (const event of trustedEvents) {
    if (event.event !== "resolved" || event.disposition !== "wontfix") continue;
    const authorization = comments.find(
      (comment) => comment.id === event.authorization_comment_id,
    );
    if (
      authorization == null
      || !hasVerifiedStructuredWontfixAuthorization(
        authorization,
        comments,
        trust,
        issueNumber,
        event.obligation_id,
      )
    ) {
      return {
        ok: false,
        error: "execution_obligation_authorization_unverifiable",
        message:
          `The wontfix resolution for '${event.obligation_id}' lacks a verified structured authorization record`,
      };
    }
  }
  return {
    ok: true,
    ...evaluateExecutionObligations(trustedEvents),
  };
}
/**
 * Drop `reobserved` resolutions that are not attested by the trusted MCP posting identity.
 *
 * Dropped rather than raised as an error: an unattested marker leaves its obligation open, which
 * keeps completion blocked and the problem visible. Failing the whole read instead would let
 * anyone who can comment on the issue wedge the run by pasting a marker-shaped record.
 *
 * The trusted login is resolved only when such a resolution is actually present, so the common
 * path keeps its current number of GitHub calls.
 */
async function filterAttestedReobservations(repoRoot, markerComments, comments) {
  const events = markerComments.flatMap(({ events: parsed }) => parsed);
  const hasReobservation = events.some(
    (event) => event.event === "resolved"
      && event.disposition === STATION_OBSERVATION_DISPOSITION,
  );
  if (!hasReobservation) return events;
  const trustedLogin = await getAuthenticatedGitHubLogin(repoRoot);
  const attested = [];
  for (const { comment, events: parsed } of markerComments) {
    for (const event of parsed) {
      const isReobservation = event.event === "resolved"
        && event.disposition === STATION_OBSERVATION_DISPOSITION;
      if (
        isReobservation
        && !hasVerifiedStationReobservation(event, comment, comments, trustedLogin)
      ) {
        continue;
      }
      attested.push(event);
    }
  }
  return attested;
}
/**
 * Phases whose completion markers were authored by someone with repository permission.
 *
 * Every caller uses the result as a prerequisite gate — the plan post, the review cap, and the
 * completion record all refuse to proceed until the phases they require appear here. The markers
 * were previously read from comment bodies alone, with no regard for who wrote them, so anyone able
 * to comment on the issue could paste a `gc:phase` marker and satisfy a prerequisite that no phase
 * had actually met. Execution-obligation markers on the same thread already resolve their author's
 * effective repository permission before they are believed; phase markers now use the same trust
 * model, since they gate the same workflow.
 *
 * Fails closed. A marker whose author cannot be established, or a trust lookup that cannot be
 * completed, yields no phase rather than an assumed one: an unmet prerequisite blocks, and blocking
 * on an unverifiable marker is the safe direction.
 */
export async function readCompletedPhases(repoRoot, owner, name, issueNumber) {
  let comments;
  try {
    comments = await readIssueCommentsWithAuthors(repoRoot, owner, name, issueNumber);
  } catch {
    return new Set();
  }
  const markerComments = comments.filter(
    (comment) => parsePhaseMarkers([comment.body], issueNumber).size > 0,
  );
  if (markerComments.length === 0) {
    return new Set();
  }
  let trust;
  try {
    trust = await resolveExecutionObligationTrust(repoRoot, owner, name, comments);
  } catch {
    return new Set();
  }
  const trustedBodies = markerComments.filter((comment) => trust.isTrusted(comment)).map((c) => c.body);
  return parsePhaseMarkers(trustedBodies, issueNumber);
}
export async function getCurrentBranchName(repoRoot) {
  try {
    const { stdout } = await execFile("git", ["-C", repoRoot, "rev-parse", "--abbrev-ref", "HEAD"], {
      cwd: repoRoot,
    });
    const branch = String(stdout).trim();
    if (!branch || branch === "HEAD") return null;
    return branch;
  } catch {
    return null;
  }
}
export async function autoDetectPrNumber(repoRoot) {
  // Pin --repo to the git-remote-derived slug so a rogue GH_REPO on the
  // MCP host can't redirect this lookup at a different repo. --repo is
  // placed at the end of argv (gh accepts flags in any order) so the
  // hermetic-shim test fixtures' strict argv-prefix matches still work.
  try {
    const { owner, name } = await getOwnerRepo(repoRoot);
    const { stdout } = await execFile(
      "gh",
      ["pr", "view", "--json", "number", "--repo", `${owner}/${name}`],
      { cwd: repoRoot },
    );
    const data = JSON.parse(stdout);
    const n = Number.parseInt(data.number, 10);
    return Number.isInteger(n) && n > 0 ? n : null;
  } catch {
    return null;
  }
}
export async function fetchReviewCommentById(repoRoot, owner, name, commentId) {
  const { stdout } = await execFile(
    "gh",
    ["api", `/repos/${owner}/${name}/pulls/comments/${commentId}`],
    { cwd: repoRoot },
  );
  return JSON.parse(stdout);
}
export async function enrichCommentsWithThreadIds({ repoRoot, owner, name, prNumber, commentIds }) {
  if (!commentIds || commentIds.length === 0) {
    return new Map();
  }
  const wanted = new Set(commentIds);
  const result = new Map();
  let cursor = null;
  let pages = 0;

  while (result.size < wanted.size) {
    if (pages >= ENRICH_THREAD_PAGE_CAP) {
      // Don't throw — the caller is happy to receive partial mapping (missing
      // entries become null thread_ids in the returned comment list). Just
      // stop paging so we cannot loop forever.
      break;
    }
    pages += 1;
    const query = `
      query($owner:String!, $name:String!, $pr:Int!, $cursor:String) {
        repository(owner:$owner, name:$name) {
          pullRequest(number:$pr) {
            reviewThreads(first:100, after:$cursor) {
              pageInfo { hasNextPage endCursor }
              nodes {
                id
                comments(first:10) { nodes { databaseId } }
              }
            }
          }
        }
      }
    `;
    const args = [
      "api", "graphql",
      "-f", `query=${query}`,
      "-F", `owner=${owner}`,
      "-F", `name=${name}`,
      "-F", `pr=${prNumber}`,
    ];
    if (cursor) args.push("-f", `cursor=${cursor}`);
    const { stdout } = await execFile("gh", args, { cwd: repoRoot });
    const data = JSON.parse(stdout);
    const threads = data?.data?.repository?.pullRequest?.reviewThreads;
    if (!threads) break;
    for (const node of threads.nodes || []) {
      for (const c of node.comments?.nodes || []) {
        if (wanted.has(c.databaseId) && !result.has(c.databaseId)) {
          result.set(c.databaseId, node.id);
        }
      }
    }
    if (!threads.pageInfo?.hasNextPage) break;
    cursor = threads.pageInfo.endCursor;
  }

  return result;
}
async function collectUnreviewedUntrackedPaths(repoRoot) {
  const { stdout } = await execFile(
    "git",
    ["-C", repoRoot, "ls-files", "--others", "--exclude-standard", "-z"],
    { maxBuffer: 10 * 1024 * 1024 },
  );
  return stdout.split("\0").filter((p) => p !== "");
}
export async function computeReviewDiff(repoRoot, baseBranch, uncommitted) {
  if (uncommitted) {
    const staged = await execFile("git", ["-C", repoRoot, "diff", "--staged"], { maxBuffer: 50 * 1024 * 1024 });
    const unstaged = await execFile("git", ["-C", repoRoot, "diff"], { maxBuffer: 50 * 1024 * 1024 });
    const stagedManifest = await execFile(
      "git",
      ["-C", repoRoot, "diff", "--staged", "--numstat"],
      { maxBuffer: 10 * 1024 * 1024 },
    );
    const unstagedManifest = await execFile(
      "git",
      ["-C", repoRoot, "diff", "--numstat"],
      { maxBuffer: 10 * 1024 * 1024 },
    );
    const unreviewedUntrackedPaths = await collectUnreviewedUntrackedPaths(repoRoot);
    return {
      diffText: `${staged.stdout}\n${unstaged.stdout}`.trim(),
      manifest: [
        "# staged",
        stagedManifest.stdout.trim() || "(none)",
        "",
        "# unstaged",
        unstagedManifest.stdout.trim() || "(none)",
        // Count only: the manifest goes into the reviewer prompt, and a path
        // can itself be revealing. The caller gets the full list off-prompt.
        ...(unreviewedUntrackedPaths.length > 0
          ? [
              "",
              `# untracked: ${unreviewedUntrackedPaths.length} path(s) present but NOT staged and NOT included in this review`,
            ]
          : []),
      ].join("\n"),
      baseRefDescriptor: null,
      unreviewedUntrackedPaths,
    };
  }
  const candidates = [`origin/${baseBranch}`, baseBranch, "origin/main", "main"];
  for (const ref of candidates) {
    try {
      await execFile("git", ["-C", repoRoot, "rev-parse", "--verify", ref]);
      const { stdout } = await execFile(
        "git",
        ["-C", repoRoot, "diff", `${ref}...HEAD`],
        { maxBuffer: 50 * 1024 * 1024 },
      );
      const manifest = await execFile(
        "git",
        ["-C", repoRoot, "diff", `${ref}...HEAD`, "--numstat"],
        { maxBuffer: 10 * 1024 * 1024 },
      );
      return {
        diffText: stdout,
        manifest: manifest.stdout.trim() || "(no files changed)",
        baseRefDescriptor: ref,
        unreviewedUntrackedPaths: [],
      };
    } catch {
      continue;
    }
  }
  throw new Error(`Unable to compute review diff: none of ${candidates.join(", ")} exist in ${repoRoot}`);
}
export async function runSingleCodexReview({ repoRoot, prompt, signal = undefined }) {
  const tempDir = mkdtempSync(join(tmpdir(), "gc-codex-review-"));
  const outputPath = join(tempDir, "codex-last-message.txt");
  try {
    await execFileWithInput(
      "codex",
      buildCodexReviewExecArgs({ repoPath: repoRoot, outputPath }),
      {
        input: prompt,
        cwd: repoRoot,
        maxBuffer: 10 * 1024 * 1024,
        env: codexEngineEnv(),
        timeoutMs: getDefaultCodexTimeoutMs(),
        signal,
      },
    );
    return readGeneratedCodexSummary(outputPath);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
}

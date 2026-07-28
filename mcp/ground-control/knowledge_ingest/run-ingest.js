// Split from knowledge_ingest.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Declaration bodies are unchanged.

import { existsSync, readFileSync, realpathSync } from "node:fs";
import { isAbsolute, relative, resolve as resolvePath } from "node:path";
import { acquireKnowledgeLock } from "../lib.js";
import { buildIngestPrompt, collectWorktreeChanges, defaultIngestAgent, execFile, parseIngestResultTail, resolveSymbolicBranch, revertWorktreeChanges, splitFrontmatter, validateCommitIsolation } from "./exec-file.js";

// Run the full ingest transaction for a single inbox item. See module
// header for the higher-level contract. The function owns:
//   - Branch check (reject detached HEAD)
//   - Lock acquisition (serialization)
//   - Pre-ingest snapshot of dirty state (to avoid clobbering user work)
//   - Codex invocation
//   - Commit-isolation validation
//   - Commit (knowledge tree + inbox item only)
//   - Lock release
//   - Latency measurement
//
// Returns { ok: true, action, page, commit_sha, latency_ms, citations_added }
// on success. Throws on any failure; the caller (CLI or test) is
// responsible for turning thrown errors into structured output and for
// deciding whether to retry.
export async function runIngest({
  repoRoot,
  inboxFilePath,
  knowledge,
  ingestAgent = defaultIngestAgent,
  now = Date.now,
}) {
  if (typeof repoRoot !== "string" || !isAbsolute(repoRoot)) {
    throw new Error("runIngest: repoRoot must be an absolute path");
  }
  if (typeof inboxFilePath !== "string" || !isAbsolute(inboxFilePath)) {
    throw new Error("runIngest: inboxFilePath must be an absolute path");
  }
  if (!knowledge || typeof knowledge !== "object") {
    throw new Error("runIngest: knowledge block is required");
  }
  for (const field of ["dir", "schema", "inbox"]) {
    if (typeof knowledge[field] !== "string" || knowledge[field] === "") {
      throw new Error(`runIngest: knowledge.${field} is required`);
    }
  }

  // Canonicalize repo root so the lock is keyed by inode identity, not by
  // whatever path spelling the caller happened to pass in. This matches
  // the containment logic in lib.js:resolveKnowledgeBlock.
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- repoRoot is caller-validated absolute
  const repoRootReal = realpathSync(repoRoot);

  const knowledgeDirRel = knowledge.dir;
  const absKnowledgeDir = resolvePath(repoRootReal, knowledgeDirRel);
  const inboxFileRel = relative(repoRootReal, inboxFilePath);

  // Read the inbox item up front so we have its captured_at timestamp for
  // latency calculation, plus a stable bytes snapshot we can use to prove
  // the file was left untouched on failure.
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- inboxFilePath is caller-validated absolute and anchored under knowledge.inbox
  if (!existsSync(inboxFilePath)) {
    throw new Error(`runIngest: inbox file does not exist: ${inboxFilePath}`);
  }
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- inboxFilePath is caller-validated absolute
  const inboxPayload = readFileSync(inboxFilePath, "utf8");
  const { frontmatter: inboxFrontmatter } = splitFrontmatter(inboxPayload);
  const capturedAtIso = typeof inboxFrontmatter.captured_at === "string"
    ? inboxFrontmatter.captured_at
    : null;
  const source = typeof inboxFrontmatter.source === "string" ? inboxFrontmatter.source : null;

  // Enforce symbolic-branch invariant before touching anything.
  await resolveSymbolicBranch(repoRootReal);

  // Acquire the per-knowledge-base lock. We pass a retry policy here
  // rather than failing fast: the real-time capture path expects two
  // captures fired in quick succession to both land in the wiki, so the
  // second ingest should wait for the first to finish instead of
  // returning an error. Total wait budget: ~20s across 15 retries with
  // exponential backoff capped at 2s. That is long enough to serialize
  // a typical ingest (~5s with Claude Code) without holding the caller forever.
  const release = await acquireKnowledgeLock(absKnowledgeDir, {
    retries: {
      retries: 15,
      factor: 1.5,
      minTimeout: 100,
      maxTimeout: 2000,
    },
  });

  // Snapshot pre-existing dirty files so we can tell user changes apart
  // from agent-introduced changes when we validate commit isolation and
  // when we revert on failure.
  const preexistingDirty = await collectWorktreeChanges(repoRootReal);

  let agentResult;
  try {
    // Read index.md and log.md tail for the prompt context.
    const indexMdAbs = resolvePath(repoRootReal, knowledge.dir, "index.md");
    const logMdAbs = resolvePath(repoRootReal, knowledge.dir, "log.md");
    let indexMdContent = "";
    let logMdContent = "";
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- derived from validated knowledge.dir
    if (existsSync(indexMdAbs)) {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- derived from validated knowledge.dir
      indexMdContent = readFileSync(indexMdAbs, "utf8");
    }
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- derived from validated knowledge.dir
    if (existsSync(logMdAbs)) {
      // eslint-disable-next-line security/detect-non-literal-fs-filename -- derived from validated knowledge.dir
      logMdContent = readFileSync(logMdAbs, "utf8");
    }
    const logMdLines = logMdContent.split("\n");
    const logMdTail = logMdLines.slice(-20).join("\n");

    const prompt = buildIngestPrompt({
      knowledgeDir: knowledge.dir,
      knowledgeSchemaRel: knowledge.schema,
      inboxFileAbs: inboxFilePath,
      inboxFileRel,
      inboxDirRel: knowledge.inbox,
      inboxPayload,
      indexMdContent,
      logMdTail,
    });

    agentResult = await ingestAgent({ repoRoot: repoRootReal, prompt });
    const resultTail = parseIngestResultTail(agentResult.stdout || "");

    // Validate commit isolation: every changed file must be under the
    // knowledge tree or the inbox item path. If any path is outside that
    // allowlist, we revert ALL agent-introduced changes, leave the inbox
    // file untouched, and throw.
    const changedAfter = await collectWorktreeChanges(repoRootReal);
    const agentChanges = new Set();
    for (const path of changedAfter) {
      if (preexistingDirty.has(path)) continue;
      agentChanges.add(path);
    }
    const isolation = validateCommitIsolation({
      changedFiles: agentChanges,
      knowledgeDirRel,
      inboxFileRel,
    });
    if (!isolation.ok) {
      await revertWorktreeChanges(repoRootReal, agentChanges, preexistingDirty);
      throw new Error(
        `runIngest: ingest agent wrote files outside the knowledge tree (commit isolation violation): ${isolation.unexpected.join(", ")}`,
      );
    }
    if (agentChanges.size === 0) {
      throw new Error("runIngest: ingest agent made no changes — nothing to commit");
    }

    // Stage exactly the agent-introduced paths. No `git add -A`.
    const stagePaths = Array.from(agentChanges);
    await execFile("git", ["-C", repoRootReal, "add", "--", ...stagePaths]);

    // Commit with the canonical citation-derived message. Repo-native
    // hooks (pre-commit, sign-off) run as normal — no `--no-verify`.
    const citation = source || resultTail.page;
    const commitMessage = `knowledge: ingest ${citation}\n\nAction: ${resultTail.action}\nPage: ${resultTail.page}\nCitations added: ${resultTail.citations_added}\n`;
    try {
      await execFile("git", ["-C", repoRootReal, "commit", "-m", commitMessage]);
    } catch (error) {
      // Commit failure: revert staged changes and leave the inbox item
      // untouched. The inbox file is itself one of the staged paths if
      // the agent moved it to processed/, so the revert restores it.
      await revertWorktreeChanges(repoRootReal, agentChanges, preexistingDirty);
      throw new Error(`runIngest: git commit failed: ${error.message}`);
    }

    const { stdout: headOut } = await execFile("git", ["-C", repoRootReal, "rev-parse", "HEAD"]);
    const commitSha = headOut.trim();

    const completedAt = now();
    let latencyMs = 0;
    if (capturedAtIso) {
      const capturedAt = Date.parse(capturedAtIso);
      if (!Number.isNaN(capturedAt)) {
        latencyMs = Math.max(0, completedAt - capturedAt);
      }
    }

    // Emit a structured log line on stderr so the CLI's parent (and
    // test harnesses) can observe latency without affecting stdout
    // (which is the ingest-agent tail protocol).
    process.stderr.write(
      JSON.stringify({
        event: "ingest_commit",
        citation,
        inbox_path: inboxFileRel,
        action: resultTail.action,
        page: resultTail.page,
        citations_added: resultTail.citations_added,
        commit_sha: commitSha,
        latency_ms: latencyMs,
      }) + "\n",
    );

    return {
      ok: true,
      action: resultTail.action,
      page: resultTail.page,
      citations_added: resultTail.citations_added,
      commit_sha: commitSha,
      latency_ms: latencyMs,
    };
  } catch (error) {
    // On any failure between lock acquisition and commit, revert agent
    // changes (if any were made) and rethrow. The inbox file is
    // deliberately NOT touched by this revert — if the agent renamed it,
    // the rename is one of the "agent changes" which get reverted.
    try {
      const current = await collectWorktreeChanges(repoRootReal);
      const agentChanges = new Set();
      for (const p of current) {
        if (!preexistingDirty.has(p)) agentChanges.add(p);
      }
      if (agentChanges.size > 0) {
        await revertWorktreeChanges(repoRootReal, agentChanges, preexistingDirty);
      }
    } catch {
      // Revert is best-effort; we prioritize surfacing the original error.
    }
    throw error;
  } finally {
    try {
      await release();
    } catch {
      // Release failures are logged by the lock helper. Do not let them
      // mask the original ingest result / error.
    }
  }
}

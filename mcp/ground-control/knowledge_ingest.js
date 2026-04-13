// Knowledge base ingest engine — GC-X007..GC-X011.
//
// This module is called by two entry points:
//
//   1. The `gc_remember` MCP tool spawns this via knowledge_ingest_cli.js
//      as a detached subprocess right after an agent captures an
//      observation (the "hot path"). The subprocess passes the exact
//      inbox file path plus the resolved repo / knowledge paths, so the
//      engine never rescans the inbox or re-parses .ground-control.yaml.
//
//   2. Unit tests call `runIngest` directly, injecting a stub ingest
//      agent that scripts the filesystem actions a real run would
//      produce. This lets us exercise read-before-write consistency,
//      serialization, commit isolation, and failure semantics without
//      shelling out to the real Claude Code CLI on every test.
//
// The engine delegates the actual "update existing page vs. create new
// page" decision to Claude Code (the ingest agent), then validates and
// commits the resulting filesystem changes under strict isolation and
// locking rules described in docs/notes/agent-knowledge-system-design.md
// §"Hot path guardrails for implementation".
//
// Claude Code is the only ingestion agent. Codex is deliberately NOT
// used for knowledge ingest: codex's role in this repo is confined to
// the workflows (architecture preflight, cross-model review) where it
// has already been wired in by name. Knowledge maintenance is a
// Claude Code responsibility by project decision.

import { existsSync, readFileSync, realpathSync } from "node:fs";
import { isAbsolute, join, relative, resolve as resolvePath } from "node:path";
import { execFile as execFileCb } from "node:child_process";
import { promisify } from "node:util";
import { load as parseYaml } from "js-yaml";
import { acquireKnowledgeLock, execFileWithInput } from "./lib.js";

const execFile = promisify(execFileCb);

// Parse the trailing `INGEST_RESULT={...}` line from the ingest agent's
// output. This mirrors `parseCodexReviewTail` in lib.js and gives the
// engine a machine-checkable signal that Claude Code finished and
// decided on an action. Throws if the line is missing, malformed, or
// carries an unknown action.
export function parseIngestResultTail(output) {
  if (typeof output !== "string") {
    throw new Error("parseIngestResultTail: output must be a string");
  }
  const lines = output.split(/\r?\n/);
  let tail = null;
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i].trim();
    if (line === "") continue;
    if (line.startsWith("INGEST_RESULT=")) {
      tail = line.slice("INGEST_RESULT=".length);
    }
    break;
  }
  if (tail == null) {
    throw new Error("parseIngestResultTail: no INGEST_RESULT tail line found");
  }
  let parsed;
  try {
    parsed = JSON.parse(tail);
  } catch (error) {
    throw new Error(`parseIngestResultTail: INGEST_RESULT payload is not valid JSON: ${error.message}`);
  }
  if (!parsed || typeof parsed !== "object") {
    throw new Error("parseIngestResultTail: INGEST_RESULT must be a JSON object");
  }
  const { action, page, citations_added: citationsAdded } = parsed;
  if (action !== "create" && action !== "update") {
    throw new Error(`parseIngestResultTail: unknown action '${action}' (expected 'create' or 'update')`);
  }
  if (typeof page !== "string" || page.trim() === "") {
    throw new Error("parseIngestResultTail: INGEST_RESULT.page must be a non-empty string");
  }
  if (typeof citationsAdded !== "number" || citationsAdded < 0) {
    throw new Error("parseIngestResultTail: INGEST_RESULT.citations_added must be a non-negative number");
  }
  return { action, page, citations_added: citationsAdded };
}

// Walk up the current branch name with a structured failure. Ingest
// commits land on whatever symbolic branch the repo happens to be on at
// the time, which keeps the knowledge update in the same PR as the code
// that taught the lesson. Detached HEAD or an unborn branch state is a
// retry failure — per GC-X010 we never invent a fallback branch or
// silently skip the commit.
async function resolveSymbolicBranch(repoRoot) {
  try {
    const { stdout } = await execFile("git", ["-C", repoRoot, "symbolic-ref", "--short", "HEAD"]);
    const name = stdout.trim();
    if (!name) {
      throw new Error("git symbolic-ref returned an empty branch name");
    }
    return name;
  } catch (error) {
    throw new Error(
      `refusing to ingest: repository ${repoRoot} is not on a symbolic branch (detached HEAD or unborn branch state). Ingest must run on a named branch so the commit lands in the same history as the code that taught the lesson. Leave the inbox file in place and retry after checking out a branch. underlying: ${error.message}`,
    );
  }
}

// Read frontmatter + body out of a markdown file. Returns
// { frontmatter, body }. Used to parse the inbox item so we can include
// its captured_at timestamp in the latency measurement.
function splitFrontmatter(source) {
  if (!source.startsWith("---\n")) {
    return { frontmatter: {}, body: source };
  }
  const end = source.indexOf("\n---", 4);
  if (end === -1) {
    return { frontmatter: {}, body: source };
  }
  const yamlBlock = source.slice(4, end);
  const bodyStart = source.indexOf("\n", end + 4);
  const body = bodyStart === -1 ? "" : source.slice(bodyStart + 1);
  let frontmatter = {};
  try {
    const parsed = parseYaml(yamlBlock);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      frontmatter = parsed;
    }
  } catch {
    // If the frontmatter is malformed, fall through with an empty
    // object — the ingest engine continues and the agent can decide
    // what to do with the body.
  }
  return { frontmatter, body };
}

// Default ingest agent invoker. Shells out to Claude Code in headless
// mode (`claude -p`) with a tight tool allowlist and directory access
// scoped to the repository root. Claude Code reads the prompt on the
// command line, uses its built-in Read/Edit/Write/Bash tools to make
// the wiki changes, and emits prose + the INGEST_RESULT tail on stdout.
// Tests replace this with a scripted stub via the `ingestAgent`
// parameter on `runIngest`.
//
// Flag rationale:
//   --print                         headless (no interactive session)
//   --bare                          skip hooks, LSP, plugin sync,
//                                   auto-memory, CLAUDE.md
//                                   auto-discovery — clean subprocess
//                                   environment
//   --add-dir <repo>                grants Claude Code tool access to
//                                   the target repo
//   --permission-mode
//     bypassPermissions             unattended ingest: no interactive
//                                   confirm prompts. Safe because
//                                   --allowed-tools restricts to a
//                                   minimal set and the engine
//                                   validates commit isolation after
//                                   the agent finishes.
//   --allowed-tools "Read Edit Write Bash(git status:*) Bash(git mv:*)"
//                                   minimum tools to read the wiki,
//                                   edit pages, and rename the inbox
//                                   item. No WebFetch, no Task, no
//                                   free-form Bash.
//   --max-budget-usd <cap>          hard spend cap per invocation
//   --model sonnet                  ingest decisions don't need opus;
//                                   sonnet is fast enough and cheap.
//   --output-format text            plain-text stdout we can grep for
//                                   the INGEST_RESULT tail.
async function defaultIngestAgent({ repoRoot, prompt, agentOverrides = {} }) {
  const model = agentOverrides.model || "sonnet";
  // `claude --print` reads the prompt from stdin by default and only
  // falls back to the positional arg after a 3 s stdin-wait timeout.
  // We pipe the prompt via stdin explicitly (through execFileWithInput)
  // so the subprocess starts the model call immediately without the
  // false-positive warning.
  //
  // NOTE: `--bare` is deliberately NOT used here. Per `claude --help`,
  // `--bare` restricts Anthropic auth to ANTHROPIC_API_KEY or an
  // apiKeyHelper — it refuses to read OAuth / keychain credentials.
  // The interactive operator session is OAuth-based (via `claude login`),
  // so a bare-mode subprocess with the API key stripped has no auth at
  // all and fails with "Not logged in". We keep the non-bare default
  // so the subprocess inherits the same logged-in session the operator
  // uses interactively, while still isolating tool access via
  // `--allowed-tools` and directory access via `--add-dir`.
  const args = [
    "--print",
    "--add-dir", repoRoot,
    "--permission-mode", "bypassPermissions",
    "--allowed-tools",
    "Read Edit Write Bash(git status:*) Bash(git mv:*) Bash(mkdir:*)",
    "--model", model,
    "--output-format", "text",
  ];
  // Strip `ANTHROPIC_API_KEY` from the subprocess env. When the parent
  // is itself a running Claude Code instance, it inherits an API key
  // in its environment that `claude -p` would prefer over the
  // logged-in session credentials at `~/.claude/.credentials.json`.
  // That is almost never what an operator wants: the operator logged
  // in interactively, so the session creds are the "real" auth they
  // expect unattended subprocess runs to use. Filtering the env var
  // (only in the child's env dict — this does not touch the parent's
  // env) lets `claude` fall back to the session credential file and
  // matches the auth path an interactive shell would use.
  //
  // Operators who explicitly want API-key auth for ingest can set
  // `GC_KNOWLEDGE_INGEST_ANTHROPIC_API_KEY` in their environment;
  // when present, we pass it through as ANTHROPIC_API_KEY for the
  // subprocess only, preserving the "bring your own dedicated key"
  // escape hatch without polluting the interactive session's auth.
  const childEnv = { ...process.env, NO_COLOR: "1" };
  delete childEnv.ANTHROPIC_API_KEY;
  if (agentOverrides.anthropicApiKey) {
    childEnv.ANTHROPIC_API_KEY = agentOverrides.anthropicApiKey;
  } else if (process.env.GC_KNOWLEDGE_INGEST_ANTHROPIC_API_KEY) {
    childEnv.ANTHROPIC_API_KEY = process.env.GC_KNOWLEDGE_INGEST_ANTHROPIC_API_KEY;
  }
  const { stdout, stderr } = await execFileWithInput("claude", args, {
    cwd: repoRoot,
    maxBuffer: 10 * 1024 * 1024,
    env: childEnv,
    input: prompt,
  });
  return { stdout, stderr };
}

// Build the ingest prompt sent to the ingest agent (Claude Code). The
// prompt fully describes the transaction the agent owns: read current
// wiki state, decide update vs create, write knowledge-tree files,
// append to log.md, move the inbox item, and emit the INGEST_RESULT
// tail. Staging and committing happen in the parent — the agent never
// runs git itself beyond the allowed `git mv` for the inbox move.
function buildIngestPrompt({
  knowledgeDir,
  knowledgeSchemaRel,
  inboxFileAbs,
  inboxFileRel,
  inboxDirRel,
  inboxPayload,
  indexMdContent,
  logMdTail,
}) {
  return [
    "You are the Ground Control knowledge ingest agent.",
    "",
    "An agent captured a new observation. Integrate it into the repo-local",
    `knowledge base at ${knowledgeDir}. Follow the conventions documented in`,
    `${knowledgeSchemaRel}.`,
    "",
    "Inbox item (absolute path):",
    inboxFileAbs,
    "",
    "Inbox item content (frontmatter + body):",
    "```",
    inboxPayload,
    "```",
    "",
    "Current index.md:",
    "```",
    indexMdContent,
    "```",
    "",
    "Tail of log.md (last ~20 lines):",
    "```",
    logMdTail,
    "```",
    "",
    "Required behavior (use your Read / Edit / Write / Bash tools):",
    "- Consult existing pages listed in index.md BEFORE deciding to create.",
    "  Read the candidate pages with your Read tool. If this observation",
    "  refines or extends an existing page, update that page in place via",
    "  your Edit tool. Preserve the existing page's frontmatter, sources",
    "  list, and cross-references. Incremental edits, not full regeneration.",
    "- If the observation is genuinely new, use your Write tool to create a",
    `  new page under the appropriate category directory under ${knowledgeDir}.`,
    "- Use your Edit tool to append a new dated bullet to log.md describing",
    "  the ingest.",
    "- Use `git mv` via your Bash tool to move the inbox item from",
    `  ${inboxFileRel} to ${inboxDirRel}/processed/ ONLY after you have`,
    "  written the page and updated index.md and log.md. If `git mv` fails",
    "  because the inbox file is untracked (it was just written by",
    "  gc_remember and has not been committed), use `mkdir -p` to ensure",
    "  the processed directory exists and then fall back to moving with",
    "  a plain filesystem move via your Bash tool.",
    `- Do NOT write or edit any file outside ${knowledgeDir}/ or the inbox`,
    "  item path. The parent process enforces commit isolation and will",
    "  abort the whole ingest if you touch anything outside that scope.",
    "- Do NOT stage or commit; the parent process does that. Do not run",
    "  `git add`, `git commit`, or `git push`.",
    "",
    "Emit exactly one trailing line as the VERY LAST line of your output,",
    "in this format (literal, no code fence, choose 'create' or 'update'):",
    "",
    '  INGEST_RESULT={"action":"create","page":"<relative path>","citations_added":<n>}',
    '  INGEST_RESULT={"action":"update","page":"<relative path>","citations_added":<n>}',
    "",
    "Use 'create' when you wrote a new page file, 'update' when you modified",
    "an existing one.",
  ].join("\n");
}

// Collect the set of files that changed in the worktree relative to HEAD.
// Returns a Set of repo-relative paths, including untracked files and
// renamed-from / renamed-to entries. We compare against HEAD (not the
// staging area) because the agent may have written directly to the worktree
// without staging.
async function collectWorktreeChanges(repoRoot) {
  const { stdout } = await execFile("git", [
    "-C",
    repoRoot,
    "status",
    "--porcelain=v1",
    "-uall",
  ]);
  const files = new Set();
  for (const rawLine of stdout.split("\n")) {
    if (rawLine.trim() === "") continue;
    // Porcelain v1 format: XY path [-> renamedPath]
    // X = index status, Y = worktree status, 2-char code + space + path.
    const code = rawLine.slice(0, 2);
    const rest = rawLine.slice(3);
    if (code.trim() === "") continue;
    // Rename entries: "R  from -> to" — include both sides.
    const renameIdx = rest.indexOf(" -> ");
    if (renameIdx !== -1) {
      const from = rest.slice(0, renameIdx);
      const to = rest.slice(renameIdx + " -> ".length);
      files.add(from);
      files.add(to);
    } else {
      files.add(rest);
    }
  }
  return files;
}

// Verify that every changed path is contained in the allowed set:
// somewhere under the knowledge directory (any file), or the exact inbox
// file path (so the agent can move it to processed/). Returns { ok: true }
// or { ok: false, unexpected: [...] }.
function validateCommitIsolation({ changedFiles, knowledgeDirRel, inboxFileRel }) {
  const unexpected = [];
  const allowedPrefix = knowledgeDirRel.endsWith("/") ? knowledgeDirRel : knowledgeDirRel + "/";
  for (const path of changedFiles) {
    if (path === inboxFileRel) continue;
    if (path.startsWith(allowedPrefix)) continue;
    unexpected.push(path);
  }
  if (unexpected.length > 0) {
    return { ok: false, unexpected };
  }
  return { ok: true };
}

// Abort cleanly: revert any changes the agent made, using `git checkout --`
// for tracked paths and filesystem cleanup for untracked files. The goal
// is that after an abort the worktree is indistinguishable from before
// the ingest attempt, modulo whatever was already dirty when we started.
async function revertWorktreeChanges(repoRoot, changedFiles, preexistingDirty) {
  for (const path of changedFiles) {
    // Skip files that were already dirty before ingest started. We never
    // modify pre-existing user edits.
    if (preexistingDirty.has(path)) continue;
    // Try `git checkout -- path` first (reverts tracked file to HEAD).
    try {
      await execFile("git", ["-C", repoRoot, "checkout", "--", path]);
    } catch {
      // If checkout fails, the file is probably untracked; clean it up.
      try {
        await execFile("git", ["-C", repoRoot, "clean", "-f", "--", path]);
      } catch {
        // Best-effort; the caller already knows we're aborting.
      }
    }
  }
}

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

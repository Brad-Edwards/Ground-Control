// Split from knowledge_ingest.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Declaration bodies are unchanged.

import { relative } from "node:path";
import { execFile as execFileCb } from "node:child_process";
import { promisify } from "node:util";
import { load as parseYaml } from "js-yaml";
import { execFileWithInput } from "../lib.js";
import { runIngest } from "./run-ingest.js";

export const execFile = promisify(execFileCb);

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
}export // Walk up the current branch name with a structured failure. Ingest
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
}export // Read frontmatter + body out of a markdown file. Returns
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
}export // Default ingest agent invoker. Shells out to Claude Code in headless
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
}export // Build the ingest prompt sent to the ingest agent (Claude Code). The
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
}export // Collect the set of files that changed in the worktree relative to HEAD.
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
}export // Verify that every changed path is contained in the allowed set:
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
}export // Abort cleanly: revert any changes the agent made, using `git checkout --`
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

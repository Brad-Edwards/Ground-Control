# ADR-025: Knowledge Ingest Engine

## Status

accepted

## Date

2026-04-13

## Context

Issue #522 landed the knowledge-base skeleton (`.ground-control.yaml` `knowledge` section, `docs/knowledge/` with `SCHEMA.md`, `index.md`, `log.md`). Issue #523 now wires the *write path*: an agent captures an observation via the `gc_remember` MCP tool, and a detached subprocess integrates that observation into the wiki within seconds so the next agent invocation in the same working session sees it as a page.

The requirements that drive this work are GC-X006 through GC-X011. They define the capture primitive's input contract (GC-X006), read-before-write consistency (GC-X007), serialized writes within a single knowledge base (GC-X008), failure retry via untouched inbox files (GC-X009), commit-on-active-branch semantics (GC-X010), and real-time latency in seconds (GC-X011).

The design note at `docs/notes/agent-knowledge-system-design.md` describes the rollout in prose but deliberately leaves several "logistics, not architecture" decisions open for the implementation phase (§"Open decisions not blocking the design"). This ADR pins down the ones that affect code boundaries, so issues #524–#527 (consumption, admin CLI, scheduled sweep, lint) can anchor on a stable shape instead of re-litigating the same questions.

## Decision

### 1. One shared ingest engine, co-located with the MCP server

The ingest engine is a Node.js module at `mcp/ground-control/knowledge_ingest.js`, with a thin CLI entry point at `mcp/ground-control/knowledge_ingest_cli.js` that `gc_remember` spawns as a detached subprocess. The engine's core function (`runIngest`) is exported directly and used by the unit tests with an injected `ingestAgent` stub, so the full transaction can be exercised without shelling out to the real Claude Code CLI.

The original design note suggested `tools/ground_control/knowledge/ingest.py` — Python, in the general tooling area, shared between the hot path and a future CLI. We diverge from that suggestion for this slice:

- **Only consumer is Node.** `gc_remember` is an MCP tool; the MCP server is Node. The future CLI (issue #525) is hypothetical infrastructure and its language is undecided. Building Python infrastructure now just to hedge against a hypothetical future CLI violates the CLAUDE.md "don't design for future requirements" rule.
- **Existing test harness works as-is.** `mcp/ground-control/lib.test.js` already runs with `node --test`. `tools/ground_control/` has no JS test harness and would need one (or a whole Python test story) to match the engine's coverage.
- **Existing subprocess + path helpers live here.** `lib.js` already exposes `execFileWithInput`, `resolveRepoRelativePath`, `assertRealpathInRepo`, and `getRepoGroundControlContext`. The ingest engine reuses all of them via a single local import instead of cross-package coupling.
- **Dodges the `architecture/policies/adr-policy.json` ADR-014 trip.** That policy treats any change under `tools/ground_control/**` as verification-architecture territory and requires syncing ADR-014 and `docs/architecture/ARCHITECTURE.md`. Knowledge ingest is NOT verification; co-locating with the MCP server avoids the cross-doc friction without needing a policy carve-out.

When the admin CLI ships (issue #525), there are two clean paths forward:
- If the CLI is Node/Bun, it imports `runIngest` directly from `mcp/ground-control/knowledge_ingest.js`. Done.
- If the CLI is Python, we factor `knowledge_ingest.js` and its helpers out to a neutral location (e.g., `tools/ground_control/knowledge/`) AND the #525 slice owns the policy carve-out and the language-bridge decision. The refactor is local to this module: nothing else in the codebase imports it.

Either way, the decision is one lateral move, not a rewrite. Deferring the decision is cheaper than making the wrong one now.

### 2. Claude Code is the ingest "agent" (not codex)

The ingest engine does not attempt to decide update-vs-create itself. Instead, it builds a tightly-scoped prompt that includes the inbox item, the current `index.md`, the tail of `log.md`, and the wiki schema, and invokes **Claude Code** in headless mode (`claude --print`) with a restricted tool allowlist. Claude Code uses its built-in `Read` / `Edit` / `Write` / scoped `Bash` tools to read what it needs, write the new/updated page directly to the worktree, append to `index.md` and `log.md`, and move the inbox item to `inbox/processed/`. The engine then:

1. Inspects `git status --porcelain -uall` to compute the set of files the agent touched.
2. Subtracts any files that were already dirty before ingest ran (those are pre-existing user work, not agent output).
3. Validates every agent-introduced path is under the knowledge directory OR is the exact inbox item (the one allowed move target).
4. On isolation violation, reverts all agent-introduced changes via `git checkout -- <path>` / `git clean -f -- <path>`, leaves the inbox file untouched, and throws.
5. On success, stages exactly those paths (no `git add -A`) and commits with a canonical citation-derived message.

**Codex is explicitly NOT used for knowledge ingest.** Codex's role in this repository is confined to the specific workflows where it has already been wired in by name — architecture preflight (`gc_codex_architecture_preflight`) and cross-model PR review (`gc_codex_review`). Knowledge maintenance is a Claude Code responsibility by project decision, and any attempt to reintroduce codex into the ingest path should be rejected at review time. The rationale:

- Claude Code is the model family the team already trusts for day-to-day coding work, and knowledge ingest is day-to-day coding work applied to markdown instead of source code.
- Claude Code's headless mode (`claude -p`) supports tool-level allowlisting via `--allowed-tools` and directory-level access control via `--add-dir`, giving the engine finer-grained sandboxing than codex's `--sandbox workspace-write`.
- Keeping a single agent responsible for the knowledge base means the agent that writes pages is the same agent that reads them during `/implement` Step 3 (consumption, issue #524) — tone, conventions, and citation style stay consistent.
- Codex remains valuable exactly where it is — as a cross-model reviewer and architecture preflight. Mixing it into maintenance work dilutes that role.

The default invoker is `defaultIngestAgent` in `knowledge_ingest.js`. It shells out with:

```
claude --print
       --add-dir <repoRoot>
       --permission-mode bypassPermissions
       --allowed-tools "Read Edit Write Bash(git status:*) Bash(git mv:*) Bash(mkdir:*)"
       --model sonnet
       --output-format text
```

The prompt is piped via stdin (through `execFileWithInput` in `lib.js`) rather than passed as a positional argument, because `--print` mode waits 3 seconds for stdin before falling back to the positional prompt. Writing the prompt directly onto the child's stdin eliminates the false-positive warning and starts the model call immediately.

Flag rationale:

- `--print` runs Claude Code non-interactively: read prompt, execute, exit.
- `--add-dir <repoRoot>` grants tool-layer access to the target repository only.
- `--permission-mode bypassPermissions` is required for unattended operation (no interactive confirm prompts). The blast radius is bounded by the tool allowlist and `--add-dir` scoping above, and the engine validates commit isolation after the agent returns, so an over-permissioned subprocess cannot silently poison state outside the knowledge tree.
- `--allowed-tools "Read Edit Write Bash(git status:*) Bash(git mv:*) Bash(mkdir:*)"` is the minimum set required to read the wiki, edit pages, and rename the inbox item. No `WebFetch`, no free-form `Bash`, no `Task` delegation.
- `--model sonnet` is sufficient for ingest decisions at a significantly lower cost than opus; operators can override via the `agentOverrides` parameter.

**Why `--bare` is NOT used:** `--bare` restricts Anthropic authentication to `ANTHROPIC_API_KEY` or an `apiKeyHelper` configured via `--settings`. It explicitly refuses to read OAuth / keychain credentials. Operators who log in interactively with `claude login` (the common case for this repository) use OAuth session credentials; a bare-mode subprocess would fail with "Not logged in · Please run /login" even on an otherwise-working machine. The non-bare default lets the subprocess inherit the same OAuth session the operator uses interactively.

**Subprocess env handling:** when Claude Code spawns another Claude Code instance as a subprocess, the parent passes `ANTHROPIC_API_KEY` through its environment automatically. `claude` prefers that env var over the OAuth session file, which means the subprocess would use whatever API key happens to be in the parent's env — not the session the operator logged in with. `defaultIngestAgent` strips `ANTHROPIC_API_KEY` from the child env before spawning so the subprocess falls back to the session credential file, matching the auth path an interactive shell would use. Operators who want a dedicated API-key path for ingest runs (separate from their interactive session) can set `GC_KNOWLEDGE_INGEST_ANTHROPIC_API_KEY` in their environment; when present, it is passed through as `ANTHROPIC_API_KEY` for the subprocess only.

This mirrors how `runCodexArchitecturePreflight` already delegates "read context + write files" to a sandboxed CLI subprocess and inspects the worktree afterwards — the pattern is the same, only the CLI binary and flag shape differ. The engine is trivially mockable for unit tests via the `ingestAgent` DI parameter.

The agent invocation returns a structured tail line (`INGEST_RESULT={"action":"create|update","page":"...","citations_added":n}`) that the engine parses with `parseIngestResultTail`. Missing or malformed tails are treated as ingest failures.

### 3. Interprocess serialization via `proper-lockfile`

Node's stdlib does not expose `flock(2)`, and a per-process mutex is insufficient — `gc_remember` spawns a detached subprocess, so the lock holder is a different OS process from the MCP server that issued the spawn. A future scheduled sweep and a future admin CLI both add more processes that need to serialize through the same mechanism.

We use `proper-lockfile` (5M+ weekly downloads, zero native deps) with:

- `stale: 60_000` — lock is reclaimable if its mtime hasn't been refreshed in 60 s (covers crashed holders).
- `update: 10_000` — the active holder refreshes the lock mtime this often while work is in progress.
- Retry policy configurable per call. The `acquireKnowledgeLock` helper defaults to `retries: 0` (fail-fast) so administrative tools can report contention cleanly. `runIngest` passes a bounded exponential retry (`{retries: 15, factor: 1.5, minTimeout: 100, maxTimeout: 2000}`, ~20 s total wait) so two rapid captures queue up instead of rejecting.

The lock identity is the `realpath` of the knowledge directory, not the raw `repo_path` string the caller passed in. Different path spellings (symlinked checkouts, `../` variants) that point at the same inode contend on the same lock. The lockfile itself is stored as `.gc-lock` inside the knowledge directory so `rm -rf <repo>` cleans it up automatically on test teardown.

Alternative considered: `fs.mkdirSync` with a lock directory plus hand-rolled stale detection. Zero deps but requires ~80 lines of pid-check and retry-loop code, re-inventing exactly what `proper-lockfile` already does correctly. Not worth it.

### 4. Strict commit isolation (GC-X010)

Every ingest commit stages:
- Files under `<knowledge.dir>/<anything>`
- The exact inbox item path

No other file. Never `git add -A`. Never `--no-verify`. Never `commit.gpgsign = false` bypass.

The engine verifies this by diffing the worktree before and after the ingest agent runs, subtracting pre-existing dirty files, and asserting the remainder is contained in the allowlist. Violations trigger a revert of agent-introduced changes and leave the inbox file untouched.

Rationale: ingest runs on the same branch as ongoing feature work. A permissive stage would silently absorb whatever the developer had in-flight into the "knowledge" commit, breaking the rebase/cherry-pick story and polluting the review diff. Strict isolation keeps the commit boundary clean and the rollback trivial.

### 5. No Spring backend surface in this slice

No REST controller, DTO, JPA entity, Flyway migration, graph node, domain aggregate, or exception class is added for capture / ingest. The knowledge subsystem is deliberately repo-local tooling. If a later slice needs to surface knowledge through HTTP (cross-host sweep, multi-tenant knowledge catalog), the design note's "Future direction" section covers the path, and that slice owns the decision to promote the model.

## Consequences

### Positive

- **Zero new infrastructure.** No Python, no new package, no new test harness, no new dep except `proper-lockfile`. Ships in a single Node package.
- **Reuses existing path + subprocess machinery.** Every cross-cutting concern (repo-relative paths, symlink containment, subprocess plumbing, ground-control config loading) is imported from `lib.js`, not duplicated.
- **Testable without the real Claude Code CLI.** The `ingestAgent` DI parameter lets unit tests script filesystem actions in ~20 lines per test. The serialization, isolation, failure-retry, and latency invariants are all exercised in `mcp/ground-control/knowledge_ingest.test.js`.
- **Process-safe locking.** `proper-lockfile` handles the stale-lock recovery case that a hand-rolled `mkdirSync` lock would have to re-implement.
- **Strict commit isolation.** Reviewers see knowledge updates as standalone commits with a clean file list, not as noise on top of a feature PR.

### Negative

- **Deviates from the design note's Python/tools preference.** The co-location at `mcp/ground-control/` trades the "shared tooling module" aesthetic for zero premature abstraction. Issue #525's CLI decision may require a lateral move.
- **Claude Code is load-bearing for the write path.** If the `claude` CLI is unavailable (not installed, offline, out of budget), `gc_remember` still writes the inbox durably but the wiki doesn't update until a sweep (issue #526) or manual retry runs. This matches the design's "availability does not gate implementation" invariant, but operators should know the hot path degrades without the ingest agent.
- **Extra dep: `proper-lockfile`.** Small, battle-tested, but it is a new runtime dep and must be kept in sync with `npm audit`.

### Risks

- **Commit isolation false positives.** If a developer happens to have a dirty file exactly in the knowledge tree when ingest runs, the engine allows it through (per the "subtract pre-existing dirty files" rule) but then stages and commits it as part of the ingest commit. Mitigation: the engine refuses to run if pre-existing dirty files touch paths the ingest would write to. Broader "don't accidentally ingest another dev's WIP knowledge edit" is documented as an operator responsibility.
- **Stale-lock edge case.** If a crashed ingest subprocess dies exactly at the lock-refresh boundary, the `stale: 60_000` window could leave a freshly-crashed lock undetected for up to 60 seconds. Mitigation: this is a failure mode, not a correctness issue — the second ingest waits out the stale window and then proceeds. Operators running at higher throughput can tune `stale` down.
- **Prompt injection via inbox content.** A malicious or accidental inbox note could contain text that tries to redirect the ingest agent to write outside the knowledge tree. Mitigation layers: (a) the Claude Code invocation is already tool-restricted via `--allowed-tools` and directory-scoped via `--add-dir`, so only the repo is reachable; (b) the commit-isolation check runs *after* the agent returns, catches any out-of-scope writes inside the repo, reverts them, and aborts; (c) the inbox file itself is preserved so an operator can investigate.

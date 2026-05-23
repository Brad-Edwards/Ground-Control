# Agent Knowledge System — Design Note

Captured from the design discussion so we can refer back before and during implementation. This is the reasoning, not the specification. The specification lives in the requirements graph (GC-X001 through GC-X025) and the implementation order lives in `agent-knowledge-system-order.md` alongside this file.

## Problem

Coding agents learn the same lessons over and over.

Every implementation run, the agent hits a gotcha — the formatter strips unused imports, the completion gate needs these three files touched together, that repository pattern bypasses the scoped query, the test mock is asserting on the wrong thing. It fixes the problem, commits, moves on. The next run has no memory of any of it. Review comments that explained the fix sit on a merged PR nobody re-reads. Commit messages are terse. The user's chat corrections evaporate when the session ends.

We want the lessons to compound. A gotcha learned in run N should be available to run N+1 (same repo, same day, different session). Over time the repo accumulates institutional memory that makes every subsequent run shorter and sharper.

The scope of this work is that compounding loop — how we capture the lessons, how we keep them current, how coding agents consume them, how the knowledge base stays healthy without manual bookkeeping.

## What we're NOT building

- A cross-repo "everything I know" super-base. One repo, one knowledge base. Knowledge bases are never merged across repos. Each repo's knowledge is about that repo.
- A RAG system, an embedding index, or a vector database. The knowledge base is flat markdown plus a content index. Agents navigate it by greppable index + reading matched pages.
- An interactive approval flow. The agent owns the wiki. It writes, updates, refactors, files, and cross-links without asking. Humans browse via Obsidian and correct errors by direct edit (or by telling the agent next session).
- A new universal "org memory". This is agent-maintained per-repo memory. If you want cross-repo memory, that's a different layer.

## Source pattern: karpathy's LLM-maintained wiki

The pattern we're adopting comes from Karpathy's sketch of an LLM-maintained personal knowledge base (see `karpathy-gist.md` in the same directory for the source). Three layers:

1. **Raw sources** — immutable. The LLM reads but never writes. For us: commits, PR review comments, fix-review commit messages, CI log excerpts, user corrections, freeform agent notes.
2. **The wiki** — mutable, LLM-owned. A directory of markdown files: summaries, topic pages, entity pages, cross-linked. The LLM creates pages, updates them when new sources arrive, maintains cross-references, keeps everything consistent. Humans read it.
3. **The schema** — a file that tells the LLM how to maintain the wiki: conventions, page types, frontmatter, ingest/query/lint workflows. The schema is what turns the LLM from a generic scribbler into a disciplined wiki maintainer. Humans and the LLM co-evolve the schema as the domain stabilizes.

Two special files help navigation at scale:
- **`index.md`** — content-oriented catalog. Every page listed once with a one-line summary and optionally tags/date. Organized by category. The agent reads this before any ingest or query.
- **`log.md`** — chronological append-only record of what happened and when (ingests, sweeps, lint passes). Gives a timeline of the wiki's evolution.

## The hot path: real-time ingest on inbox change

The core loop:

1. An agent mid-run notices surprise: "that review comment is pointing at a thing I've seen before but can't remember".
2. It calls `gc_remember` with a short structured note and a source citation (commit SHA, PR number, review comment id, or `user-correction`).
3. The capture primitive writes the note to the repo's inbox directory as a timestamped file and triggers a detached ingest subprocess.
4. The ingest subprocess reads the inbox file, reads the current `index.md`, decides whether to update an existing page or create a new one, runs the ingest agent against the relevant context, writes the update, updates `index.md` and `log.md`, moves the inbox file to `inbox/processed/`, and commits the change to the current branch.
5. All of this happens in seconds. By the next time any agent reads the wiki — even the same agent two tool calls later — the new lesson is already there.

Key invariants:
- **Read-before-write**: ingest always consults the current wiki before writing so matching observations update existing pages rather than producing duplicates.
- **Serialization**: concurrent ingests on the same repo must not corrupt the wiki. A per-repo lock serializes writes. Cross-repo ingests run in parallel.
- **Failure leaves material in place**: if ingest crashes, the inbox file stays in `inbox/` so a later run can retry. No silent data loss.
- **VCS as the transaction log**: every ingest commits. `git log` on the wiki subtree is the authoritative history.

## The cold path: scheduled sweep

Real-time ingest only triggers when an agent calls `gc_remember`. Three things that path misses:

1. **PRs merged without agent involvement.** You merge via the GitHub UI, a teammate merges, a CI bot merges. No `gc_remember` was called, but the merged PR still has review comments, fix commits, and failure histories worth learning from.
2. **Failed real-time ingests.** Codex hiccupped, disk was full, the lock was held too long — the inbox file is still sitting there.
3. **Lint / staleness / orphan drift.** The wiki accumulates rot over time. Pages contradict each other, claims become stale, cross-references break.

The scheduled sweep handles all three. It runs on a timer (systemd user unit / launchd plist / cron), iterates the registered repos, and for each one:

- Computes "new material since the last successful sweep" via a per-repo watermark (last merged PR processed, last inbox file mtime).
- Pulls metadata for merged PRs newer than the watermark via `gh api` — review comments, fix-review commit messages, linked CI runs.
- Retries any inbox files that are still sitting in `inbox/`.
- Feeds the aggregated new material to the same ingest engine the hot path uses. One ingest-agent subprocess per item, under the same per-repo lock, serialized with any concurrent real-time ingest. The ingest agent is Claude Code (see ADR-025); codex is deliberately not involved in knowledge maintenance.
- Updates the watermark state and commits + pushes.

The sweep is the safety net, not the main road. In a repo where `gc_remember` is used diligently, the sweep rarely finds fresh material — it mostly runs the lint. In a repo where nobody uses `gc_remember`, the sweep is the only capture mechanism and the cold path carries the whole load.

A separate lint pass on its own cadence (weekly) looks for contradictions, orphan pages, stale `last_verified` dates, and unsourced claims. Staleness is manual: every page carries a frontmatter `last_verified` date; the lint pass surfaces anything older than N weeks for re-check by the agent.

## Consumption: agents read the wiki during exploration

Capture is half of the loop. Consumption is the other half. Without consumption, capture is journaling.

Every `/implement` run is instructed to consult the knowledge base during Step 3 (Explore codebase for existing coverage). The skill instruction:

> Before planning, grep `<knowledge.dir>/index.md` for entries tagged with the areas the requirement touches (module names, file paths, subsystems). Read the matched pages. Apply any gotchas or conventions they document when shaping the plan.

This turns a captured lesson into a prevented mistake on the next run. Run 1 learns the gotcha, drops it in inbox, real-time ingest files it. Run 17 reads the page during Step 3 and avoids the mistake entirely.

Two deliberate non-requirements on the consumption side:

- **No RAG / embedding infrastructure.** A flat `index.md` with one-liners is enough for hundreds of pages. Agents grep + read. This is exactly karpathy's claim about moderate-scale personal knowledge bases, and we're taking the same bet.
- **No special tool for reading.** The agent reads markdown files with its existing Read tool. No new MCP surface for queries.

## Architecture: MCP vs CLI boundary

The boundary we landed on is clean:

**MCP layer (per-project, per-session, agent-invoked):**
- **`gc_remember`** — structured inbox write. Takes `{repo_path, note, source_type, source_ref, tags?}`, appends `<inbox>/<timestamp>-<slug>.md`, spawns a detached ingest subprocess that processes the entry into the wiki, returns the file path. This is the ONLY knowledge-related MCP tool.

**CLI layer (global, cross-repo, user / scheduler invoked):**
- `gc knowledge register / deregister / list / show` — registry of monitored repos, no per-session state.
- `gc knowledge start / stop / status / restart` — scheduler lifecycle (systemd user unit / launchd / cron fallback).
- `gc knowledge sweep [--repo X | --all]` — manual one-off trigger of the scheduled processing path.
- `gc knowledge ingest-inbox [--repo X]` — manual re-processing of the inbox (same engine as real-time, different entry point).
- `gc knowledge lint [--repo X | --all]` — manual lint pass.

The per-repo ingest engine is a shared repo-local tooling module (`tools/ground_control/knowledge/ingest.*` or similar), not a Java backend service. Both the CLI entry points and the `gc_remember` subprocess call it. The MCP server does not orchestrate cross-repo work — cross-repo work is strictly the CLI / scheduler concern.

Rationale for this split:
- MCP is designed for in-session tool calls. Multi-repo orchestration and scheduling belong at a layer that doesn't require a Claude Code session to exist.
- The CLI doesn't need to speak MCP protocol for its core work — it just invokes the ingest engine directly.
- Real-time capture (`gc_remember`) and scheduled processing use the same ingest engine, so behavior is consistent across triggers.

## Config: per-repo yaml declares the knowledge base

Each repo's `.ground-control.yaml` gains a `knowledge` section:

```yaml
project: ground-control
github_repo: KeplerOps/Ground-Control
# ... existing workflow + sonarcloud sections ...
knowledge:
  dir: docs/knowledge               # relative to repo root, required when this section is present
  schema: docs/knowledge/SCHEMA.md  # optional override; default is <dir>/SCHEMA.md
  inbox: docs/knowledge/inbox       # optional override; default is <dir>/inbox
```

The yaml is authoritative — the registry only tracks "is this repo monitored"; everything else is re-read from the yaml on each sweep so configuration changes take effect without re-registering. If a repo doesn't have the section, `gc knowledge register` fails with a clear error and points at a suggested snippet (the same pattern `gc_get_repo_ground_control_context` uses for missing workflow config).

`knowledge.dir` is the only required field. `schema` and `inbox` default to sensible paths under `dir`.

Config guardrails for implementation:
- `knowledge` extends the existing `.ground-control.yaml` contract. Reuse the same parser, normalization, error-reporting shape, and suggested-snippet flow already used for repo workflow config. Do not introduce a second schema file, registry entry, or parallel config reader for knowledge-base location.
- All `knowledge.*` paths are repo-relative author input resolved against the Git repo root. Absolute paths and repo-escaping traversal (`..`) are rejected. Use one shared repo-scoped path resolver for repo-local config paths rather than open-coding separate `join(repoRoot, rawPath)` logic for each field.
- `knowledge.dir` is the single knowledge-base root. `knowledge.schema` and `knowledge.inbox` are overrides for locations inside that root, not a way to declare a second store elsewhere in the repo. `index.md` and `log.md` stay anchored under `knowledge.dir`.
- Absence of `knowledge` means "this repo has no configured knowledge base yet", not "fall back to an implicit global default". Registration and sweep flows fail clearly; implementation workflows degrade gracefully and continue without knowledge capture.
- The real-time path should reuse the resolved knowledge block returned by `gc_get_repo_ground_control_context` (or the same helper chain) and pass resolved repo / knowledge paths into the ingest subprocess. Do not make the subprocess re-open `.ground-control.yaml` through a second parser with slightly different containment or defaulting rules.

## Hot-path guardrails for implementation

- `gc_remember` owns only synchronous capture: validate the fixed input shape, canonicalize the source citation once, append an immutable inbox file, trigger ingest, and return the stored location. It does not read `index.md`, choose a target page, deduplicate content, or write wiki files directly.
- `source_type` is not a second citation vocabulary. Its allowed values must match the source-citation prefixes documented in `docs/knowledge/SCHEMA.md`, and one canonical formatter / validator should turn `{source_type, source_ref}` into the citation string reused in inbox payloads, page frontmatter, `log.md`, and commit messages.
- Success for the synchronous MCP call means "the inbox entry was durably written", not "the wiki ingest finished". If subprocess launch fails after the write, the inbox file stays in place for retry and the failure is surfaced without deleting or rewriting the source material.
- The detached ingest contract is item-addressed, not inbox-scanning. `gc_remember` passes the exact inbox file path plus the already-resolved repo / knowledge paths into the subprocess, and the subprocess processes that specific item. Do not have the child rescan the inbox for "the newest file", and do not tie correctness to the parent MCP call staying alive after spawn.
- Source lifecycle is success-only: the ingest engine may archive or delete the inbox item only after the wiki update, `index.md` / `log.md` update, and git commit all succeed. Failed attempts leave the original bytes at the same inbox path so later runs can discover and retry the item automatically; do not quarantine failures under `inbox/failed/`, rename them out of the inbox pre-commit, or mutate the file to record retry state.
- The per-repo lock covers the whole ingest transaction: read current wiki state, decide update-vs-create, write page / `index.md` / `log.md` changes, move the inbox file, and create the git commit while holding the same lock. Locking only the final file write is insufficient.
- The lock must be an interprocess file lock owned by the shared ingest engine, not an in-memory mutex in the MCP server. `gc_remember`, manual inbox retry, and the future scheduled sweep all need to serialize through the same mechanism even when they run in different OS processes.
- Lock identity comes from the canonicalized repo / knowledge-base path returned by the existing config-resolution chain, not from the raw `repo_path` string the caller supplied. Different path spellings or symlinked checkout roots must still contend on the same lock.
- Git commit isolation is strict. Ingest stages and commits only files inside the knowledge base plus the specific inbox item being processed. Never `git add -A`, never include unrelated working-tree changes, and fail safely if pre-existing edits in the knowledge tree would be overwritten.
- GC-X010 commit messages come from one canonical formatter and must identify the integrated source material directly (for example the normalized citation string), not from ad hoc per-call wording. The same canonical source formatter used for page citations should drive the commit-message fragment so inbox payloads, `log.md`, and git history cannot drift in terminology.
- "Active branch at processing time" means the current symbolic branch name of the checkout that owns the knowledge base. If the repo is in detached HEAD, an unborn branch state, or any other non-branch checkout, ingest fails clearly and leaves the inbox item in place for retry; it must not invent a fallback branch, auto-checkout another ref, or silently skip the commit.
- GC-X011 latency must be measured on the real write path, not inferred from process launch. The canonical measure is capture timestamp to successful knowledge-base commit completion for that inbox item; emit it in structured logs (including source citation / inbox path) so "available within seconds" is observable and testable under normal conditions.
- Do not silently bypass repo-native commit policy with `git commit --no-verify` just to keep ingest fast. If existing hooks or signing requirements need a knowledge-commit carve-out, make that carve-out explicit in repo-native policy / hook logic so the behavior is auditable and consistent between manual and automated commits.
- This slice stays out of the Spring backend product model. No REST controller, DTO, migration, graph node, or domain aggregate should be added for capture / ingest unless a later requirement explicitly moves knowledge into the server-backed product surface.

## Invariants worth stating explicitly

- **One repo, one knowledge base.** Each repo's knowledge is about that repo. Knowledge bases are never merged across repos. A cross-repo super-base is explicitly out of scope.
- **Repo is the source of truth.** The knowledge base lives in the repo (under `docs/knowledge/` or wherever `knowledge.dir` points). Obsidian opens the repo directory as a vault — no import, no sync. Git is the version history. Branches inherit the wiki state of their parent.
- **Commits happen on the current branch.** Real-time ingest commits to whatever branch the repo is on at the time. That's a feature: the knowledge update lands in the same PR as the code that taught us the lesson, so the reviewer sees both. The scheduled sweep uses its own branch to avoid mixing with unrelated feature work.
- **Failures never lose source material.** A failed ingest leaves the inbox file in place; the next run retries. A failed sweep advances no watermark.
- **Knowledge system availability does not gate implementation.** If the knowledge layer is down or misconfigured, `/implement` still completes. Capture is supplementary. Degradation is silent.

## Phasing

Five ordered phases. Each phase produces usable value on its own.

### Phase 1 — Foundation & capture

Config schema, knowledge base structure, schema file conventions, source citation format. `gc_remember` MCP tool with synchronous inbox write and detached ingest subprocess. Processing semantics (consistency, serialization, failure retry, VCS commits). Real-time promptness requirement.

Consumption from day 1: `/implement` Step 3 instructed to grep `index.md` and read matched pages. Wiki stays navigable via flat markdown. No RAG.

Outcome: one repo can capture, process, and consume gotchas end-to-end in real time. No scheduling, no CLI, no cross-repo.

### Phase 2 — Workflow integration

`/implement` instructed to call `gc_remember` during the review loop and after user corrections. Graceful degradation when the knowledge system is unavailable.

Outcome: the habit of capture is baked into every run. The hot path is load-bearing for any repo running `/implement`.

### Phase 3 — Administration & registry

`bin/gc knowledge register / deregister / list / show`. `bin/gc knowledge sweep [--repo X | --all]` as a manual trigger. Registry file format (per-repo watermark state separated from the flat registry).

Outcome: multi-repo support. Users can enumerate and inspect monitored repos. Manual sweep works standalone.

### Phase 4 — Scheduled sweep

`bin/gc knowledge start / stop / status / restart` wiring a systemd user unit / launchd plist / cron fallback. Incremental processing against the watermark. PR metadata extraction (cold-path capture of lessons not flagged via `gc_remember`). Retry of failed inbox files. Idempotency invariant.

Outcome: cross-repo, unattended capture. One cron job for N repos. Catches the cold path.

### Phase 5 — Lint

Scheduled lint pass on its own cadence. Contradictions, orphan pages, stale `last_verified` dates, unsourced claims. Separate cron.

Outcome: knowledge base stays healthy without manual curation.

## Future direction (not in scope for this work)

- **Graph integration.** Knowledge entries become first-class Ground Control graph nodes (`KNOWLEDGE_ENTRY` entity type) with edges to the requirements, PRs, commits, and files they came from. The markdown wiki becomes a projection of the graph. Queryable alongside requirements and ADRs via the existing graph traversal. Enables "show me every gotcha tied to any requirement in the H-wave" as a one-liner.
- **Server-hosted registry and scheduler.** Local JSON registry migrates to the GC backend. The sweep worker still runs on a host with repo access, but the registry and run history live in GC's Postgres. Enables multi-host / team deployment.
- **Auto-verification of claims.** Lint pass grows the ability to grep source against the claims on a page, automatically confirming or flagging. Only works for the subset of claims that encode machine-checkable assertions.

None of these are on the critical path. They are directions that become easier once the core loop is in place.

## Key decisions and their rationale

| Decision | Alternatives considered | Why this |
|---|---|---|
| Real-time ingest on inbox write, not at end-of-run | Batch at end of `/implement`; scheduled only | Next run benefits immediately. Lessons with the shortest half-life get the shortest latency. |
| Agent owns the wiki end-to-end, no human-in-the-loop approval on ingest | Propose-and-accept flow | The karpathy pattern works because the agent is a disciplined maintainer. Friction at ingest kills the habit. Corrections happen by direct Obsidian edit or next-session instruction. |
| Flat markdown + `index.md`, no RAG | Embedding index, vector DB | Moderate scale makes flat indexing adequate. Zero infra, zero dependency. Matches karpathy's explicit choice for the same reason. |
| Knowledge base per repo, never merged | Cross-repo unified knowledge | Repos have different conventions, vocabularies, and lifecycles. Cross-repo merging is a different problem layer. |
| Sweep is global, MCP is per-project | Put sweep in MCP (`gc_knowledge_sweep`) | MCP tools are in-session per-repo. Multi-repo orchestration and scheduling don't fit that shape. Sweep lives in the CLI. |
| One `gc_remember` MCP tool, nothing else | Multiple MCP tools for capture, ingest, query, etc. | Capture is the only per-session, per-project agent action. Ingest is always async. Query is grep + read. No need for more MCP surface. |
| Per-repo ingest engine is shared repo-local tooling module | Duplicate engines in MCP and CLI; or invoke MCP from CLI | MCP protocol is wrong for batch work. Duplicating is worse than sharing. Keep one engine under repo-local tooling and let both entry points call it. |
| Real-time and scheduled paths use the same engine | Separate engines | Consistency of behavior. Same lock, same commit semantics, same failure modes. |
| Scheduler is systemd user unit / launchd plist / cron, not a daemon | Long-running daemon with IPC | Zero infra. Reboot-safe. Nothing to maintain. Timer fires the CLI, CLI runs, exits. |
| Registry lives in local JSON (v1) | Ground Control Postgres (v2 direction) | v1 is one user, one machine. GC-backed registry is the migration target for multi-host. |
| Git commits land on the current branch for real-time ingest | Dedicated knowledge branch per ingest | The lesson lands in the same PR as the code that taught us the lesson. The reviewer sees both together. |
| Every claim on a knowledge page requires a source citation | Unsourced claims allowed | Lint pass rejects unsourced claims. Traceability makes the wiki auditable and kills drift between claims and reality. |
| Knowledge availability never blocks `/implement` | Fail closed | Capture is exhaust, not a gate. `/implement` must still finish if the knowledge layer is down. |

## Open decisions not blocking the design

These are logistics, not architecture. They get decided when we start implementing the relevant phase.

- Default sweep cadence (my vote: daily at 03:00 local, configurable at `gc knowledge start`).
- Default lint cadence (my vote: weekly).
- Default commit-and-push behavior for the scheduled sweep — push to a dedicated branch per run or directly to the current working branch.
- Where the sweep config / registry actually lives in v1 (local XDG path vs a fixed location in the repo).
- Whether the `/implement` consolidation step merges with the real-time path or stays a separate explicit moment.

---
title: Ground Control Knowledge Base Schema
tags: [meta, schema]
sources:
  - issue:522
  - pr:528
last_verified: 2026-04-12
---

# Ground Control Knowledge Base Schema

This file is the authoritative, agent-readable description of how the repo
knowledge base at `docs/knowledge/` is structured and maintained. Agents
working in this repository consult this schema before writing new pages or
updating existing ones.

The knowledge base design that these conventions implement lives at
`docs/notes/agent-knowledge-system-design.md`. That note explains *why* the
knowledge base exists. This file is the *operating manual*.

## Purpose and boundaries

- **Per-repo, agent-maintained.** The knowledge base is authoritative for
  this repository only. It captures lessons learned while working in this
  codebase: gotchas, conventions, sharp edges, fix recipes, workflow rules
  that are not already enforced by tooling.
- **One knowledge base per repository, never merged across repositories**
  (GC-X003). Cross-repository knowledge is a separate concern and does not
  belong here. Do not copy pages from another repo's knowledge base into
  this one.
- **Flat markdown, no retrieval infrastructure** (GC-X013). Pages are plain
  markdown and are navigated by grepping `index.md` and reading matched
  files. There is no vector store, no embedding index, no RAG service, and
  no query tool beyond ordinary text search and `Read`. Anything that would
  require more than grep + read is out of scope for this knowledge base.
- **The agent owns the wiki end-to-end.** Humans read via Obsidian or any
  markdown viewer. Corrections happen by direct edit or by telling the agent
  during the next session. There is no interactive approval flow for new
  pages.

## Structure

```
docs/knowledge/
├── SCHEMA.md       # this file — conventions and contracts
├── index.md        # flat content catalog; every page listed with a one-line summary
├── log.md          # append-only chronological record of what the agent did and when
├── topics/         # cross-cutting topic pages (created on demand)
├── entities/       # pages about concrete code entities (classes, modules, endpoints)
├── gotchas/        # short pages documenting a specific pitfall and its resolution
└── inbox/          # (created by a later slice) staging area for raw captures
```

Category directories (`topics/`, `entities/`, `gotchas/`) are created the
first time a page of that category is written. Do not pre-create empty
category directories.

### `index.md` contract

`index.md` is the content-oriented catalog (GC-X004). It lists every page in
the knowledge base exactly once, grouped by category, with a one-line
summary and optionally tags. Before creating a new page, an agent MUST read
`index.md` and check whether an existing page already covers the same
topic — if so, update the existing page rather than creating a duplicate
(read-before-write invariant from the design note).

### Ingest consistency contract

When ingest integrates a new observation into the wiki (GC-X007), it treats
an existing page's current file path as that page's identity. If the
observation refines or extends a page already represented in `index.md`,
ingest updates that file in place rather than creating a second page with a
new slug, source-specific suffix, or timestamped variant.

`index.md` is the discovery shortlist, not the whole decision. Ingest reads
the candidate pages themselves before deciding whether the observation
belongs on one of them or truly requires a new page.

When updating an existing page, ingest uses the current page contents as the
base document. Preserve existing frontmatter, source coverage, and
cross-references unless the new evidence explicitly corrects them. The goal
is to refine the current page, not re-summarize the topic from scratch.

Renames, splits, and merges are exceptional maintenance operations, not the
default ingest path. If one is truly required, update `index.md` and any
affected intra-wiki links in the same transaction so the knowledge base
never points at stale paths.

### `log.md` contract

`log.md` is the append-only chronological record of knowledge-base activity
(GC-X004). Each significant event (ingest, sweep, lint pass, manual
correction) appends a bullet with an ISO date, a short description, and the
source citation for the change. `log.md` is never rewritten — only appended
to. `git log` on this subtree is the authoritative deeper history.

### `inbox/`

The inbox is a staging area for raw captures from the capture primitive
(`gc_remember`). It does not exist yet — it is created by a later slice of
the agent knowledge system rollout (issues 2–6). Until then, do not write
to `inbox/`.

When the inbox ships, each inbox file is the retry surface for ingest. A
failed ingest leaves the source file's bytes untouched at its inbox path so
later real-time, manual, or scheduled processing can retry it
automatically. Do not move failed items to a `failed/` directory, rename
them out of `inbox/`, or mutate the file to track retry state.

An inbox file may be archived under a success-only location such as
`inbox/processed/` or deleted only after the full ingest transaction has
succeeded: page update or creation, `index.md` update, `log.md` append, and
the git commit that records the change.

## Content page types

Content pages live under one of the category directories and fall into one
of the following types. Each type has a concrete purpose; do not invent new
types ad hoc.

- **Topic pages** (`topics/<kebab-case-name>.md`). Cross-cutting subjects
  that span multiple files and entities — e.g. "how migrations interact
  with audit tables", "the shape of our Envers setup", "what happens during
  `/implement` step 7". Use when a gotcha applies broadly and is not tied
  to a single entity.
- **Entity pages** (`entities/<kebab-case-name>.md`). Focused on a concrete
  code entity: a class, a module, a REST endpoint, a migration family, a
  configuration file. Document invariants, surprising behavior, and the
  relationships an agent needs before modifying the entity.
- **Gotcha pages** (`gotchas/<kebab-case-name>.md`). Short, tightly scoped
  notes documenting a specific pitfall, the symptom that revealed it, and
  the resolution. A gotcha page should be readable in under a minute and
  should leave a future agent able to avoid the same trap.

## Frontmatter

Every content page (and this schema) starts with YAML frontmatter. Required
and optional fields:

| Field           | Required | Value                                                                 |
| --------------- | -------- | --------------------------------------------------------------------- |
| `title`         | yes      | Human-readable title, usually matching the H1.                        |
| `tags`          | yes      | List of tags used by `index.md` to support grep-based discovery.      |
| `sources`       | yes      | Non-empty list of source citations — see "Source citation rule".      |
| `last_verified` | yes      | ISO date of the last time a human or agent checked this page is true. |

Additional fields are permitted but should be documented here before they
are adopted in multiple pages.

## Source citation rule (GC-X005)

Every claim on a knowledge page MUST cite at least one source. Pages with
unsourced assertions are invalid and will be flagged by the lint pass when
the lint slice ships. Valid source citation shapes:

- `commit:<short-sha>` — the claim was learned from reading or writing a
  specific commit.
- `pr:<number>` — the claim was learned from a pull request, review, or its
  merge.
- `review:<comment-id>` — the claim came from a specific review comment.
- `issue:<number>` — the claim is about an issue or was captured while
  working an issue.
- `ci:<run-id>` — the claim came from a specific CI run (typically a
  failure mode).
- `user-correction:<short-description>` — the claim came from an explicit
  user correction during a session; the short description anchors the
  correction in the session context.
- `file:<relative-path>` — the claim is verifiable by reading a specific
  file in the current repository state. Use this only when the claim is
  a direct reading of committed code and the file is stable enough to
  cite.

The `sources` frontmatter field lists the citations that back the page as a
whole; inline citations inside the page body are encouraged for
claim-by-claim attribution when the sources are heterogeneous.

Claims that cannot be cited do not belong on a knowledge page. If an agent
notices itself writing an unsourced claim, it should either find a source
or drop the claim.

## Naming conventions

- Content page filenames: `kebab-case.md`. No spaces, no CamelCase, no
  underscores.
- Inbox filenames (created by a later slice): `YYYY-MM-DDTHH-MM-SS-<slug>.md`
  so chronological ordering falls out of `ls`.
- Category directories are always lowercase.

## Navigation convention (GC-X013)

To find information about topic X, an agent:

1. Reads `index.md` and greps for tags/summaries related to X.
2. Reads the matched pages.
3. If no page matches and the question is relevant, creates a new page
   following the rules above.

There is no query tool, no embedding search, no retrieval service. Grep +
read is the whole interface. If grep + read is not enough at the current
scale, the right fix is to improve `index.md` entries, not to add retrieval
infrastructure.

## Invariants summary

- One knowledge base per repository; knowledge bases are never merged
  across repositories (GC-X003).
- `index.md` lists every page; duplicates are a bug (read-before-write).
- `log.md` is append-only.
- Every claim cites a source (GC-X005).
- Flat markdown; no RAG, no vector store, no embeddings, no retrieval
  service (GC-X013).
- Schema and conventions evolve through normal PRs to this file; co-evolved
  with the agent that maintains the wiki.

---
id: GC-O015
title: "Maintainer Pull Request Review Workflow"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-08-19T00:00:00Z
updated_at: 2026-08-19T00:00:00Z
---

# GC-O015 — Maintainer Pull Request Review Workflow

## Statement

The system shall expose a maintainer-oriented pull-request review workflow that reviews an existing pull request — which may have been authored outside Ground Control — read-only by default, remediates it only under explicit user authorization, and closes the issues it directly delivered only after merge, while preserving the GC-O007 / ADR-029 contract that pull-request merge remains the single human touchpoint and that a read-only maintainer review creates no issue-thread record.

(a) Read-only findings-first review. Accept a repository checkout and a pull-request number and return an evidence-backed review through the invoking interface. The read capability shall be bound to the immutable MCP launch checkout and its origin identity, so it cannot be pointed at another repository the server process can reach. The review shall read the actual changed files and the repository's architecture records (linked issues, repository guidance, relevant ADRs) rather than the pull-request or issue summary, and shall assess the premise, issue coverage, ADR and convention consistency, safety, security, correctness, compatibility, operational behavior, maintainability, and verification freshness. It shall report material findings before minor ones, distinguish merge blockers from reasonable follow-up and accepted tradeoffs, give a clear merge recommendation, and name any stale or missing verification. The evidence snapshot shall include the pull-request body (the stated premise, bounded) and unresolved-discussion evidence, and every omission that could not be read — truncated or absent patches, an unavailable required-check set, a truncated body, or unavailable discussion threads — shall be reported as incomplete, never as a clean recommendation.

(b) No pre-authorization mutation. Before an explicit remediation authorization, the workflow shall perform no repository or GitHub mutation and shall post no pull-request or issue comment, review, label, or marker. The read-only review shall create no issue-thread record, durable marker family, or local workflow-state file (ADR-029 issue #1535 amendment).

(c) Authorized same-checkout remediation. Explicit user authorization may move the workflow into remediation on the existing pull-request branch in the invocation checkout. Because a model-supplied string is not proof of user intent, the mutation shall be gated by a trusted-host confirmation the model cannot forge: a designated approval label, applied to the pull request by an account with write (or higher) repository permission and applied at or after the reviewed head commit, verified server-side before any mutation; the lane shall expose no capability that applies a label, and a stale label predating the current head shall be refused. The `authorization` value is the user's conversational change request relayed by the driver, recorded as human-readable intent, not as the proof. In addition the mutation boundary enforces the reviewed pull-request identity, re-validated against the live pull request by object id at each boundary; remediation is limited to same-repository pull requests (a cross-repository fork pull request is refused rather than pushed to an unverified destination) whose base matches the configured integration branch (a mismatch is a consultation stop); the server stages the working tree itself (the skill runs no `git`) so the pushed commit is exactly the tree the maintainer produced; and the push is a non-force push to the same branch bound to the reviewed remote head, re-fetching the base immediately before the push. It shall update a stale branch by a real merge of the freshly fetched integration branch and shall never rebase, reset, squash, force-push, create a worktree, rewrite contributor history, or auto-select a conflict side. The workflow shall NOT execute the repository's configured gate commands against the contributor-controlled checkout in the privileged server host; verification of the pushed change is the pull request's own continuous integration, which runs the commit in an isolated environment and is surfaced by the read-only context tool.

(d) Communication policy. Repository-visible text shall be succinct, technical, and neutral. The workflow shall post no comment during the read-only review. It may post one succinct, neutral, technical pull-request comment describing the change and its rationale only after a successful authorized push, proven server-side against the live pull-request head; an identical retry shall not duplicate the comment. It shall post no review prose or issue comment, and take no other repository-visible action, unless the user separately and explicitly requests it. The exact posted text shall pass secret, reserved-marker, and body-size scrubbing.

(e) Failure semantics. Cross-repository (fork) pull requests, wrong or detached checkouts, dirty trees, moved remote heads, moved bases, verification-tree mismatches, and failed gates shall be stable, non-mutating structured outcomes that the workflow reports rather than works around. A comment failure after a successful push shall be reported as a partial outcome and shall not re-push or duplicate a comment.

(f) Post-merge issue closure. After merge is confirmed (state MERGED and a non-null merge timestamp), the workflow shall close only the open issues the pull request directly delivered, one explicitly selected issue at a time, through the existing merge-gated close path (`gc_close_issue_after_merge`). It shall leave partially delivered issues and broader parent or tracking epics open. A cross-reference, shared label, milestone, parent link, or mention in prose shall not by itself make an issue eligible for closure.

(g) Boundary reuse, no duplication. The workflow shall be a repository-bound MCP capability set: the skill shall never invoke `gh`, `git`, or `curl` or handle a token directly (ADR-027). It shall reuse the existing repository-identity, configuration, safe-Git-execution, verification, GitHub-read, public-text-scrubbing, telemetry, and issue-close boundaries rather than duplicate a GitHub client, configuration parser, check poller, secret scanner, telemetry schema, or issue-close path.

(h) User-owned merge and no automatic authority. The workflow shall never approve, enable auto-merge, queue, merge, close, or relabel the pull request. A clean review recommendation is evidence-backed advice and shall never be converted into automatic merge authority.

(i) Documentation. The workflow shall be documented in the development workflow reference and the workflow reference, including the read-only default, the explicit remediation authorization, the communication policy, the post-merge closure rule, and the list of operations that remain off-limits to agents (notably merge).

## Rationale

Maintainers repeatedly restate the same review contract when evaluating contributor pull requests, and the existing lanes do not fit: `/implement` and `/quickfix` author a change under the ADR-029 issue-thread record, and `/integrate` prepares a queue of already-approved pull requests. A maintainer review of a single contributor pull request — often opened outside Ground Control — needs a read-only default that produces no durable record, an explicit and identity-bound remediation gate so a review finding never silently mutates a contributor's branch, and a merge-gated, directly-delivered-only issue closure so Ground Control state never runs ahead of shipped code. Keeping every side effect inside the repository-bound MCP server (ADR-027) makes the lane agent-neutral across Claude Code, Codex, and Cursor and keeps privileged `gh`/`git` access and public-text scrubbing on the one enforcement boundary that protects every driver. Preserving contributor history through a real merge (never a rewrite) and keeping merge a human touchpoint (GC-O007 / ADR-029) protect the trust relationship with contributors.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib/pr-review-context.js` (Read-only PR review context reader — GC-O015 clauses (a), (b))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib/pr-review-remediate.js` (Authorized same-checkout remediation dispatcher + sync_base — GC-O015 clauses (c), (e), (h))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib/pr-review-remediate-publish.js` (Remediation publish + neutral PR comment — GC-O015 clauses (c), (d), (e))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib/pr-review-shared.js` (Shared validators, caps, and gh helper — GC-O015 clause (g))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib/pr-review-confirm.js` (Trusted-host remediation confirmation via a write-permission approval label — GC-O015 clause (c))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/tools/pr-review.js` (MCP tool registrations gc_get_pr_review_context + gc_remediate_pull_request)
- IMPLEMENTS → DOCUMENTATION `skills/review/SKILL.md` (Maintainer PR-review skill — prose contract for the lane)
- IMPLEMENTS → GITHUB_ISSUE `1535` (Issue #1535: Add a maintainer-oriented pull request review skill)
- CONSTRAINS → ADR `architecture/adrs/027-ground-control-yaml-context-contract.md` (ADR-027: the MCP server owns privileged gh/git side effects; the skill never runs them)
- CONSTRAINS → ADR `architecture/adrs/029-issue-thread-gate-model.md` (ADR-029: read-only maintainer review creates no issue-thread record; merge stays the single human touchpoint)
- TESTS → TEST `mcp/ground-control/pr-review-context.test.js` (Read-only context tool tests: read-only invariant, diff completeness, linked-issue distinction)
- TESTS → TEST `mcp/ground-control/pr-review-remediate.test.js` (Remediation tests: authorization, identity binding, fork/branch access, stale-base, publish, comment suppression)

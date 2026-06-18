# Test-Quality Review Engine - gc_test_quality_review

How `/implement` Step 13 actually runs the test-quality review, end to
end. This is the durable mechanism doc - the SKILL.md prose, ADR-029,
and `docs/DEVELOPMENT_WORKFLOW.md` cross-reference back to this note.

## Why this exists (the Skill→MCP migration)

Issue #884 v1 fixed the original "Step 13 halts on a clean review" bug
by mandating a `gc_post_decision_record(reviewer: "test-quality",
findings: [])` post at the end of every cycle. The clean record's
successful post became the structured advance-to-Step-14 signal.

That fix worked for the clean case. It did NOT work for the
findings-present case. After v1 shipped, the user observed the same
underlying failure - the agent kept echoing the review findings back
to the user as a status report and stopping, instead of fixing them in
the same agent turn. The cause was not prose-level: the SKILL.md
explicitly said "do not echo, fix in the same turn." The cause was the
tool boundary itself.

When `/implement` invoked `Skill("review-tests")`:

1. The child skill ran in a sub-context and returned prose-formatted
   findings (`**[CRITICAL] file::TestClass::test_method** ...`).
2. The parent agent's autoregressive continuation after a Skill-return
   defaulted to "received a result, summarize and respond to the user."
3. The SKILL.md "do not echo" rule was in the prompt context but lost
   against the much stronger proximate signal that a child skill had
   just produced report-shaped output.

`gc_codex_review` does not have this problem in the same workflow.
Both reviewers run inside the same /implement run; codex review's
post-cycle behavior is reliably "fix in-turn, re-invoke" because
`gc_codex_review` returns a structured envelope with a `next_action`
field. The agent reads `next_action: "fix_all_findings_and_restage"`
as a directive, not a status report. The tool boundary determines the
behavior; the prose cannot override it.

The v2 fix replaces `Skill("review-tests")` with `gc_test_quality_review`,
an MCP tool returning the same envelope shape `gc_codex_review` uses.

## Tool invocation contract

```js
gc_test_quality_review({
  repo_path:    string,           // required
  base_branch?: string = "dev",   // git diff base for changed-test discovery
  issue_number?: int,             // overrides numeric-branch-prefix derivation
  pr_number?:   int,              // passed through to the envelope
  override_cap?: bool,            // cycle hardCap+1 onward requires this
  override_reason?: string,       // required when override_cap=true
  model?:       string,           // defaults to claude-sonnet-4-6
})
```

Return envelope:

```js
{
  ok: true,
  issue_number: <int>,
  branch:       <string>,
  pr_number:    <int|null>,
  cycle:        <int>,           // this cycle (1-based)
  cap:          3,               // hard cap; matches codex review cap
  finding_count: <int>,
  findings: [
    {
      severity:        "critical" | "warning",
      location:        "<file>::<TestClass>::<test_method>" or "<file>:<line>",
      problem:         <string>,
      why_it_matters:  <string>,
      fix:             <string>,
    },
    ...
  ],
  next_action: "fix_findings_and_reinvoke"                       // findings, in-cap
            | "post_clean_decision_record_and_advance_to_step_14" // clean
            | "fix_findings_then_summarize_and_escalate"          // last in-cap cycle
            | "post_summary_and_escalate_to_user",                // cap refused
  findings_comment_url: <string>,  // permalink to the durable record
  changed_test_files:   [<string>],
  override:        bool,
  override_reason: string|null,
}
```

Error envelopes (`ok: false`) carry a structured `error` code plus
`message` and (where relevant) `next_action`. The codes are listed
under "Failure modes" below.

## End-to-end mechanism

```
+--------------------+
|  /implement Step 13|
+--------------------+
         |
         | gc_test_quality_review(repo_path, issue_number)
         v
+--------------------------------------------------+
| MCP server runTestQualityReview():                |
|   1. resolve repo + branch + (issue|branch-prefix)|
|   2. count prior test-quality cycle markers       |
|      on the issue thread; refuse cycle 4 unless   |
|      override_cap                                 |
|   3. `git diff --name-only origin/<base>...HEAD`  |
|      filtered to test files                       |
|   4. build prompt (canonical rubric + file list)  |
|   5. shell out:                                   |
|        claude --print                             |
|               --model claude-sonnet-4-6           |
|               --output-format json                |
|               --json-schema '<findings schema>'   |
|               --add-dir <repo>                    |
|               --permission-mode bypassPermissions |
|               --allowedTools "Read Glob Grep"     |
|        with prompt on stdin                       |
|        with ANTHROPIC_API_KEY stripped from env   |
|   6. parse JSON envelope -> findings[]            |
|   7. post durable findings record to issue thread |
|   8. post cycle marker to issue thread            |
|   9. return envelope                              |
+--------------------------------------------------+
         |
         v
+--------------------+
| /implement reads   |
| next_action field  |
| as a directive     |
+--------------------+
```

## Authentication and the env-var strip

The exec wrapper strips `ANTHROPIC_API_KEY` from the subprocess env
before launching `claude --print`. This is intentional:

- The host environment may have `ANTHROPIC_API_KEY` set (for example, from a
  shell profile, a previous integration, or a CI runner).
- When `claude` sees that env var, it uses it preferentially over the
  OAuth session the host human signed into.
- The env-var-anchored account may have a different billing balance
  than the OAuth account - in practice, the env-var account is often
  empty (set up but never funded), while the OAuth account is the one
  the user actually uses.
- Stripping the env var forces `claude` onto the OAuth session - the
  canonical user-driven auth path. The same credentials power the
  /implement parent run; the test-quality review sub-call routes
  through the same billing account.

Concretely the wrapper is:

```js
const childEnv = { ...process.env };
delete childEnv.ANTHROPIC_API_KEY;
await execFileWithInput("claude", args, { input: prompt, env: childEnv, ... });
```

Operator implications:

- The host running the MCP server (which is the same host running
  /implement, since the MCP server is a local subprocess) must be
  logged in to `claude` via OAuth. Run `claude login` once on the
  host; the credentials persist.
- If an operator genuinely wants a separate billing account via
  `ANTHROPIC_API_KEY`, they must remove the env-var strip in
  `runSingleClaudeTestQualityReview` and ensure the env-var account
  has credits.
- `--bare` mode is NOT used. `--bare` forces ANTHROPIC_API_KEY-only
  auth (per the CLI's help text: "OAuth and keychain are never read")
  which contradicts the strip. The MCP wrapper runs without `--bare`
  so OAuth applies.

## Model selection

Default model: `claude-sonnet-4-6`. Chosen by the user in #884 v2 as
the right cost/quality balance for false-assurance-test detection.

The MCP tool accepts an optional `model` parameter; pass a different
model alias (`claude-haiku-4-5`, `claude-opus-4-8`, etc.) or full ID
to override on a per-call basis. The /implement SKILL does not set
`model`, so it uses the default. Future per-repo configuration could
live in `.ground-control.yaml` under a new `review.test_quality.model`
key, but is not currently implemented.

## Cycle cap mechanism

Server-side hard cap: 3 cycles per issue. Anchored to the GitHub
issue resolved at Step 1 (matching codex review). The cap counter
reads `<!-- gc:test-quality-review-cycle issue="N" branch="..." cycle="M" -->`
markers from the issue thread and refuses cycle 4 unless
`override_cap=true` with a non-empty `override_reason`.

Marker family is disjoint from `gc:codex-prepush-cycle` and
`gc:decision-record` so the three counters never cross-count. Branch
is recorded in the marker for audit context only - a branch rename on
the same issue does NOT reset the counter.

Override path:

```js
gc_test_quality_review({
  ..., override_cap: true,
       override_reason: "user authorized cycle 4: <verbatim quote>",
})
```

The agent cannot self-authorize; the override_reason must quote the
user's actual authorization on the issue thread or in the
conversation.

## Findings record on the issue thread

Every cycle posts a durable Markdown record to the issue thread under
the `<!-- gc:test-quality-review-findings ... -->` marker. The record
carries the cycle number, cap, branch, finding count, and (when
non-empty) each finding's severity / location / problem /
why-it-matters / fix. The record is reviewer-controlled prose, so it
passes through `detectSensitiveBodyContent` before posting - a record
that matches the sensitive-content guardrail is refused with
`error: "test_quality_review_record_rejected"` and no cycle marker is
written, so a retry after scrubbing is free.

The cycle marker is posted as a separate comment immediately after
the findings record. Both posts are server-side via `gh api`; the
caller does not need to do follow-up `gh issue comment` calls.

## How the parent /implement workflow consumes the envelope

| next_action                                       | What Step 13 does                                                                                                                                                                                                                                                                                                                                                |
|---------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `fix_findings_and_reinvoke`                       | Classify each finding (`one-off` / `class`), apply the fix (structural at the category level for `class`), self-verify locally, stage / commit / push, call `gc_post_decision_record` with the dispositions, confirm `ok: true`, re-invoke `gc_test_quality_review` (cycle N+1). All in the same agent turn. No echo to user. No "return control to user." |
| `post_clean_decision_record_and_advance_to_step_14` | Call `gc_post_decision_record` with `findings: []`. Confirm `ok: true`. Advance to Step 14 in the same agent turn.                                                                                                                                                                                                                                              |
| `fix_findings_then_summarize_and_escalate`        | Same as `fix_findings_and_reinvoke` for steps 1–5, then instead of re-invoking, post a summary of remaining concerns and escalate to the user (last in-cap cycle reached).                                                                                                                                                                                       |
| `post_summary_and_escalate_to_user`               | Cap refused (cycle 4 attempted without authorization). Post a summary of remaining findings + fix history to the issue thread, ask the user. If authorized, retry with `override_cap=true`.                                                                                                                                                                       |

## Failure modes

| error code                                | When                                                                                            | next_action                                       |
|-------------------------------------------|-------------------------------------------------------------------------------------------------|--------------------------------------------------|
| `test_quality_review_branch_unresolved`   | HEAD is detached / branch name unresolvable                                                     | `checkout_named_feature_branch`                  |
| `test_quality_review_issue_unresolved`    | No `issue_number` passed and branch lacks a numeric prefix                                      | `pass_issue_number_or_use_numeric_branch_prefix` |
| `test_quality_review_cap_reached`         | Cycle 4 attempted without `override_cap=true`                                                   | `post_summary_and_escalate_to_user`              |
| `test_quality_review_override_missing_reason` | `override_cap=true` with empty `override_reason`                                              | (none - fix input and retry)                     |
| `test_quality_review_engine_failed`       | `claude --print` exited non-zero (transport error, OAuth not logged in, network)                | `fix_engine_issue_and_retry`                     |
| `test_quality_review_parse_failed`        | `claude` returned non-JSON or JSON that doesn't satisfy the findings schema                     | `inspect_engine_output_and_retry`                |
| `test_quality_review_record_rejected`     | Rendered findings record matched the sensitive-content guardrail                                | `scrub_findings_and_retry`                       |

In every error envelope, `ok: false` and no cycle marker is written
(except for the cap-reached case, where the cycle counter is what
produced the refusal). A retry against the same (issue, branch) does
not double-spend the cycle budget unless the underlying engine call
actually succeeded.

## Operator quickstart

1. Run `claude login` on the host (one-time; persists in `~/.claude`).
2. From a Ground-Control-aware repo, run `/implement <issue>` as
   normal - Step 13 calls `gc_test_quality_review` automatically.
3. To run the review standalone (debugging, ad-hoc):

   ```bash
   gc-mcp-call gc_test_quality_review \
     --repo_path /path/to/repo \
     --issue_number 884
   ```

   (Or via the MCP CLI of your choice - the tool is registered in
   `mcp/ground-control/index.js`.)

The legacy `Skill("review-tests")` / `~/.codex/prompts/review-tests.md`
paths have been removed; existing host installs are orphaned and can
be deleted manually.

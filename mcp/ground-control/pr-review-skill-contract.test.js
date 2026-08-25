// Maintainer PR-review lane — skill contract test (issue #1535).
//
// A discovery/wording guard over the /review skill prose: it asserts the
// safety-critical contract markers are present so an edit cannot silently drop
// the read-only default, the remediation authorization gate, the no-history-
// rewrite rule, comment suppression, or the merge-gated post-merge closure. Per
// the preflight, this supplements — never replaces — the behavioral tool tests
// in pr-review-context.test.js and pr-review-remediate.test.js.

import { readFileSync } from "node:fs";
import { realpathSync } from "node:fs";
import assert from "node:assert/strict";
import { describe, it } from "node:test";

const REPO_ROOT = realpathSync(new URL("../..", import.meta.url).pathname);
const read = (rel) => readFileSync(`${REPO_ROOT}/${rel}`, "utf8");

const SKILL = read("skills/review/SKILL.md");
const REVIEW = read("skills/review/steps/step-01-review.md");
const REMEDIATE = read("skills/review/steps/step-02-remediate.md");
const POST_MERGE = read("skills/review/steps/step-03-post-merge.md");

function assertAll(text, needles, label) {
  for (const needle of needles) {
    assert.ok(
      (Array.isArray(needle) ? needle.some((n) => text.includes(n)) : text.includes(needle)),
      `${label}: missing "${Array.isArray(needle) ? needle.join(" | ") : needle}"`,
    );
  }
}

describe("/review skill contract", () => {
  it("declares the skill frontmatter for discovery", () => {
    assertAll(SKILL, ["name: review", "disable-model-invocation: true", "argument-hint"], "SKILL frontmatter");
  });

  it("SKILL.md keeps side effects on the MCP boundary and merge with the user", () => {
    assertAll(SKILL, [
      ["never runs `gh`, `git`, or `curl`", "never run `gh`, `git`, or `curl`"],
      "gc_get_pr_review_context",
      "gc_remediate_pull_request",
      "gc_close_issue_after_merge",
      "user owns merge",
      "ADR-027",
      "ADR-029",
    ], "SKILL boundaries");
  });

  it("read-only step forbids mutation and posting and reads the real diff", () => {
    assertAll(REVIEW, [
      "gc_get_pr_review_context",
      ["without any repository or GitHub mutation", "no repository or GitHub mutation"],
      "Post nothing",
      ["findings-first", "findings first"],
      "Merge blockers",
      ["untrusted", "Untrusted"],
      "completeness",
    ], "read-only step");
  });

  it("remediation step is authorization-gated and preserves history", () => {
    assertAll(REMEDIATE, [
      ["only after the user explicitly asks", "explicit user authorization", "explicitly asks"],
      "gc-review: remediation-approved",
      "gc_remediate_pull_request",
      "sync_base",
      "publish",
      "comment",
      ["never rebase", "never rebase, reset"],
      ["compare-and-swap", "fast-forward", "non-force"],
      ["succinct", "at most one", "one succinct"],
      "Preserve contributor history",
    ], "remediation step");
  });

  it("post-merge step closes only directly-delivered issues and preserves trackers", () => {
    assertAll(POST_MERGE, [
      ["only after merge is confirmed", "the PR is merged", "MERGED"],
      "directly delivered",
      ["partially delivered", "partially-delivered"],
      ["parent", "tracking", "epic"],
      "gc_close_issue_after_merge",
      ["leave", "Leave"],
      ["cross-reference", "cross_reference"],
    ], "post-merge step");
  });
});

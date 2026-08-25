// Maintainer PR-review lane — trusted-host confirmation tests (issue #1535, codex F6 / cycle-3 F5).
//
// Remediation is gated by a PR review bound to the reviewed head OID (GitHub's
// trusted commit_id, not backdatable), carrying an approval phrase, by a
// write-permission account — a confirmation the model cannot forge.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { REVIEW_REMEDIATION_APPROVAL_PHRASE, assertRemediationConfirmed, resolveActorRepoPermission } from "./lib.js";

const HEAD = "a".repeat(40);
const OTHER = "b".repeat(40);

describe("resolveActorRepoPermission", () => {
  it("returns the permission for a write-access account", async () => {
    const runner = async () => ({ stdout: "maintain\n" });
    assert.equal(await resolveActorRepoPermission("/r", "o", "n", "maint", runner), "maintain");
  });

  it("returns null for a read-only account", async () => {
    const runner = async () => ({ stdout: "read\n" });
    assert.equal(await resolveActorRepoPermission("/r", "o", "n", "rando", runner), null);
  });

  it("returns null on an empty login or an API error", async () => {
    const throwing = async () => { throw new Error("404"); };
    assert.equal(await resolveActorRepoPermission("/r", "o", "n", "", throwing), null);
    assert.equal(await resolveActorRepoPermission("/r", "o", "n", "gone", throwing), null);
  });
});

const base = { repoRoot: "/r", owner: "o", name: "n", prNumber: 42, reviewedHeadOid: HEAD };
const reviewsRunner = (reviews) => async (command, args) => {
  if (command === "gh" && args[0] === "api" && args.some((a) => String(a).includes("/pulls/") && String(a).includes("/reviews"))) {
    return { stdout: JSON.stringify(reviews) };
  }
  throw new Error(`unexpected: ${command} ${JSON.stringify(args)}`);
};
const review = ({ login = "maint", commit = HEAD, body = `looks good — ${REVIEW_REMEDIATION_APPROVAL_PHRASE}` } = {}) =>
  ({ user: { login }, commit_id: commit, body, state: "COMMENTED" });

describe("assertRemediationConfirmed", () => {
  it("confirms a write-applied approval review bound to the reviewed head", async () => {
    const out = await assertRemediationConfirmed({
      ...base, commandRunner: reviewsRunner([review()]), permissionResolver: async () => "admin",
    });
    assert.equal(out.ok, true);
    assert.equal(out.confirmed_by, "maint");
    assert.equal(out.confirmed_permission, "admin");
    assert.equal(out.confirmed_commit, HEAD);
  });

  it("refuses when no review carries the approval phrase", async () => {
    const out = await assertRemediationConfirmed({
      ...base, commandRunner: reviewsRunner([review({ body: "LGTM" })]), permissionResolver: async () => "write",
    });
    assert.equal(out.error, "pr_remediation_confirmation_required");
  });

  it("refuses an approval review bound to a different (earlier) head", async () => {
    const out = await assertRemediationConfirmed({
      ...base, commandRunner: reviewsRunner([review({ commit: OTHER })]), permissionResolver: async () => "write",
    });
    assert.equal(out.error, "pr_remediation_confirmation_required");
  });

  it("refuses when the approving reviewer lacks write permission", async () => {
    const out = await assertRemediationConfirmed({
      ...base, commandRunner: reviewsRunner([review({ login: "rando" })]), permissionResolver: async () => null,
    });
    assert.equal(out.error, "pr_remediation_confirmation_unverified");
  });

  it("refuses (unverified) when the PR reviews list cannot be read", async () => {
    const throwing = async () => { throw new Error("reviews unreadable"); };
    const out = await assertRemediationConfirmed({
      ...base, commandRunner: throwing, permissionResolver: async () => "write",
    });
    assert.equal(out.error, "pr_remediation_confirmation_unverified");
  });
});

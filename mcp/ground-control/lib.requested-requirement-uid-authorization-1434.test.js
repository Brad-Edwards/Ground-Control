// Split from lib.repository-identity-resolution-gc-p026-1383.test.js under the
// 500-LOC limit (docs/CODING_STANDARDS.md). The requirement-UID authorization
// suites (#1434 and its auto-resolve follow-up) are a self-contained seam:
// requestedRequirementUidAuthorization (pure body/UID check) and
// authorizeRequestedRequirementUid (the issue-thread-backed gate resolver).
// Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { authorizeRequestedRequirementUid, requestedRequirementUidAuthorization } from "./lib.js";

describe("requestedRequirementUidAuthorization (#1434)", () => {
  const body = "## Requirements\n- DSL-437\n- DSL-438\n";

  it("authorizes a UID the issue's Requirements section actually lists", () => {
    const result = requestedRequirementUidAuthorization(body, "DSL-437");
    assert.equal(result.ok, true);
    assert.equal(result.requirementUid, "DSL-437");
  });

  it("refuses a syntactically valid UID that the target issue does not list", () => {
    // Syntax is not authority: a UID belonging to another issue or project
    // must never become the gate's requirement identity.
    const result = requestedRequirementUidAuthorization(body, "OTHER-999");
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_requested_requirement_uid_out_of_scope");
    // These envelopes reach tool results, and the environment is the only place
    // the requested UID may exist, so the message must not echo it back.
    assert.equal(result.message.includes("OTHER-999"), false);
  });

  it("refuses a UID that is not a bounded requirement identifier", () => {
    const result = requestedRequirementUidAuthorization(body, "DSL-437; rm -rf /");
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_requested_requirement_uid_invalid");
  });

  it("authorizes an absent UID without binding anything", () => {
    for (const absent of [undefined, null, ""]) {
      const result = requestedRequirementUidAuthorization(body, absent);
      assert.equal(result.ok, true);
      assert.equal(result.requirementUid, null);
    }
  });

  it("refuses every UID when the issue has no Requirements section", () => {
    const result = requestedRequirementUidAuthorization("## Problem\nNo requirements.\n", "DSL-437");
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_requested_requirement_uid_out_of_scope");
  });
});

describe("authorizeRequestedRequirementUid (#1434 follow-up: auto-resolve gate UID)", () => {
  const reader = (body) => async () => ({ ok: true, body });
  const singleReqBody = "## Requirements\n- DSL-437\n";
  const multiReqBody = "## Requirements\n- DSL-437\n- DSL-438\n";
  const noReqBody = "## Problem\nA bug fix.\n";

  it("carries the sole in-scope requirement UID when the caller omits it", async () => {
    // A branch named for the issue number (e.g. 785-batch-trial-scheduling) has
    // no UID to pass, so the gate would otherwise fail requirement-context-missing.
    for (const omitted of [undefined, null, ""]) {
      const result = await authorizeRequestedRequirementUid(
        { repoPath: "/r", issueNumber: 785, requestedRequirementUid: omitted },
        { issueThreadReader: reader(singleReqBody) },
      );
      assert.equal(result.ok, true);
      assert.equal(result.requirementUid, "DSL-437");
    }
  });

  it("binds no UID when the caller omits it and the issue lists multiple requirements", async () => {
    const result = await authorizeRequestedRequirementUid(
      { repoPath: "/r", issueNumber: 785, requestedRequirementUid: null },
      { issueThreadReader: reader(multiReqBody) },
    );
    assert.equal(result.ok, true);
    assert.equal(result.requirementUid, null);
  });

  it("binds no UID for a requirement-free issue", async () => {
    const result = await authorizeRequestedRequirementUid(
      { repoPath: "/r", issueNumber: 785, requestedRequirementUid: null },
      { issueThreadReader: reader(noReqBody) },
    );
    assert.equal(result.ok, true);
    assert.equal(result.requirementUid, null);
  });

  it("does not block the gate when auto-resolution cannot read the issue", async () => {
    const result = await authorizeRequestedRequirementUid(
      { repoPath: "/r", issueNumber: 785, requestedRequirementUid: null },
      { issueThreadReader: async () => ({ ok: false, error: "issue_unreadable" }) },
    );
    assert.equal(result.ok, true);
    assert.equal(result.requirementUid, null);
  });

  it("does not read the issue to auto-resolve when there is no issue number", async () => {
    let read = false;
    const result = await authorizeRequestedRequirementUid(
      { repoPath: "/r", issueNumber: null, requestedRequirementUid: null },
      {
        issueThreadReader: async () => {
          read = true;
          return { ok: true, body: singleReqBody };
        },
      },
    );
    assert.equal(result.ok, true);
    assert.equal(result.requirementUid, null);
    assert.equal(read, false);
  });

  it("still authorizes an explicit in-scope UID against the issue", async () => {
    const result = await authorizeRequestedRequirementUid(
      { repoPath: "/r", issueNumber: 785, requestedRequirementUid: "DSL-437" },
      { issueThreadReader: reader(multiReqBody) },
    );
    assert.equal(result.ok, true);
    assert.equal(result.requirementUid, "DSL-437");
  });

  it("still blocks an explicit UID when the issue cannot be read", async () => {
    const result = await authorizeRequestedRequirementUid(
      { repoPath: "/r", issueNumber: 785, requestedRequirementUid: "DSL-437" },
      { issueThreadReader: async () => ({ ok: false, error: "issue_unreadable" }) },
    );
    assert.equal(result.ok, false);
    assert.equal(result.error, "issue_unreadable");
    assert.equal(result.next_action, "repair_issue_access_and_retry");
  });

  it("still refuses an explicit out-of-scope UID", async () => {
    const result = await authorizeRequestedRequirementUid(
      { repoPath: "/r", issueNumber: 785, requestedRequirementUid: "OTHER-999" },
      { issueThreadReader: reader(singleReqBody) },
    );
    assert.equal(result.ok, false);
    assert.equal(result.error, "implement_requested_requirement_uid_out_of_scope");
  });
});

// Posting a verification attestation writes exactly one trusted issue-thread
// marker through the argv-based gh api boundary (ADR-027/ADR-029, issue #1497),
// carrying only digests and safe identities.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { computeVerificationAttestation } from "./lib/verification-attestation.js";
import { postImplementVerificationAttestation } from "./lib/knowledge-capture.js";

function attestation() {
  return computeVerificationAttestation({
    issueNumber: 1497,
    branchName: "1497-tier-publish-verification",
    baseSha: "c".repeat(40),
    treeOid: "a".repeat(40),
    requirementUid: "GC-O007",
    completionCommand: "make mcp-test",
    policyCommand: "make policy",
    toolchainFingerprintCommand: "node --version | shasum -a 256",
    config: {},
    toolchainDigest: "e".repeat(64),
  });
}

describe("postImplementVerificationAttestation", () => {
  it("posts one marker comment to the issue and returns the created identifiers", async () => {
    const calls = [];
    const commandRunner = async (file, args, options) => {
      calls.push({ file, args, options });
      return { stdout: JSON.stringify({ html_url: "https://github.test/c/9", id: 9 }), stderr: "" };
    };
    const att = attestation();
    const result = await postImplementVerificationAttestation(
      "/repo",
      "autarchy-ai",
      "Ground-Control",
      att,
      commandRunner,
    );
    assert.equal(calls.length, 1);
    assert.equal(calls[0].file, "gh");
    assert.ok(calls[0].args.includes("/repos/autarchy-ai/Ground-Control/issues/1497/comments"));
    const body = calls[0].args.at(-1);
    assert.ok(body.startsWith("body="));
    // Content-address present; no raw command text or UID leaks into the record.
    assert.ok(body.includes(att.id));
    assert.equal(body.includes("make mcp-test"), false);
    assert.equal(body.includes("GC-O007"), false);
    assert.deepEqual(result, { commentUrl: "https://github.test/c/9", commentId: 9 });
  });
});

// The verification attestation is the content-addressed proof that one exact
// staged tree passed the authoritative gates (issue #1497). Reuse compares
// attestation ids, so the id must change whenever any bound input changes, and
// the durable marker must never leak raw command text or the requirement UID.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  VERIFICATION_ATTESTATION_SCHEMA,
  REQUIREMENT_FREE_SENTINEL,
  computeVerificationAttestation,
  buildVerificationAttestationMarker,
  parseVerificationAttestationMarkers,
  verificationAttestationMatches,
  selectTrustedVerificationAttestations,
  findMatchingVerificationAttestation,
} from "./lib/verification-attestation.js";

const TREE_A = "a".repeat(40);
const TREE_B = "b".repeat(40);
const BASE_A = "c".repeat(40);
const BASE_B = "d".repeat(40);
const TOOL_A = "e".repeat(64);
const TOOL_B = "f".repeat(64);

function bindings(overrides = {}) {
  return {
    issueNumber: 1497,
    branchName: "1497-tier-publish-verification",
    baseSha: BASE_A,
    treeOid: TREE_A,
    requirementUid: null,
    completionCommand: "make mcp-test",
    policyCommand: "make policy",
    toolchainFingerprintCommand: "node -e 'process.stdout.write(require(\"crypto\").createHash(\"sha256\").update(process.version).digest(\"hex\"))'",
    config: { precommit_command: "pre-commit run --all-files" },
    toolchainDigest: TOOL_A,
    ...overrides,
  };
}

describe("computeVerificationAttestation", () => {
  it("is deterministic and content-addressed over every bound input", () => {
    const base = computeVerificationAttestation(bindings());
    assert.ok(base, "a complete binding yields an attestation");
    assert.match(base.id, /^[0-9a-f]{64}$/);
    // Same inputs → same id.
    assert.equal(computeVerificationAttestation(bindings()).id, base.id);
    // Each differing input flips the id.
    const mutations = [
      { branchName: "1497-other" },
      { baseSha: BASE_B },
      { treeOid: TREE_B },
      { requirementUid: "GC-O007" },
      { completionCommand: "make check" },
      { policyCommand: "make other-policy" },
      { toolchainFingerprintCommand: "echo other" },
      { config: { precommit_command: "pre-commit run trailing-whitespace" } },
      { toolchainDigest: TOOL_B },
      { issueNumber: 1498, branchName: "1498-tier-publish-verification" },
    ];
    for (const mutation of mutations) {
      assert.notEqual(
        computeVerificationAttestation(bindings(mutation)).id,
        base.id,
        `changing ${Object.keys(mutation)[0]} must change the id`,
      );
    }
  });

  it("binds a requirement-free run to the reserved sentinel, distinct from any UID", () => {
    const free = computeVerificationAttestation(bindings({ requirementUid: null }));
    const blank = computeVerificationAttestation(bindings({ requirementUid: "   " }));
    const uid = computeVerificationAttestation(bindings({ requirementUid: "GC-O007" }));
    assert.equal(free.id, blank.id, "null and blank both use the sentinel");
    assert.notEqual(free.id, uid.id, "the sentinel is distinct from a real UID");
  });

  it("fails closed (returns null) without a valid toolchain digest or git identities", () => {
    assert.equal(computeVerificationAttestation(bindings({ toolchainDigest: null })), null);
    assert.equal(computeVerificationAttestation(bindings({ toolchainDigest: "not-a-sha" })), null);
    assert.equal(computeVerificationAttestation(bindings({ treeOid: "nope" })), null);
    assert.equal(computeVerificationAttestation(bindings({ baseSha: "nope" })), null);
  });
});

describe("verification attestation marker", () => {
  it("round-trips through render and parse to a valid record with the same id", () => {
    const attestation = computeVerificationAttestation(bindings());
    const marker = buildVerificationAttestationMarker(attestation);
    const parsed = parseVerificationAttestationMarkers([marker], 1497);
    assert.equal(parsed.length, 1);
    assert.equal(parsed[0].valid, true);
    assert.equal(parsed[0].id, attestation.id);
    assert.equal(parsed[0].tree, TREE_A);
    assert.equal(parsed[0].base, BASE_A);
  });

  it("never leaks raw command text or the requirement UID into the durable body", () => {
    const attestation = computeVerificationAttestation(bindings({ requirementUid: "GC-O007" }));
    const marker = buildVerificationAttestationMarker(attestation);
    assert.equal(marker.includes("make mcp-test"), false);
    assert.equal(marker.includes("make policy"), false);
    assert.equal(marker.includes("pre-commit"), false);
    assert.equal(marker.includes("GC-O007"), false);
    assert.ok(marker.includes(VERIFICATION_ATTESTATION_SCHEMA));
  });

  it("authenticates a genuine marker and rejects a forged authentication tag", () => {
    const attestation = computeVerificationAttestation(bindings());
    const marker = buildVerificationAttestationMarker(attestation);
    assert.match(attestation.auth, /^[0-9a-f]{64}$/);
    const [genuine] = parseVerificationAttestationMarkers([marker], 1497);
    assert.equal(genuine.authenticated, true);
    assert.equal(verificationAttestationMatches(genuine, attestation), true);
    // A repo writer can compute the content id but not a valid HMAC: a tampered
    // auth tag stays structurally valid (not a poison) but is not authenticated,
    // so it can never be a reuse hit.
    const forged = marker.replace(`auth="${attestation.auth}"`, `auth="${"0".repeat(64)}"`);
    const [tampered] = parseVerificationAttestationMarkers([forged], 1497);
    assert.equal(tampered.valid, true);
    assert.equal(tampered.authenticated, false);
    assert.equal(verificationAttestationMatches(tampered, attestation), false);
  });

  it("rejects a marker whose id does not match its own bound fields (integrity)", () => {
    const attestation = computeVerificationAttestation(bindings());
    const marker = buildVerificationAttestationMarker(attestation)
      .replace(attestation.id, "0".repeat(64));
    const parsed = parseVerificationAttestationMarkers([marker], 1497);
    assert.equal(parsed.length, 1);
    assert.equal(parsed[0].valid, false);
  });

  it("rejects a marker for a different issue or with a malformed field", () => {
    const attestation = computeVerificationAttestation(bindings());
    const marker = buildVerificationAttestationMarker(attestation);
    assert.equal(parseVerificationAttestationMarkers([marker], 9999).some((r) => r.valid), false);
    const malformed = marker.replace(`tree="${TREE_A}"`, 'tree="short"');
    const parsed = parseVerificationAttestationMarkers([malformed], 1497);
    assert.equal(parsed[0].valid, false);
  });
});

describe("verificationAttestationMatches", () => {
  it("hits only a valid parsed record whose id equals the recomputed attestation", () => {
    const attestation = computeVerificationAttestation(bindings());
    const [parsed] = parseVerificationAttestationMarkers(
      [buildVerificationAttestationMarker(attestation)],
      1497,
    );
    assert.equal(verificationAttestationMatches(parsed, attestation), true);
    const other = computeVerificationAttestation(bindings({ treeOid: TREE_B }));
    assert.equal(verificationAttestationMatches(parsed, other), false);
    assert.equal(verificationAttestationMatches({ valid: false, id: attestation.id }, attestation), false);
  });
});

describe("selectTrustedVerificationAttestations", () => {
  const attestation = computeVerificationAttestation(bindings());
  const marker = buildVerificationAttestationMarker(attestation);
  const trustAll = () => true;

  it("collects valid records from trusted comments and finds a content-address match", () => {
    const comments = [{ id: 42, body: `context\n${marker}` }];
    const selection = selectTrustedVerificationAttestations(comments, trustAll, 1497);
    assert.equal(selection.ok, true);
    assert.equal(selection.records.length, 1);
    const hit = findMatchingVerificationAttestation(selection, attestation);
    assert.ok(hit);
    assert.equal(hit.commentId, 42);
    // A different tree recomputes a different id → miss.
    const miss = findMatchingVerificationAttestation(
      selection,
      computeVerificationAttestation(bindings({ treeOid: TREE_B })),
    );
    assert.equal(miss, null);
  });

  it("fails closed when a marker is authored outside the writer set", () => {
    const comments = [{ id: 7, body: marker }];
    const selection = selectTrustedVerificationAttestations(comments, () => false, 1497);
    assert.equal(selection.ok, false);
    assert.equal(selection.error, "implement_verification_attestation_untrusted");
  });

  it("fails closed when any verification marker on the thread is malformed", () => {
    const corrupt = marker.replace(attestation.id, "0".repeat(64));
    const selection = selectTrustedVerificationAttestations([{ id: 1, body: corrupt }], trustAll, 1497);
    assert.equal(selection.ok, false);
    assert.equal(selection.error, "implement_verification_attestation_malformed");
  });

  it("returns no match against a thread with no verification markers", () => {
    const selection = selectTrustedVerificationAttestations([{ id: 1, body: "no markers here" }], trustAll, 1497);
    assert.equal(selection.ok, true);
    assert.equal(findMatchingVerificationAttestation(selection, attestation), null);
  });
});

describe("module constants", () => {
  it("exposes a versioned schema and an explicit requirement-free sentinel", () => {
    assert.equal(VERIFICATION_ATTESTATION_SCHEMA, "gc.implement.verification-attestation/v1");
    assert.equal(typeof REQUIREMENT_FREE_SENTINEL, "string");
    assert.ok(REQUIREMENT_FREE_SENTINEL.length > 0);
  });
});

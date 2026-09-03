// Content-addressed verification attestation (issue #1497).
//
// A durable, versioned issue-thread marker proving that ONE exact staged tree
// passed the authoritative completion + policy gates under a specific base
// commit, requirement context, command/config set, and toolchain fingerprint.
// It is a DISTINCT fact from gc.implement.remote-base-sync/v1: verification says
// specific inputs passed specific gates; synchronization says the branch
// incorporates a freshly fetched base. Do not fold either schema into the other.
//
// Reuse is content-addressed: the attestation id is the SHA-256 of the canonical
// bound record, so comparing ids is exactly comparing every bound field. It is
// fail-closed: an absent, malformed, untrusted, or non-matching record is a
// cache miss whose only fallback is full verification. Only digests and safe Git
// or workflow identities enter the durable marker — never raw command text, the
// requirement UID, environment values, tool versions, or child output.

import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { GIT_OBJECT_ID_RE, validateImplementBranchName } from "./codex-workflow.js";

export const VERIFICATION_ATTESTATION_SCHEMA = "gc.implement.verification-attestation/v1";

// A gate-skipping attestation is only trustworthy if the process that ran the
// gates produced it. The content-addressed id is computable by anyone who can
// read the tree, base, and config — including a repository writer forging a
// marker directly — so the durable marker also carries an HMAC keyed by a secret
// generated once per MCP process and never disclosed. verify (produce) and base
// synchronization (consume) share the same process and secret within one
// /implement run; a forged marker cannot carry a valid tag, and a marker from a
// prior process authenticates to a different secret and is simply ignored — the
// reuse then fails closed to full verification (issue #1497 codex review).
const ATTESTATION_AUTH_SECRET = randomBytes(32);

function attestationAuthTag(id) {
  return createHmac("sha256", ATTESTATION_AUTH_SECRET).update(String(id), "utf8").digest("hex");
}

function authTagMatches(id, tag) {
  if (!SHA256_HEX_RE.test(tag ?? "")) return false;
  return timingSafeEqual(Buffer.from(attestationAuthTag(id), "hex"), Buffer.from(tag, "hex"));
}
// A reserved sentinel so a requirement-free run binds a real, non-empty value
// that cannot collide with a UID-bearing run or with a malformed/empty field.
export const REQUIREMENT_FREE_SENTINEL = "gc.implement.requirement-free";
const MARKER_PREFIX = "<!-- gc:implement-verification-attestation";
// Non-backtracking (S8786): an unquantified `\s` before the capture removes the
// quantifier-vs-quantifier ambiguity a `\s+([^>]*?)` form has, and the capture
// runs greedily to the first `>` (disjoint from `[^>]`) — the `>` of the closing
// `-->`. The trailing `--` lands in the capture and is ignored by attribute
// parsing, which only extracts `key="value"` pairs.
const MARKER_RE = /<!--\s*gc:implement-verification-attestation\s([^>]*)>/g;
const SHA256_HEX_RE = /^[0-9a-f]{64}$/;

export function sha256Hex(value) {
  return createHash("sha256").update(String(value), "utf8").digest("hex");
}

// Stable serialization with sorted keys so the digest is order-independent.
// The comparator is an explicit code-unit ordering, NOT String.localeCompare:
// the digest must be byte-identical across every host and locale, and
// localeCompare is locale-dependent, which would silently fork the id.
export function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value)
      .sort((a, b) => {
        if (a < b) return -1;
        if (a > b) return 1;
        return 0;
      })
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value ?? null);
}

// Digest of the requirement context. A requirement-free run binds the sentinel.
export function requirementContextDigest(requirementUid) {
  const token =
    typeof requirementUid === "string" && requirementUid.trim() !== ""
      ? requirementUid.trim()
      : REQUIREMENT_FREE_SENTINEL;
  return sha256Hex(`requirement-context\n${token}`);
}

function normalizeCommandText(command) {
  return typeof command === "string" && command.trim() !== "" ? command.trim() : null;
}

// Digest binding the normalized completion + policy commands, the configured
// toolchain-fingerprint command TEXT, the relevant normalized config, and the
// schema version. Command text is hashed, never stored raw.
export function commandConfigDigest({
  completionCommand,
  policyCommand,
  toolchainFingerprintCommand = null,
  config = {},
} = {}) {
  return sha256Hex(
    canonicalJson({
      schema: VERIFICATION_ATTESTATION_SCHEMA,
      completion: normalizeCommandText(completionCommand),
      policy: normalizeCommandText(policyCommand),
      toolchain_fingerprint: normalizeCommandText(toolchainFingerprintCommand),
      config: config ?? {},
    }),
  );
}

// The ordered, bound fields whose canonical SHA-256 is the attestation id. The
// issue thread is already owner/repo-scoped (a trusted author can only post to
// this repo's thread), so owner/name are not re-encoded here; that scoping is
// enforced by the trusted-record reader, exactly as for base synchronization.
function attestationFields({ issueNumber, branchName, baseSha, treeOid, req, cfg, tool }) {
  return {
    schema: VERIFICATION_ATTESTATION_SCHEMA,
    issue: issueNumber,
    branch: branchName,
    base: baseSha,
    tree: treeOid,
    req,
    cfg,
    tool,
  };
}

function attestationIdFromFields(fields) {
  return sha256Hex(canonicalJson(fields));
}

/**
 * Compute the attestation id + flat record from full bindings, or `null` when a
 * complete, reusable attestation cannot be formed (fail-closed). A missing or
 * malformed toolchain digest, base commit, or staged tree oid disables reuse:
 * the toolchain fingerprint covers non-tree inputs, so without it the workflow
 * cannot prove the gate result is reusable.
 */
export function computeVerificationAttestation(bindings) {
  if (bindings == null || typeof bindings !== "object") return null;
  const { issueNumber, branchName, baseSha, treeOid, toolchainDigest } = bindings;
  if (!Number.isInteger(issueNumber) || issueNumber <= 0) return null;
  if (validateImplementBranchName(branchName, issueNumber).ok !== true) return null;
  if (!GIT_OBJECT_ID_RE.test(baseSha ?? "")) return null;
  if (!GIT_OBJECT_ID_RE.test(treeOid ?? "")) return null;
  if (!SHA256_HEX_RE.test(toolchainDigest ?? "")) return null;
  const req = requirementContextDigest(bindings.requirementUid);
  const cfg = commandConfigDigest(bindings);
  const fields = attestationFields({ issueNumber, branchName, baseSha, treeOid, req, cfg, tool: toolchainDigest });
  const id = attestationIdFromFields(fields);
  return { id, auth: attestationAuthTag(id), ...fields };
}

export function buildVerificationAttestationMarker(attestation) {
  return [
    MARKER_PREFIX,
    `schema="${attestation.schema}"`,
    `id="${attestation.id}"`,
    `auth="${attestation.auth}"`,
    `issue="${attestation.issue}"`,
    `branch="${attestation.branch}"`,
    `base="${attestation.base}"`,
    `tree="${attestation.tree}"`,
    `req="${attestation.req}"`,
    `cfg="${attestation.cfg}"`,
    `tool="${attestation.tool}"`,
    "-->",
  ].join(" ");
}

export function parseVerificationAttestationMarkers(commentBodies, issueNumber) {
  const records = [];
  for (const body of Array.isArray(commentBodies) ? commentBodies : []) {
    if (typeof body !== "string") continue;
    MARKER_RE.lastIndex = 0;
    let match;
    while ((match = MARKER_RE.exec(body)) !== null) {
      const attrs = {};
      // Bounded quantifiers (S8786): attribute keys are short lowercase tokens
      // and values are digests/oids/identities well under this ceiling, so the
      // key and value quantifiers cannot drive super-linear backtracking.
      const attrRe = /([a-z]{2,8})="([^"]{0,128})"/g;
      let attr;
      while ((attr = attrRe.exec(match[1])) !== null) attrs[attr[1]] = attr[2];
      const parsedIssue = Number.parseInt(attrs.issue ?? "", 10);
      const wellFormed =
        attrs.schema === VERIFICATION_ATTESTATION_SCHEMA
        && SHA256_HEX_RE.test(attrs.id ?? "")
        && SHA256_HEX_RE.test(attrs.auth ?? "")
        && parsedIssue === issueNumber
        && validateImplementBranchName(attrs.branch, issueNumber).ok === true
        && GIT_OBJECT_ID_RE.test(attrs.base ?? "")
        && GIT_OBJECT_ID_RE.test(attrs.tree ?? "")
        && SHA256_HEX_RE.test(attrs.req ?? "")
        && SHA256_HEX_RE.test(attrs.cfg ?? "")
        && SHA256_HEX_RE.test(attrs.tool ?? "");
      // A record whose stored id does not match a fresh digest of its own bound
      // fields is corrupt — treat it as malformed, never a reuse hit.
      const integrity =
        wellFormed
        && attrs.id === attestationIdFromFields(
          attestationFields({
            issueNumber: parsedIssue,
            branchName: attrs.branch,
            baseSha: attrs.base,
            treeOid: attrs.tree,
            req: attrs.req,
            cfg: attrs.cfg,
            tool: attrs.tool,
          }),
        );
      if (!integrity) {
        records.push({ valid: false, raw: match[0] });
        continue;
      }
      // Authenticity is a separate axis from structural validity: a structurally
      // valid marker whose HMAC does not verify against THIS process's secret is
      // not corrupt — it was posted by a forger or a prior process. It is not a
      // reuse hit (matches() requires `authenticated`) but must not poison the
      // read, so it stays `valid: true`.
      records.push({
        valid: true,
        authenticated: authTagMatches(attrs.id, attrs.auth),
        id: attrs.id,
        issue: parsedIssue,
        branch: attrs.branch,
        base: attrs.base,
        tree: attrs.tree,
        req: attrs.req,
        cfg: attrs.cfg,
        tool: attrs.tool,
      });
    }
  }
  return records;
}

/** A parsed record reuses an attestation only when it is structurally valid,
 * authenticated by this process's secret, and its id is the exact content
 * address the caller recomputed from the current checkout. */
export function verificationAttestationMatches(parsedRecord, attestation) {
  return (
    Boolean(parsedRecord?.valid)
    && Boolean(parsedRecord?.authenticated)
    && Boolean(attestation?.id)
    && parsedRecord.id === attestation.id
  );
}

/**
 * Pure trusted-record selection over already-fetched issue comments. Kept
 * separate from I/O so the trust and fail-closed rules are unit-tested directly.
 * `isTrusted(comment)` reports whether the comment's author is in the repository
 * writer set. A marker authored outside that set, or any malformed marker on the
 * thread, fails the whole selection closed — reuse is never authorized off a
 * thread that carries an untrusted or corrupt verification marker.
 */
export function selectTrustedVerificationAttestations(comments, isTrusted, issueNumber) {
  const markerComments = (Array.isArray(comments) ? comments : [])
    .map((comment) => ({
      comment,
      records: parseVerificationAttestationMarkers([comment?.body], issueNumber),
    }))
    .filter(({ records }) => records.length > 0);
  if (markerComments.some(({ comment }) => !isTrusted(comment))) {
    return {
      ok: false,
      error: "implement_verification_attestation_untrusted",
      message: "A verification-attestation marker was authored outside the repository writer set",
    };
  }
  if (markerComments.some(({ records }) => records.some((record) => !record.valid))) {
    return {
      ok: false,
      error: "implement_verification_attestation_malformed",
      message: "A malformed verification-attestation marker exists on the issue thread",
    };
  }
  return {
    ok: true,
    records: markerComments.flatMap(({ comment, records }) =>
      records.map((record) => ({ record, commentId: comment?.id ?? null })),
    ),
  };
}

/** Find the single trusted record whose content address matches `attestation`,
 * or null when the selection failed or nothing matches (a cache miss). */
export function findMatchingVerificationAttestation(selection, attestation) {
  if (!selection?.ok || !attestation?.id) return null;
  return selection.records.find(({ record }) => verificationAttestationMatches(record, attestation)) ?? null;
}

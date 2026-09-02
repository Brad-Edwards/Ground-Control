// Maintainer PR-review lane — shared validators, constants, and helpers (issue #1535).
//
// This module holds the pieces both the read-only context tool
// (lib/pr-review-context.js) and the authorized remediation tool
// (lib/pr-review-remediate.js) depend on. Per the preflight
// (architecture/notes/maintainer-pr-review-skill-preflight.md) and ADR-027,
// the skill never runs gh/git itself — every side effect flows through these
// server-owned helpers with fixed argv, and expected failures return the
// repo-wide `{ok:false, error, message, next_action}` envelope rather than
// throwing.

import { isAbsolute } from "node:path";
import { GIT_OBJECT_ID_RE } from "./codex-workflow.js";
import { isSafeGitRefName } from "./repo-context.js";
import { extractGhErrorMessage } from "./grc-legacy-compat-2.js";
import { execFile } from "./runtime-primitives.js";

// Bounded evidence caps. A large or binary diff must never be silently
// presented as fully reviewed, so patches over the byte cap and file lists over
// the file cap are truncated with an explicit flag (never dropped).
export const PR_REVIEW_PATCH_BYTE_CAP = 65536;
export const PR_REVIEW_FILE_CAP = 300;
export const PR_REVIEW_LINKED_ISSUE_CAP = 20;
// Authorization text is model-controlled; bound it so a runaway prompt cannot be
// smuggled through this field.
export const PR_REMEDIATION_AUTHORIZATION_MAX = 4000;

export const PR_REVIEW_ACTIONS = Object.freeze(["sync_base", "publish"]);

// A remediation record id ties a `publish`/`comment` call back to the PR
// identity a `sync_base` call already validated. 32 lowercase hex, same shape as
// the base-sync record id.
export const PR_REVIEW_RECORD_ID_RE = /^[0-9a-f]{32}$/;

export function refusal(error, message, extra = {}) {
  return { ok: false, error, message, ...extra };
}

export function isPlainObject(value) {
  return value != null && typeof value === "object" && !Array.isArray(value);
}

export function validateRepoPath(repoPath) {
  if (typeof repoPath !== "string" || !isAbsolute(repoPath)) {
    return refusal(
      "pr_review_repo_path_invalid",
      "repo_path must be an absolute path to the invocation checkout",
    );
  }
  return { ok: true };
}

export function validatePrNumber(prNumber) {
  if (!Number.isInteger(prNumber) || prNumber <= 0) {
    return refusal("pr_review_pr_number_invalid", "pr_number must be a positive integer");
  }
  return { ok: true };
}

// A caller may assert the expected repository slug; it is only ever checked
// against the checkout's origin, never used to select an alternate destination.
export function validateRepoAssertion(repo) {
  if (repo == null) return { ok: true };
  if (typeof repo !== "string" || !/^[^/\s]+\/[^/\s]+$/.test(repo)) {
    return refusal("pr_review_repo_assertion_invalid", "repo must be '<owner>/<name>' when supplied");
  }
  return { ok: true };
}

export function assertRepoAssertionMatches(repo, owner, name) {
  if (repo == null) return { ok: true };
  const [assertOwner, assertName] = repo.split("/");
  if (assertOwner.toLowerCase() !== owner.toLowerCase() || assertName.toLowerCase() !== name.toLowerCase()) {
    return refusal(
      "pr_review_repo_identity_mismatch",
      `The checkout resolves to ${owner}/${name}, not the asserted ${repo}`,
    );
  }
  return { ok: true };
}

export function isGitObjectId(value) {
  return typeof value === "string" && GIT_OBJECT_ID_RE.test(value.toLowerCase());
}

export function validateOid(value, field) {
  if (!isGitObjectId(value)) {
    return refusal("pr_review_oid_invalid", `${field} must be a full Git object id`);
  }
  return { ok: true };
}

export function validateRef(value, field) {
  if (typeof value !== "string" || value === "" || !isSafeGitRefName(value)) {
    return refusal("pr_review_ref_invalid", `${field} must be a safe Git ref name`);
  }
  return { ok: true };
}

// The reviewed-PR identity a remediation caller must echo back, so a mutation is
// bound to exactly the PR that was reviewed. A boolean the model asserts is not
// cryptographic proof of user intent, but binding to the reviewed OIDs makes a
// stale or mistargeted authorization detectable server-side.
export function validateReviewedIdentity(identity) {
  if (!isPlainObject(identity)) {
    return refusal("pr_review_identity_invalid", "reviewed_identity must be an object");
  }
  const checks = [
    validateRef(identity.base_ref, "reviewed_identity.base_ref"),
    validateRef(identity.head_ref, "reviewed_identity.head_ref"),
    validateOid(identity.base_oid, "reviewed_identity.base_oid"),
    validateOid(identity.head_oid, "reviewed_identity.head_oid"),
  ];
  const failed = checks.find((check) => !check.ok);
  if (failed) return failed;
  if (typeof identity.cross_repository !== "boolean") {
    return refusal("pr_review_identity_invalid", "reviewed_identity.cross_repository must be a boolean");
  }
  return { ok: true };
}

export function validateAuthorization(authorization) {
  if (typeof authorization !== "string" || authorization.trim() === "") {
    return refusal(
      "pr_remediation_authorization_missing",
      "Remediation requires an explicit user change request in `authorization`; "
        + "read-only review never mutates",
      { next_action: "obtain_explicit_user_authorization" },
    );
  }
  if (authorization.length > PR_REMEDIATION_AUTHORIZATION_MAX) {
    return refusal("pr_remediation_authorization_too_large", "authorization is too long");
  }
  return { ok: true };
}

// Fixed-argv gh invocation through the injected runner. Returns the raw stdout;
// callers parse. Errors are surfaced through a scrubbed message so a token or a
// diff body in gh stderr never reaches the caller verbatim.
export async function runReviewGh(repoRoot, args, commandRunner = execFile) {
  return commandRunner("gh", args, { cwd: repoRoot });
}

export async function runReviewGhJson(repoRoot, args, commandRunner = execFile) {
  const { stdout } = await runReviewGh(repoRoot, args, commandRunner);
  return JSON.parse(stdout);
}

// Paginated list read. `gh api --paginate` emits one JSON document PER PAGE, so a
// single JSON.parse over the concatenated output fails on any multi-page
// response; `--slurp` wraps the pages into one array, which we flatten back into
// the flat list the callers expect (codex cycle-2 F2, #1535).
export async function runReviewGhPaginated(repoRoot, apiPath, commandRunner = execFile) {
  const { stdout } = await runReviewGh(repoRoot, ["api", "--paginate", "--slurp", apiPath], commandRunner);
  const pages = JSON.parse(stdout);
  if (!Array.isArray(pages)) return [];
  return pages.flatMap((page) => (Array.isArray(page) ? page : [page]));
}

// A gh failure becomes a bounded structured outcome instead of a raw stack.
export function ghFailure(error, code, message) {
  return refusal(code, message, { detail: extractGhErrorMessage(error).slice(0, 500) });
}

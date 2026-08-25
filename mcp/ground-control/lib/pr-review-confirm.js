// Maintainer PR-review lane - trusted-host remediation confirmation (issue #1535).
//
// Codex F6 flagged that a model-supplied `authorization` string is not proof of
// user intent. This module adds an out-of-band confirmation the model cannot
// forge and that is bound to the exact reviewed head by a GitHub-trusted value:
// a pull-request REVIEW whose `commit_id` equals the reviewed head OID and whose
// body carries a designated approval phrase, submitted by an account with write
// (or higher) repository permission.
//
// Why a review rather than a label: GitHub sets a review's `commit_id` to the
// head at submission time - it is not author-controllable - so the confirmation
// is bound to the exact head. An earlier design bound a label to the head's
// committer date, but Git committer dates are author-controlled and a contributor
// can backdate a pushed commit to make a stale label look fresh (codex cycle-2 F6
// / cycle-3 F5). The review's `commit_id` closes that: a review against an old
// head simply does not match the current reviewed head, and the lane exposes no
// tool that submits a review, so the model cannot forge one.
//
// The helper is generic (repo/owner/pr/reviewed-head plus injectable gh runners)
// so the same trusted-host confirmation can gate other privileged mutation
// surfaces, not just this lane.

import { execFile } from "./runtime-primitives.js";
import { refusal, runReviewGhPaginated } from "./pr-review-shared.js";

// The phrase a maintainer includes in a PR review body to authorize remediation
// of the head that review was submitted against.
export const REVIEW_REMEDIATION_APPROVAL_PHRASE = "gc-review: remediation-approved";

const WRITE_PERMISSIONS = new Set(["write", "maintain", "admin"]);

// Effective repository permission for a login, via the collaborators API, using
// the injected runner so it is testable without hitting GitHub. Returns the
// permission string only when it is write-or-higher, else null.
export async function resolveActorRepoPermission(repoRoot, owner, name, login, commandRunner = execFile) {
  if (typeof login !== "string" || login.trim() === "") return null;
  try {
    const { stdout } = await commandRunner(
      "gh",
      ["api", "--method", "GET", `/repos/${owner}/${name}/collaborators/${encodeURIComponent(login)}/permission`, "--jq", ".permission"],
      { cwd: repoRoot },
    );
    const permission = String(stdout).trim().toLowerCase();
    return WRITE_PERMISSIONS.has(permission) ? permission : null;
  } catch {
    return null;
  }
}

// Trusted-host confirmation gate for a remediation mutation. Requires a PR review
// whose commit_id is the reviewed head, whose body carries the approval phrase,
// and whose author holds write permission. Refuses (non-mutating) otherwise.
export async function assertRemediationConfirmed({
  repoRoot, owner, name, prNumber, reviewedHeadOid, commandRunner = execFile,
  permissionResolver = resolveActorRepoPermission,
}) {
  const wantHead = typeof reviewedHeadOid === "string" ? reviewedHeadOid.toLowerCase() : null;
  let reviews;
  try {
    reviews = await runReviewGhPaginated(repoRoot, `/repos/${owner}/${name}/pulls/${prNumber}/reviews`, commandRunner);
  } catch {
    return refusal("pr_remediation_confirmation_unverified",
      "The PR reviews could not be read to verify remediation authorization");
  }
  const candidates = (Array.isArray(reviews) ? reviews : []).filter((r) =>
    typeof r?.commit_id === "string" && r.commit_id.toLowerCase() === wantHead
    && typeof r?.body === "string" && r.body.includes(REVIEW_REMEDIATION_APPROVAL_PHRASE)
    && typeof r?.user?.login === "string");
  if (candidates.length === 0) {
    return refusal(
      "pr_remediation_confirmation_required",
      `Remediation requires trusted-host confirmation the model cannot forge: submit a PR review against the current head `
        + `(commit ${wantHead ? wantHead.slice(0, 8) : "unknown"}) whose body contains '${REVIEW_REMEDIATION_APPROVAL_PHRASE}', `
        + "from an account with write access, then retry",
      { approval_phrase: REVIEW_REMEDIATION_APPROVAL_PHRASE, next_action: "submit_a_remediation_approval_review_for_the_current_head_from_a_write_access_account" },
    );
  }
  for (const review of candidates) {
    const permission = await permissionResolver(repoRoot, owner, name, review.user.login, commandRunner);
    if (permission) {
      return { ok: true, confirmed_by: review.user.login, confirmed_permission: permission, confirmed_commit: wantHead };
    }
  }
  return refusal("pr_remediation_confirmation_unverified",
    "The remediation-approval review was not submitted by a write-access account");
}

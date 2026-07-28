// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { request } from "./api-controls-2.js";
import { createTraceabilityLink, getRequirementByUid, getTraceabilityLinks } from "./api-controls-3.js";
import { createGitHubIssue, formatIssueBody } from "./codex-workflow-3.js";
import { STATUSES } from "./constants.js";
import { getOwnerRepo, postPhaseMarker } from "./grc-legacy-compat-3.js";
import { ensureGitRepo } from "./grc-legacy-compat-4.js";
import { hasTestableSurfaceTarget } from "./pr-body.js";

export async function deleteRelation(reqId, relId) {
  await request("DELETE", `/api/v1/requirements/${encodeURIComponent(reqId)}/relations/${encodeURIComponent(relId)}`);
}
export async function deleteTraceabilityLink(reqId, linkId) {
  await request("DELETE", `/api/v1/requirements/${encodeURIComponent(reqId)}/traceability/${encodeURIComponent(linkId)}`);
}
export async function getTraceabilityByArtifact(artifactType, artifactIdentifier, project) {
  return request("GET", "/api/v1/requirements/traceability/by-artifact", {
    params: { artifactType, artifactIdentifier, project },
  });
}
export async function createGitHubIssueFromRequirement({ uid, project, repo, repoRoot, labels, extraBody }) {
  if (typeof uid !== "string" || uid.trim() === "") {
    throw new Error("createGitHubIssueFromRequirement: 'uid' is required");
  }
  // getRequirementByUid throws RequestError on 404, so a missing requirement
  // aborts before any GitHub issue is created.
  const req = await getRequirementByUid(uid, project);
  if (!req || !req.id) {
    throw new Error(`createGitHubIssueFromRequirement: requirement '${uid}' not found`);
  }

  // The issue title is a single line. The requirement title arrives as
  // `folder_title` (toSnakeCase renames `title` → `folder_title` on responses);
  // fall back to `title` for direct callers. Reuse formatIssueBody's
  // whitespace-collapse rule so a multiline/markdown title cannot inject
  // structure into the title (the body's `## Requirements` bullet is sanitized
  // the same way).
  const titleValue = req.folder_title ?? req.title;
  const titleText = titleValue ? titleValue.replace(/\s+/g, " ").trim() : "";
  const title = titleText ? `${req.uid} — ${titleText}` : req.uid;
  const body = formatIssueBody(req, extraBody);

  const { url, number } = await createGitHubIssue({ title, body, labels, repo, repoRoot });

  // Auto-link the new issue back to the requirement. ACTIVE requirements are
  // being implemented (IMPLEMENTS); everything else is being tracked/documented
  // (DOCUMENTS, per #841). A GitHub issue link is never a TESTS link. Keep this
  // as one explicit decision next to the orchestration so future DRAFT policy
  // changes don't touch title/body rendering or GitHub posting.
  const linkType = req.status === "ACTIVE" ? "IMPLEMENTS" : "DOCUMENTS";
  const result = { url, number, requirement_uid: req.uid, link_type: linkType };

  // Issue creation and link creation are two non-atomic side effects: the issue
  // already exists by the time the link is attempted. The original defect was a
  // *silent* success, so a link failure must stay visible (traceability_error)
  // rather than be swallowed — and must not discard the created issue.
  try {
    result.traceability_link = await createTraceabilityLink(req.id, {
      artifact_type: "GITHUB_ISSUE",
      artifact_identifier: String(number),
      link_type: linkType,
      artifact_url: url,
      artifact_title: title,
    });
  } catch (e) {
    result.traceability_error = e?.message || String(e);
  }

  return result;
}
export async function createGitHubIssueViaApi(data, project) {
  return request("POST", "/api/v1/admin/github/issues", { body: data, params: { project } });
}
export async function setDocumentGrammar(documentId, grammar) {
  return request("PUT", `/api/v1/documents/${encodeURIComponent(documentId)}/grammar`, { body: grammar });
}
export async function getDocumentGrammar(documentId) {
  return request("GET", `/api/v1/documents/${encodeURIComponent(documentId)}/grammar`);
}
export async function deleteDocumentGrammar(documentId) {
  await request("DELETE", `/api/v1/documents/${encodeURIComponent(documentId)}/grammar`);
}
export async function createControlTest(data, project) {
  return request("POST", "/api/v1/control-tests", { body: data, params: { project } });
}
export async function updateControlTest(id, data, project) {
  return request("PUT", `/api/v1/control-tests/${encodeURIComponent(id)}`, {
    body: data,
    params: { project },
  });
}
export async function deleteControlTest(id, project) {
  await request("DELETE", `/api/v1/control-tests/${encodeURIComponent(id)}`, { params: { project } });
}
async function evaluateRequirementTraceability({ item, index, project, issueNumber }) {
  if (!item || typeof item !== "object" || typeof item.uid !== "string" || item.uid.trim() === "") {
    return {
      earlyReturn: {
        ok: false,
        error: "traceability_input_invalid",
        message: `requirements[${index}] must be { uid: <non-empty string>, statusIntent: 'ACTIVE'|'DRAFT' }`,
        issue_number: issueNumber,
      },
    };
  }
  const statusIntent = typeof item.statusIntent === "string" ? item.statusIntent : "ACTIVE";
  if (!STATUSES.includes(statusIntent)) {
    return {
      earlyReturn: {
        ok: false,
        error: "traceability_input_invalid",
        message: `requirements[${index}].statusIntent='${statusIntent}' must be one of ${STATUSES.join(", ")}`,
        issue_number: issueNumber,
      },
    };
  }
  let requirement;
  try {
    requirement = await getRequirementByUid(item.uid, project);
  } catch (error) {
    return {
      earlyReturn: {
        ok: false,
        error: "traceability_requirement_lookup_failed",
        message: `gc_assert_traceability_reconciled could not resolve requirement ${item.uid}: ${error.message}`,
        issue_number: issueNumber,
        uid: item.uid,
      },
    };
  }
  const actualStatus = requirement && typeof requirement.status === "string" ? requirement.status : null;
  if (actualStatus !== statusIntent) {
    return {
      checkedEntry: { uid: item.uid, status: actualStatus, implements_count: 0, tests_count: 0 },
      failures: [{
        uid: item.uid,
        reason: "status_mismatch",
        expected: statusIntent,
        actual: actualStatus,
      }],
    };
  }
  let links = [];
  try {
    links = await getTraceabilityLinks(requirement.id);
  } catch (error) {
    return {
      earlyReturn: {
        ok: false,
        error: "traceability_links_lookup_failed",
        message: `gc_assert_traceability_reconciled could not fetch links for ${item.uid}: ${error.message}`,
        issue_number: issueNumber,
        uid: item.uid,
      },
    };
  }
  const linksArray = Array.isArray(links) ? links : [];
  const implementsLinks = linksArray.filter((l) => l?.link_type === "IMPLEMENTS");
  const testsLinks = linksArray.filter((l) => l?.link_type === "TESTS");
  const checkedEntry = {
    uid: item.uid,
    status: actualStatus,
    implements_count: implementsLinks.length,
    tests_count: testsLinks.length,
  };
  const failures = [];
  if (statusIntent === "ACTIVE" && implementsLinks.length === 0) {
    failures.push({ uid: item.uid, reason: "implements_missing" });
    return { checkedEntry, failures };
  }
  // DRAFT requirements are exempt from the TESTS check — by definition
  // they have no executable behavior yet to test. ACTIVE requirements
  // with an IMPLEMENTS link pointing at the testable-surface set must
  // carry at least one TESTS link.
  if (statusIntent === "ACTIVE"
    && hasTestableSurfaceTarget(implementsLinks)
    && testsLinks.length === 0) {
    failures.push({ uid: item.uid, reason: "tests_missing" });
  }
  return { checkedEntry, failures };
}
async function checkOrphanedIssueLinks(issueNumber, project) {
  try {
    const issueLinks = await getTraceabilityByArtifact("GITHUB_ISSUE", String(issueNumber), project);
    const orphaned = Array.isArray(issueLinks)
      ? issueLinks.filter((l) => l?.link_type === "IMPLEMENTS")
      : [];
    // We allow orphaned IMPLEMENTS links here: if the requirement they
    // point at no longer exists, that's a different reconciliation
    // problem (Step 16). The check we ARE running: are there any links
    // at all on an issue whose run had no requirements? If yes, the
    // agent should have either claimed them in requirements[] or
    // deleted them. We surface this as a failure so the agent loops back.
    if (orphaned.length > 0) {
      return { failures: [{ issue: issueNumber, reason: "orphaned_issue_link", count: orphaned.length }] };
    }
    return { failures: [] };
  } catch (error) {
    return {
      earlyReturn: {
        ok: false,
        error: "traceability_issue_links_lookup_failed",
        message: `gc_assert_traceability_reconciled could not fetch issue links: ${error.message}`,
        issue_number: issueNumber,
      },
    };
  }
}
export async function runAssertTraceabilityReconciled({
  repoPath,
  issueNumber,
  requirements,
  project = null,
  touchedFiles = [],
  override = false,
  overrideReason = null,
}) {
  if (issueNumber == null || !Number.isInteger(issueNumber) || issueNumber <= 0) {
    throw new Error("gc_assert_traceability_reconciled requires a positive integer issue_number");
  }
  if (!Array.isArray(requirements)) {
    throw new TypeError("gc_assert_traceability_reconciled requires requirements to be an array");
  }

  // Validated, trimmed override reason — non-null only when override===true
  // passed the gate below. Capturing it here lets later code rely on a plain
  // string and avoids re-deriving (or re-null-checking) overrideReason.
  let overrideReasonTrimmed = null;
  if (override === true) {
    if (typeof overrideReason !== "string" || overrideReason.trim() === "") {
      return {
        ok: false,
        error: "traceability_override_missing_reason",
        message:
          "override=true requires a non-empty override_reason quoting the user's authorization to skip the traceability-reconciliation gate. " +
          "Audits cannot distinguish legitimate overrides from accidents without a reason.",
        issue_number: issueNumber,
      };
    }
    overrideReasonTrimmed = overrideReason.trim();
  }

  const repoRoot = await ensureGitRepo(repoPath);

  const checked = [];
  const failures = [];

  if (override !== true) {
    for (let i = 0; i < requirements.length; i++) {
      const result = await evaluateRequirementTraceability({
        item: requirements[i],
        index: i,
        project,
        issueNumber,
      });
      if (result.earlyReturn) return result.earlyReturn;
      if (result.checkedEntry) checked.push(result.checkedEntry);
      if (result.failures) failures.push(...result.failures);
    }

    if (requirements.length === 0) {
      const orphanResult = await checkOrphanedIssueLinks(issueNumber, project);
      if (orphanResult.earlyReturn) return orphanResult.earlyReturn;
      failures.push(...orphanResult.failures);
    }
  }

  if (failures.length > 0) {
    return {
      ok: false,
      error: "traceability_not_reconciled",
      message:
        `Traceability reconciliation gate failed for issue #${issueNumber}: ` +
        failures.map((f) => `${f.uid ?? "issue"}:${f.reason}`).join(", "),
      issue_number: issueNumber,
      failures,
      checked,
      next_action: "fix_traceability_in_step_16_and_retry",
    };
  }

  const summaryLines = [];
  if (override === true) {
    summaryLines.push(`_override=true; reason: ${overrideReasonTrimmed}_`);
  } else if (requirements.length === 0) {
    summaryLines.push(`_no in-scope requirements; touched-files audit clean._`);
  } else {
    for (const c of checked) {
      summaryLines.push(`- ${c.uid}: status=${c.status}, IMPLEMENTS=${c.implements_count}, TESTS=${c.tests_count}`);
    }
  }
  const summary = summaryLines.join("\n");

  const { owner, name } = await getOwnerRepo(repoRoot);
  const apiResponse = await postPhaseMarker(repoRoot, owner, name, issueNumber, "traceability_reconciled", {
    commentBody: summary,
  });

  return {
    repo_path: repoRoot,
    issue_number: issueNumber,
    ok: true,
    phase_marker: { phase: "traceability_reconciled", issue_number: issueNumber },
    override: override === true,
    override_reason: override === true ? overrideReasonTrimmed : null,
    checked,
    comment_url: apiResponse && typeof apiResponse.html_url === "string" ? apiResponse.html_url : null,
    comment_id: apiResponse && Number.isInteger(apiResponse.id) ? apiResponse.id : null,
  };
}

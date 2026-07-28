// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { lstatSync, readFileSync, realpathSync, statSync } from "node:fs";
import { basename, isAbsolute, relative } from "node:path";
import { execFile, formatCommandFailure } from "./runtime-primitives.js";

export function isPathStrictlyInside(canonicalRoot, canonicalPath) {
  const rel = relative(canonicalRoot, canonicalPath);
  return rel !== "" && !rel.startsWith("..") && !isAbsolute(rel);
}
function normalizeAllowedExtension(rawExt, field) {
  if (typeof rawExt !== "string" || rawExt.length === 0) {
    throw new Error(`${field}: every allowed extension must be a non-empty string`);
  }
  if (rawExt.indexOf("/") !== -1 || rawExt.indexOf("\\") !== -1 || rawExt.indexOf("\0") !== -1) {
    throw new Error(`${field}: extension must not contain path separators or NUL`);
  }
  if (rawExt[0] !== ".") {
    throw new Error(`${field}: extension must start with '.' (got '${rawExt}')`);
  }
  if (rawExt.length < 2) {
    throw new Error(`${field}: extension must include characters after '.'`);
  }
  return rawExt.toLowerCase();
}
export function readApprovedUploadFile(
  rawPath,
  { workspaceRoot, allowedExtensions, fieldName } = {},
) {
  const field = fieldName || "file_path";
  if (typeof workspaceRoot !== "string" || workspaceRoot.length === 0) {
    throw new Error(`${field}: workspaceRoot must be a non-empty string`);
  }
  if (!Array.isArray(allowedExtensions) || allowedExtensions.length === 0) {
    throw new Error(`${field}: at least one allowed extension is required`);
  }
  // Validate each entry up front so a misconfigured caller (e.g.
  // `allowedExtensions: [""]` or `["json"]`) fails closed before any
  // filesystem check could match more than the caller intended.
  const normalizedExtensions = allowedExtensions.map((ext) => normalizeAllowedExtension(ext, field));

  if (typeof rawPath !== "string" || rawPath.length === 0) {
    throw new Error(`${field}: must be a non-empty string`);
  }
  if (rawPath.indexOf("\0") !== -1) {
    throw new Error(`${field}: must not contain NUL bytes`);
  }
  if (!isAbsolute(rawPath)) {
    throw new Error(`${field}: must be an absolute path`);
  }

  const lowerName = basename(rawPath).toLowerCase();
  const extOk = normalizedExtensions.some((ext) => lowerName.endsWith(ext));
  if (!extOk) {
    throw new Error(
      `${field}: must have one of these extensions: ${normalizedExtensions.join(", ")}`,
    );
  }

  // lstat first: rejects when the leaf itself is a symlink, before realpath
  // would silently follow it. Also surfaces ENOENT as a stable validation
  // error rather than leaking through readFileSync.
  let leafStat;
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- rawPath is validated operator input being inspected pre-read
    leafStat = lstatSync(rawPath);
  } catch (err) {
    if (err && err.code === "ENOENT") {
      throw new Error(`${field}: file does not exist`);
    }
    throw new Error(`${field}: cannot stat path (${err && err.code ? err.code : "unknown"})`);
  }
  if (leafStat.isSymbolicLink()) {
    throw new Error(`${field}: must not be a symlink`);
  }

  // Realpath the workspace root and the target so ancestor symlinks resolve
  // before the containment check. A workspace path that itself is a symlink
  // resolves to its real location; the target's canonical path must lie
  // strictly inside that real workspace.
  let canonicalRoot;
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- workspaceRoot canonicalized for containment check
    canonicalRoot = realpathSync(workspaceRoot);
  } catch (err) {
    throw new Error(
      `${field}: workspaceRoot could not be canonicalized (${err && err.code ? err.code : "unknown"})`,
    );
  }
  let canonicalPath;
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- rawPath validated above; realpath needed for containment check
    canonicalPath = realpathSync(rawPath);
  } catch (err) {
    if (err && err.code === "ENOENT") {
      throw new Error(`${field}: file does not exist`);
    }
    throw new Error(
      `${field}: could not be canonicalized (${err && err.code ? err.code : "unknown"})`,
    );
  }
  if (!isPathStrictlyInside(canonicalRoot, canonicalPath)) {
    throw new Error(`${field}: must be contained inside the workspace root`);
  }

  // stat after containment: must be a regular file. This rejects
  // directories, FIFOs, devices, and sockets — file kinds whose read
  // semantics surprise upload callers and can block or hang the MCP.
  let finalStat;
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- canonicalPath is the validator-approved path
    finalStat = statSync(canonicalPath);
  } catch (err) {
    if (err && err.code === "ENOENT") {
      throw new Error(`${field}: file does not exist`);
    }
    throw new Error(
      `${field}: cannot stat resolved path (${err && err.code ? err.code : "unknown"})`,
    );
  }
  if (!finalStat.isFile()) {
    throw new Error(`${field}: must be a regular file`);
  }

  let bytes;
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- canonicalPath is the validator-approved path
    bytes = readFileSync(canonicalPath);
  } catch (err) {
    if (err && err.code === "EACCES") {
      throw new Error(`${field}: permission denied`);
    }
    if (err && err.code === "ENOENT") {
      throw new Error(`${field}: file does not exist`);
    }
    throw new Error(
      `${field}: cannot read file (${err && err.code ? err.code : "unknown"})`,
    );
  }
  return { absPath: canonicalPath, basename: basename(rawPath), bytes };
}
export function readAbsoluteTextFile(filePath) {
  if (!filePath || !isAbsolute(filePath)) {
    throw new Error("file_path must be an absolute path");
  }

  // eslint-disable-next-line security/detect-non-literal-fs-filename -- file_path is validated absolute input
  return readFileSync(filePath, "utf8");
}
export async function resolveUploadWorkspaceRoot() {
  const cwd = process.cwd();
  let stdout;
  try {
    ({ stdout } = await execFile("git", ["-C", cwd, "rev-parse", "--show-toplevel"]));
  } catch (error) {
    throw new Error(
      `upload workspace root could not be resolved: launch the MCP from inside a Git repository (${formatCommandFailure("git", error)})`,
    );
  }
  const root = stdout.trim();
  if (!root) {
    throw new Error(
      "upload workspace root could not be resolved: launch the MCP from inside a Git repository",
    );
  }
  return root;
}
export const CODEX_REVIEW_PREPUSH_HARD_CAP = 1;
export const CODEX_REVIEW_PREPUSH_MARKER_PREFIX = "<!-- gc:codex-prepush-cycle";
const CODEX_REVIEW_PREPUSH_MARKER_RE =
  /<!--\s*gc:codex-prepush-cycle\s+issue="(\d+)"\s+branch="((?:[^"\\]|\\.)*)"\s+cycle="(\d+)"[^]*?-->/g;
export function deriveIssueNumberFromBranch(branchName) {
  if (typeof branchName !== "string" || branchName === "") return null;
  const match = branchName.match(/^(\d+)(?:-|$)/);
  if (!match) return null;
  const n = Number.parseInt(match[1], 10);
  if (!Number.isInteger(n) || n <= 0) return null;
  return n;
}
export function parseCodexReviewPrePushCycleMarkers(commentBodies, issueNumber) {
  if (!Array.isArray(commentBodies)) return 0;
  let count = 0;
  for (const body of commentBodies) {
    if (typeof body !== "string") continue;
    for (const m of body.matchAll(CODEX_REVIEW_PREPUSH_MARKER_RE)) {
      const markerIssue = Number.parseInt(m[1], 10);
      if (markerIssue !== issueNumber) continue;
      // Validate branch attr is JSON-decodable so malformed markers don't
      // pollute counts. We don't compare it against any specific branch; the
      // attribute is audit-only context.
      try {
        JSON.parse(`"${m[2]}"`);
      } catch {
        continue;
      }
      count += 1;
    }
  }
  return count;
}
export function evaluateCodexReviewPrePushCycleCap({
  priorCount,
  issueNumber,
  branchName,
  hardCap = CODEX_REVIEW_PREPUSH_HARD_CAP,
  overrideCap = false,
  overrideReason = null,
}) {
  if (typeof priorCount !== "number" || !Number.isFinite(priorCount) || priorCount < 0) {
    throw new Error(
      `evaluateCodexReviewPrePushCycleCap: priorCount must be a non-negative number, got ${priorCount}`,
    );
  }

  if (overrideCap === true) {
    if (typeof overrideReason !== "string" || overrideReason.trim() === "") {
      return {
        ok: false,
        error: "codex_review_prepush_override_missing_reason",
        message:
          "override_cap=true requires a non-empty override_reason quoting the user's authorization. " +
          "Audits cannot distinguish legitimate overrides from accidental ones without a reason.",
        issue_number: issueNumber,
        branch: branchName,
        prior_cycles: priorCount,
        cap: hardCap,
      };
    }
    return {
      ok: true,
      nextCycle: priorCount + 1,
      cap: hardCap,
      override: true,
      override_reason: overrideReason.trim(),
      next_action: "fix_findings_then_summarize_and_escalate",
    };
  }

  if (priorCount >= hardCap) {
    return {
      ok: false,
      error: "codex_review_prepush_cap_reached",
      message:
        `gc_codex_review pre-push hard cap reached (${hardCap} cycles) for issue #${issueNumber} ` +
        `on branch '${branchName}'. Per GC-O007 / ADR-029, after cycle ${hardCap} you must (a) post a ` +
        `summary of findings + fixes to the issue thread, then (b) escalate to the user and ask whether ` +
        `to run cycle ${hardCap + 1} or push as-is. Do not address findings by silently re-invoking ` +
        `codex. If the user authorizes another cycle, retry with override_cap=true and ` +
        `override_reason="<their authorization>".`,
      issue_number: issueNumber,
      branch: branchName,
      prior_cycles: priorCount,
      cap: hardCap,
      next_action: "post_summary_and_escalate_to_user",
    };
  }

  const nextCycle = priorCount + 1;
  return {
    ok: true,
    nextCycle,
    cap: hardCap,
    next_action:
      nextCycle === hardCap
        ? "fix_all_findings_then_summarize_and_escalate"
        : "fix_all_findings_and_restage",
  };
}
export function buildCodexReviewPrePushCycleMarker({
  issueNumber,
  branchName,
  cycleNumber,
  override = false,
  overrideReason = null,
  // The effective cap that gated this cycle. Defaults to the module constant
  // so legacy callers that don't pass it stay correct; new callers (issue #906)
  // pass the cfg-resolved cap so the marker headline reflects what the run
  // actually enforced.
  hardCap = CODEX_REVIEW_PREPUSH_HARD_CAP,
}) {
  const branchAttr = JSON.stringify(String(branchName)).slice(1, -1); // raw inner JSON-encoded form
  const overrideAttr = override === true ? ' override="true"' : "";
  const reasonAttr =
    override === true && typeof overrideReason === "string" && overrideReason.trim() !== ""
      ? ` reason=${JSON.stringify(overrideReason.trim())}`
      : "";
  const headline = override
    ? `_gc_codex_review pre-push cycle ${cycleNumber} (USER-AUTHORIZED OVERRIDE past cap ${hardCap}) complete for issue #${issueNumber} on branch '${branchName}'._`
    : `_gc_codex_review pre-push cycle ${cycleNumber} of ${hardCap} complete for issue #${issueNumber} on branch '${branchName}'._`;
  const reasonLine =
    override && typeof overrideReason === "string" && overrideReason.trim() !== ""
      ? `\nOverride reason: ${overrideReason.trim()}`
      : "";
  return [
    `${CODEX_REVIEW_PREPUSH_MARKER_PREFIX} issue="${issueNumber}" branch="${branchAttr}" cycle="${cycleNumber}"${overrideAttr}${reasonAttr} -->`,
    "",
    headline +
      ` Posted by the MCP server to enforce the pre-push hard-cap-${hardCap} contract (issues #796, #804, #906). ` +
      "Do not edit or delete — used by the next `gc_codex_review` (uncommitted) invocation to count cycles." +
      reasonLine,
  ].join("\n");
}

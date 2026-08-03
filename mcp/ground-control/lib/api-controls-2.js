// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { closeSync, fsyncSync, mkdirSync, openSync, renameSync, rmSync, writeSync } from "node:fs";
import { isAbsolute, join, relative, resolve as resolvePath } from "node:path";
import { randomBytes } from "node:crypto";
import { dump as dumpYaml } from "js-yaml";
import { toCamelCase, toSnakeCase } from "./close-issue.js";
import { buildInboxSlug, defaultSpawnIngest, formatInboxTimestamp, formatSourceCitation } from "./knowledge-capture.js";
import { getRepoGroundControlContext } from "./repo-vocabulary-2.js";
import { RequestError, addAuthorizationHeader, buildUrl, parseErrorBody } from "./test-quality-prompt.js";

export async function request(method, path, { body, rawBody, params, formData, signal } = {}) {
  const url = buildUrl(path, params);
  const options = { method };
  // Optional caller-supplied abort. Fail-open emitters bound their writes so a hung backend cannot
  // stall the operation they are observing; every other caller passes nothing and is unaffected.
  if (signal) options.signal = signal;

  if (formData) {
    options.headers = { "X-Actor": "mcp-server" };
    options.body = formData;
    // Let fetch set Content-Type with boundary for multipart
  } else if (rawBody !== undefined) {
    // Pre-built camelCase object — skip toCamelCase() (used when the body
    // contains opaque-map fields whose inner keys must not be transformed).
    options.headers = { "Content-Type": "application/json", "X-Actor": "mcp-server" };
    options.body = JSON.stringify(rawBody);
  } else if (body !== undefined) {
    options.headers = { "Content-Type": "application/json", "X-Actor": "mcp-server" };
    options.body = JSON.stringify(toCamelCase(body));
  } else {
    options.headers = { "X-Actor": "mcp-server" };
  }
  addAuthorizationHeader(path, options.headers);

  const res = await fetch(url, options);

  if (res.status === 204) return null;

  // Refuse an event stream before reading it (issue #1436). `res.text()` on a live SSE response
  // never resolves — the connection stays open by design and heartbeats keep it from even failing
  // idle — so a streaming endpoint reached through this client would hang the MCP server outright
  // rather than erroring. gc_query denylists the one such path that exists today; this guard is
  // what keeps the next one from being an unbounded hang instead of a clear failure.
  const contentType = res.headers?.get?.("content-type") ?? "";
  if (contentType.toLowerCase().includes("text/event-stream")) {
    throw new RequestError({
      status: res.status,
      code: "unsupported_media_type",
      message: `${path} returns an event stream, which this client cannot consume`,
      detail: null,
    });
  }

  const text = await res.text();

  if (!res.ok) {
    const envelope = parseErrorBody(text);
    throw new RequestError({
      status: res.status,
      code: envelope.code,
      message: envelope.message,
      detail: envelope.detail,
    });
  }

  const data = text ? JSON.parse(text) : null;
  return toSnakeCase(data);
}
export async function createTestCase(data, project) {
  return request("POST", "/api/v1/test-cases", { body: data, params: { project } });
}
export async function listTestCases(project) {
  return request("GET", "/api/v1/test-cases", { params: { project } });
}
export async function getTestCase(id, project) {
  return request("GET", `/api/v1/test-cases/${encodeURIComponent(id)}`, { params: { project } });
}
export async function getTestCaseByUid(uid, project) {
  return request("GET", `/api/v1/test-cases/uid/${encodeURIComponent(uid)}`, { params: { project } });
}
export async function updateTestCase(id, data, project) {
  return request("PUT", `/api/v1/test-cases/${encodeURIComponent(id)}`, { body: data, params: { project } });
}
export async function deleteTestCase(id, project) {
  await request("DELETE", `/api/v1/test-cases/${encodeURIComponent(id)}`, { params: { project } });
}
export async function transitionTestCaseStatus(id, status, project) {
  return request("PUT", `/api/v1/test-cases/${encodeURIComponent(id)}/status`, {
    body: { status },
    params: { project },
  });
}
export async function createTestCaseStep(testCaseId, data, project) {
  return request("POST", `/api/v1/test-cases/${encodeURIComponent(testCaseId)}/steps`, {
    body: data,
    params: { project },
  });
}
export async function updateTestCaseStep(testCaseId, stepId, data, project) {
  return request(
    "PUT",
    `/api/v1/test-cases/${encodeURIComponent(testCaseId)}/steps/${encodeURIComponent(stepId)}`,
    { body: data, params: { project } },
  );
}
export async function deleteTestCaseStep(testCaseId, stepId, project) {
  await request(
    "DELETE",
    `/api/v1/test-cases/${encodeURIComponent(testCaseId)}/steps/${encodeURIComponent(stepId)}`,
    { params: { project } },
  );
}
export async function createTestCaseGherkin(testCaseId, data, project) {
  return request("POST", `/api/v1/test-cases/${encodeURIComponent(testCaseId)}/gherkin`, {
    body: data,
    params: { project },
  });
}
export async function getTestCaseGherkin(testCaseId, project) {
  return request("GET", `/api/v1/test-cases/${encodeURIComponent(testCaseId)}/gherkin`, {
    params: { project },
  });
}
export async function updateTestCaseGherkin(testCaseId, data, project) {
  return request("PUT", `/api/v1/test-cases/${encodeURIComponent(testCaseId)}/gherkin`, {
    body: data,
    params: { project },
  });
}
export async function deleteTestCaseGherkin(testCaseId, project) {
  await request("DELETE", `/api/v1/test-cases/${encodeURIComponent(testCaseId)}/gherkin`, {
    params: { project },
  });
}
export async function createTestCaseFolder(data, project) {
  return request("POST", "/api/v1/test-cases/folders", { body: data, params: { project } });
}
export async function updateTestCaseFolder(id, data, project) {
  return request("PUT", `/api/v1/test-cases/folders/${encodeURIComponent(id)}`, {
    body: data,
    params: { project },
  });
}
export async function deleteTestCaseFolder(id, project) {
  await request("DELETE", `/api/v1/test-cases/folders/${encodeURIComponent(id)}`, { params: { project } });
}
export async function moveTestCaseFolder(id, data, project) {
  return request("PUT", `/api/v1/test-cases/folders/${encodeURIComponent(id)}/move`, {
    body: data,
    params: { project },
  });
}
export async function reorderTestCaseFolders(data, project) {
  await request("PUT", "/api/v1/test-cases/folders/reorder", { body: data, params: { project } });
}
export async function moveTestCase(id, data, project) {
  return request("PUT", `/api/v1/test-cases/${encodeURIComponent(id)}/move`, {
    body: data,
    params: { project },
  });
}
export async function copyTestCase(id, data, project) {
  return request("POST", `/api/v1/test-cases/${encodeURIComponent(id)}/copy`, {
    body: data,
    params: { project },
  });
}
export async function reorderTestCases(data, project) {
  await request("PUT", "/api/v1/test-cases/reorder", { body: data, params: { project } });
}
export async function createTestPlan(data, project) {
  return request("POST", "/api/v1/test-plans", { body: data, params: { project } });
}
export async function listTestPlans(project) {
  return request("GET", "/api/v1/test-plans", { params: { project } });
}
export async function getTestPlan(id, project) {
  return request("GET", `/api/v1/test-plans/${encodeURIComponent(id)}`, { params: { project } });
}
export async function getTestPlanByUid(uid, project) {
  return request("GET", `/api/v1/test-plans/uid/${encodeURIComponent(uid)}`, { params: { project } });
}
export async function updateTestPlan(id, data, project) {
  return request("PUT", `/api/v1/test-plans/${encodeURIComponent(id)}`, { body: data, params: { project } });
}
export async function deleteTestPlan(id, project) {
  await request("DELETE", `/api/v1/test-plans/${encodeURIComponent(id)}`, { params: { project } });
}
export async function transitionTestPlanStatus(id, status, project) {
  return request("PUT", `/api/v1/test-plans/${encodeURIComponent(id)}/status`, {
    body: { status },
    params: { project },
  });
}
export async function createTestSuite(data, project) {
  return request("POST", "/api/v1/test-suites", { body: data, params: { project } });
}
export async function listTestSuites(project) {
  return request("GET", "/api/v1/test-suites", { params: { project } });
}
export async function getTestSuite(id, project) {
  return request("GET", `/api/v1/test-suites/${encodeURIComponent(id)}`, { params: { project } });
}
export async function getTestSuiteByUid(uid, project) {
  return request("GET", `/api/v1/test-suites/uid/${encodeURIComponent(uid)}`, { params: { project } });
}
export async function updateTestSuite(id, data, project) {
  return request("PUT", `/api/v1/test-suites/${encodeURIComponent(id)}`, { body: data, params: { project } });
}
export async function deleteTestSuite(id, project) {
  await request("DELETE", `/api/v1/test-suites/${encodeURIComponent(id)}`, { params: { project } });
}
export async function resolveTestSuiteTestCases(id, project) {
  return request("GET", `/api/v1/test-suites/${encodeURIComponent(id)}/test-cases`, { params: { project } });
}
export async function addTestSuiteMember(id, data, project) {
  return request("POST", `/api/v1/test-suites/${encodeURIComponent(id)}/members`, {
    body: data,
    params: { project },
  });
}
export async function removeTestSuiteMember(id, testCaseId, project) {
  await request(
    "DELETE",
    `/api/v1/test-suites/${encodeURIComponent(id)}/members/${encodeURIComponent(testCaseId)}`,
    { params: { project } },
  );
}
export async function reorderTestSuiteMembers(id, orderedTestCaseIds, project) {
  return request("PUT", `/api/v1/test-suites/${encodeURIComponent(id)}/members/reorder`, {
    body: { orderedTestCaseIds },
    params: { project },
  });
}
export async function addTestSuiteSourceRequirement(id, data, project) {
  return request("POST", `/api/v1/test-suites/${encodeURIComponent(id)}/source-requirements`, {
    body: data,
    params: { project },
  });
}
export async function removeTestSuiteSourceRequirement(id, requirementId, project) {
  await request(
    "DELETE",
    `/api/v1/test-suites/${encodeURIComponent(id)}/source-requirements/${encodeURIComponent(requirementId)}`,
    { params: { project } },
  );
}
export async function createTestRun(data, project) {
  return request("POST", "/api/v1/test-runs", { body: data, params: { project } });
}
export async function listTestRuns(project) {
  return request("GET", "/api/v1/test-runs", { params: { project } });
}
export async function getTestRun(id, project) {
  return request("GET", `/api/v1/test-runs/${encodeURIComponent(id)}`, { params: { project } });
}
export async function getTestRunByUid(uid, project) {
  return request("GET", `/api/v1/test-runs/uid/${encodeURIComponent(uid)}`, { params: { project } });
}
export async function updateTestRun(id, data, project) {
  return request("PUT", `/api/v1/test-runs/${encodeURIComponent(id)}`, { body: data, params: { project } });
}
export async function deleteTestRun(id, project) {
  await request("DELETE", `/api/v1/test-runs/${encodeURIComponent(id)}`, { params: { project } });
}
export async function transitionTestRunStatus(id, status, project) {
  return request("PUT", `/api/v1/test-runs/${encodeURIComponent(id)}/status`, {
    body: { status },
    params: { project },
  });
}
export async function addTestRunTester(id, testerName, project) {
  return request("POST", `/api/v1/test-runs/${encodeURIComponent(id)}/testers`, {
    body: { testerName },
    params: { project },
  });
}
export async function listTestRunTesters(id, project) {
  return request("GET", `/api/v1/test-runs/${encodeURIComponent(id)}/testers`, { params: { project } });
}
export async function removeTestRunTester(id, testerName, project) {
  await request(
    "DELETE",
    `/api/v1/test-runs/${encodeURIComponent(id)}/testers/${encodeURIComponent(testerName)}`,
    { params: { project } },
  );
}
export async function listTestRunCaseResults(id, project) {
  return request("GET", `/api/v1/test-runs/${encodeURIComponent(id)}/results`, { params: { project } });
}
export async function updateTestRunCaseResult(id, testCaseId, data, project) {
  return request(
    "PUT",
    `/api/v1/test-runs/${encodeURIComponent(id)}/results/${encodeURIComponent(testCaseId)}`,
    { body: data, params: { project } },
  );
}
export async function listTestRunStepResults(id, caseResultId, project) {
  return request(
    "GET",
    `/api/v1/test-runs/${encodeURIComponent(id)}/results/${encodeURIComponent(caseResultId)}/steps`,
    { params: { project } },
  );
}
export async function updateTestRunStepResult(id, caseResultId, stepResultId, data, project) {
  return request(
    "PUT",
    `/api/v1/test-runs/${encodeURIComponent(id)}/results/${encodeURIComponent(caseResultId)}/steps/${encodeURIComponent(stepResultId)}`,
    { body: data, params: { project } },
  );
}
export async function updateTestRunCursor(id, data, project) {
  return request("PUT", `/api/v1/test-runs/${encodeURIComponent(id)}/cursor`, {
    body: data,
    params: { project },
  });
}
export async function startResearchRun(data, project) {
  return request("POST", "/api/v1/research-runs", { body: data, params: { project } });
}
export async function listResearchRuns(project) {
  return request("GET", "/api/v1/research-runs", { params: { project } });
}
export async function getResearchRun(id, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(id)}`, { params: { project } });
}
export async function getResearchRunByUid(uid, project) {
  return request("GET", `/api/v1/research-runs/uid/${encodeURIComponent(uid)}`, { params: { project } });
}
export async function getResearchRunSnapshot(id, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(id)}/snapshot`, { params: { project } });
}
export async function listResearchRunArtifacts(id, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(id)}/artifacts`, { params: { project } });
}
export async function listResearchRunGates(id, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(id)}/gates`, { params: { project } });
}
export async function recordResearchRunArtifact(id, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/artifacts`, {
    body: data,
    params: { project },
  });
}
export const IDENTITY_ADMIN_ROOT = "/api/v1/admin/identity";
export async function writeKnowledgeInbox({
  repoPath,
  note,
  sourceType,
  sourceRef,
  tags = [],
  spawnIngest = defaultSpawnIngest,
} = {}) {
  if (typeof repoPath !== "string" || !isAbsolute(repoPath)) {
    return { ok: false, error: "repo_path must be an absolute path to a Git repository" };
  }
  if (typeof note !== "string" || note.trim() === "") {
    return { ok: false, error: "note is required and must be a non-empty string" };
  }
  if (tags != null && !Array.isArray(tags)) {
    return { ok: false, error: "tags must be an array of strings when set" };
  }

  const citationResult = formatSourceCitation({ sourceType, sourceRef });
  if (!citationResult.ok) return { ok: false, error: citationResult.error };

  let context;
  try {
    context = await getRepoGroundControlContext(repoPath);
  } catch (error) {
    return { ok: false, error: `failed to resolve repo context: ${error.message}` };
  }
  if (context.status !== "ok") {
    return {
      ok: false,
      error: `repository is not ready for knowledge capture: ${context.errors?.[0] || context.status}`,
    };
  }
  if (context.knowledge == null) {
    return {
      ok: false,
      error: "repository has no 'knowledge' block in .ground-control.yaml — capture is not configured",
    };
  }

  const repoRoot = context.repo_path;
  const knowledge = context.knowledge;
  const inboxRel = knowledge.inbox;
  const absInboxDir = resolvePath(repoRoot, inboxRel);

  // Lazy-create the inbox directory on first capture. The inbox is
  // deliberately not committed as part of the #522 skeleton because an
  // empty directory has nothing to commit.
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- absInboxDir derives from a realpath-contained, resolved knowledge.inbox
    mkdirSync(absInboxDir, { recursive: true });
  } catch (error) {
    return { ok: false, error: `failed to create inbox directory ${inboxRel}: ${error.message}` };
  }

  // Compose the filename: ISO timestamp + 4-char random suffix + slug.
  // The random suffix protects against sub-second concurrent captures
  // producing identical timestamps; the slug keeps the file human-scanable.
  const timestamp = formatInboxTimestamp();
  const slug = buildInboxSlug(note);
  const rand = randomBytes(3).toString("hex").slice(0, 4);
  const filename = `${timestamp}-${rand}-${slug}.md`;
  const absInboxFile = join(absInboxDir, filename);

  // Build the frontmatter + body. js-yaml dump auto-quotes scalars that
  // need escaping so citations containing `:` or special chars are safe.
  const frontmatter = {
    captured_at: new Date().toISOString(),
    source: citationResult.citation,
  };
  if (tags && tags.length > 0) {
    frontmatter.tags = tags;
  }
  const yamlBlock = dumpYaml(frontmatter, { lineWidth: -1, noRefs: true });
  const fileContent = `---\n${yamlBlock}---\n\n${note.trim()}\n`;

  // Atomic write: temp file + fsync + rename. A crash between the write
  // and the rename leaves a .tmp sidecar but no partial file at the final
  // path, so readers never observe a half-written inbox entry.
  const tmpPath = `${absInboxFile}.tmp`;
  let fd;
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- tmpPath derives from inboxDir which is repo-relative
    fd = openSync(tmpPath, "wx");
    writeSync(fd, fileContent);
    fsyncSync(fd);
  } catch (error) {
    if (fd != null) {
      try { closeSync(fd); } catch { /* best-effort */ }
    }
    try {
      rmSync(tmpPath, { force: true });
    } catch { /* best-effort cleanup */ }
    return { ok: false, error: `failed to write inbox tmp file: ${error.message}` };
  }
  try {
    closeSync(fd);
  } catch (error) {
    return { ok: false, error: `failed to close inbox tmp file: ${error.message}` };
  }
  try {
    // eslint-disable-next-line security/detect-non-literal-fs-filename -- both paths are under absInboxDir which is realpath-contained within repoRoot
    renameSync(tmpPath, absInboxFile);
  } catch (error) {
    try {
      rmSync(tmpPath, { force: true });
    } catch { /* best-effort cleanup */ }
    return { ok: false, error: `failed to rename inbox tmp file: ${error.message}` };
  }

  const inboxRelFromRepo = relative(repoRoot, absInboxFile);

  // Spawn the detached ingest subprocess. Spawn failures do not fail the
  // capture — the inbox entry is durable and will be retried by a later
  // real-time call, manual retry, or scheduled sweep.
  let warning = null;
  try {
    spawnIngest({
      repoRoot,
      inboxFilePath: absInboxFile,
      knowledge,
    });
  } catch (error) {
    warning = `ingest_spawn_failed: ${error.message}`;
  }

  const result = {
    ok: true,
    inbox_path: inboxRelFromRepo,
    citation: citationResult.citation,
  };
  if (warning) result.warning = warning;
  return result;
}

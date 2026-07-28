// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { request } from "./api-controls-2.js";
import { readApprovedUploadFile, resolveUploadWorkspaceRoot } from "./api-requirements.js";

export async function getRequirementByUid(uid, project) {
  return request("GET", `/api/v1/requirements/uid/${encodeURIComponent(uid)}`, {
    params: { project },
  });
}
export async function getRequirement(id) {
  return request("GET", `/api/v1/requirements/${encodeURIComponent(id)}`);
}
export async function listRequirements({ status, type, priority, wave, search, page, size, sort, project } = {}) {
  return request("GET", "/api/v1/requirements", {
    params: { status, type, priority, wave, search, page, size, sort, project },
  });
}
export async function getTraceabilityMatrix({ project, status, wave, linkType, page, size } = {}) {
  return request("GET", "/api/v1/requirements/matrix", {
    params: { project, status, wave, linkType, page, size },
  });
}
export async function createRequirement(data, project) {
  // The backend expects camelCase `uidPrefix`; snake_case `uid_prefix` from MCP args
  // is converted by the toCamelCase helper inside request() when the body is serialised.
  return request("POST", "/api/v1/requirements", { body: data, params: { project } });
}
export async function updateRequirement(id, data) {
  return request("PUT", `/api/v1/requirements/${encodeURIComponent(id)}`, { body: data });
}
export async function transitionStatus(id, status, reason) {
  const body = { status };
  if (reason) body.reason = reason;
  return request("POST", `/api/v1/requirements/${encodeURIComponent(id)}/transition`, { body });
}
export async function archiveRequirement(id) {
  return request("POST", `/api/v1/requirements/${encodeURIComponent(id)}/archive`);
}
export async function bulkTransitionStatus(ids, status, reason) {
  const body = { ids, status };
  if (reason) body.reason = reason;
  return request("POST", "/api/v1/requirements/bulk/transition", { body });
}
export async function cloneRequirement(id, newUid, copyRelations) {
  return request("POST", `/api/v1/requirements/${encodeURIComponent(id)}/clone`, {
    body: { new_uid: newUid, copy_relations: copyRelations },
  });
}
export async function getRelations(id) {
  return request("GET", `/api/v1/requirements/${encodeURIComponent(id)}/relations`);
}
export async function createRelation(sourceId, targetId, relationType) {
  return request("POST", `/api/v1/requirements/${encodeURIComponent(sourceId)}/relations`, {
    body: { target_id: targetId, relation_type: relationType },
  });
}
export async function getTraceabilityLinks(id) {
  return request("GET", `/api/v1/requirements/${encodeURIComponent(id)}/traceability`);
}
export async function createTraceabilityLink(requirementId, data) {
  return request(
    "POST",
    `/api/v1/requirements/${encodeURIComponent(requirementId)}/traceability`,
    { body: data },
  );
}
export async function detectCycles(project) {
  return request("GET", "/api/v1/analysis/cycles", { params: { project } });
}
export async function findOrphans(project) {
  return request("GET", "/api/v1/analysis/orphans", { params: { project } });
}
export async function findCoverageGaps(linkType, project) {
  return request("GET", "/api/v1/analysis/coverage-gaps", {
    params: { linkType, project },
  });
}
export async function impactAnalysis(id) {
  return request("GET", `/api/v1/analysis/impact/${encodeURIComponent(id)}`);
}
export async function crossWaveValidation(project) {
  return request("GET", "/api/v1/analysis/cross-wave", { params: { project } });
}
export async function detectConsistencyViolations(project) {
  return request("GET", "/api/v1/analysis/consistency-violations", { params: { project } });
}
export async function analyzeCompleteness(project) {
  return request("GET", "/api/v1/analysis/completeness", { params: { project } });
}
export async function analyzeStatusDrift(project, minimumConfidence) {
  return request("GET", "/api/v1/analysis/status-drift", {
    params: { project, minimumConfidence },
  });
}
export async function getDashboardStats(project) {
  return request("GET", "/api/v1/analysis/dashboard-stats", { params: { project } });
}
export async function getWorkOrder(project) {
  return request("GET", "/api/v1/analysis/work-order", { params: { project } });
}
export async function importStrictdoc(filePath, project) {
  const workspaceRoot = await resolveUploadWorkspaceRoot();
  const { bytes, basename: name } = readApprovedUploadFile(filePath, {
    workspaceRoot,
    allowedExtensions: [".sdoc"],
    fieldName: "file_path",
  });
  const form = new FormData();
  form.append("file", new Blob([bytes]), name);
  const params = {};
  if (project) params.project = project;
  return request("POST", "/api/v1/admin/import/strictdoc", { formData: form, params });
}
export async function importReqif(filePath, project) {
  const workspaceRoot = await resolveUploadWorkspaceRoot();
  const { bytes, basename: name } = readApprovedUploadFile(filePath, {
    workspaceRoot,
    allowedExtensions: [".reqif"],
    fieldName: "file_path",
  });
  const form = new FormData();
  form.append("file", new Blob([bytes]), name);
  const params = {};
  if (project) params.project = project;
  return request("POST", "/api/v1/admin/import/reqif", { formData: form, params });
}
export async function syncGithub(owner, repo) {
  return request("POST", "/api/v1/admin/sync/github", {
    params: { owner, repo },
  });
}
export async function syncGithubPrs(owner, repo) {
  return request("POST", "/api/v1/admin/sync/github/prs", {
    params: { owner, repo },
  });
}
export async function advanceResearchRun(id, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/advance`, {
    body: data,
    params: { project },
  });
}
export async function recordResearchProvenanceNode(runId, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(runId)}/provenance/nodes`, {
    body: data,
    params: { project },
  });
}
export async function recordResearchProvenanceEdge(runId, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(runId)}/provenance/edges`, {
    body: data,
    params: { project },
  });
}
export async function listResearchProvenanceNodes(runId, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(runId)}/provenance/nodes`, {
    params: { project },
  });
}
export async function listResearchProvenanceEdges(runId, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(runId)}/provenance/edges`, {
    params: { project },
  });
}
export async function getResearchProvenanceChain(runId, nodeId, depth, project) {
  return request(
    "GET",
    `/api/v1/research-runs/${encodeURIComponent(runId)}/provenance/nodes/${encodeURIComponent(nodeId)}/chain`,
    { params: { project, depth } },
  );
}
export async function requestResearchOperationAuthorization(runId, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(runId)}/operation-authorizations`, {
    body: data,
    params: { project },
  });
}
export async function listResearchOperationAuthorizations(runId, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(runId)}/operation-authorizations`, {
    params: { project },
  });
}
export async function getResearchOperationAuthorization(runId, authorizationId, project) {
  return request(
    "GET",
    `/api/v1/research-runs/${encodeURIComponent(runId)}/operation-authorizations/${encodeURIComponent(authorizationId)}`,
    { params: { project } },
  );
}
export async function decideResearchOperationAuthorization(runId, authorizationId, data, project) {
  return request(
    "POST",
    `/api/v1/research-runs/${encodeURIComponent(runId)}/operation-authorizations/${encodeURIComponent(authorizationId)}/decision`,
    { body: data, params: { project } },
  );
}
export async function consumeResearchOperationAuthorization(runId, authorizationId, project) {
  return request(
    "POST",
    `/api/v1/research-runs/${encodeURIComponent(runId)}/operation-authorizations/${encodeURIComponent(authorizationId)}/consume`,
    { params: { project } },
  );
}
export async function decideResearchRunGate(id, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/gates/decision`, {
    body: data,
    params: { project },
  });
}
export async function stopResearchRun(id, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/stop`, { params: { project } });
}
export async function failResearchRun(id, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/fail`, {
    body: data,
    params: { project },
  });
}
export async function resumeResearchRun(id, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/resume`, { params: { project } });
}
export async function completeResearchRun(id, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/complete`, { params: { project } });
}
export async function recordResearchRunUsage(id, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/usage`, {
    body: data,
    params: { project },
  });
}
export async function listResearchRunGateDecisionLog(id, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(id)}/gates/decision-log`, {
    params: { project },
  });
}
export async function addResearchRunReviewComment(id, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/review-comments`, {
    body: data,
    params: { project },
  });
}
export async function listResearchRunReviewComments(id, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(id)}/review-comments`, {
    params: { project },
  });
}
export async function resolveResearchRunReviewComment(id, commentId, data, project) {
  return request(
    "POST",
    `/api/v1/research-runs/${encodeURIComponent(id)}/review-comments/${encodeURIComponent(commentId)}/resolve`,
    { body: data, params: { project } },
  );
}
export async function addResearchRunRationaleEntry(id, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/rationale`, {
    body: data,
    params: { project },
  });
}
export async function listResearchRunRationale(id, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(id)}/rationale`, {
    params: { project },
  });
}
export async function createResearchRunDisclosure(id, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/disclosure`, {
    body: data,
    params: { project },
  });
}
export async function getResearchRunDisclosure(id, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(id)}/disclosure`, {
    params: { project },
  });
}
export async function addResearchRunDisclosureEntry(id, disclosureId, data, project) {
  return request(
    "POST",
    `/api/v1/research-runs/${encodeURIComponent(id)}/disclosure/${encodeURIComponent(disclosureId)}/entries`,
    { body: data, params: { project } },
  );
}
export async function selectMethodology(id, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/methodology/selection`, {
    body: data,
    params: { project },
  });
}
export async function getMethodologySelection(id, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(id)}/methodology/selection`, {
    params: { project },
  });
}
export async function recordMethodologySource(id, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/methodology/sources`, {
    body: data,
    params: { project },
  });
}
export async function updateMethodologySourceState(id, sourceId, data, project) {
  return request(
    "PATCH",
    `/api/v1/research-runs/${encodeURIComponent(id)}/methodology/sources/${encodeURIComponent(sourceId)}`,
    { body: data, params: { project } },
  );
}
export async function listMethodologySources(id, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(id)}/methodology/sources`, {
    params: { project },
  });
}
export async function listMethodologyCatalog() {
  return request("GET", "/api/v1/research-runs/methodology/catalog", {});
}
export async function recordMethodologyRequirementsContract(id, data, project) {
  return request(
    "POST",
    `/api/v1/research-runs/${encodeURIComponent(id)}/methodology/requirements-contract`,
    { body: data, params: { project } },
  );
}
export async function getMethodologyRequirementsContract(id, project) {
  return request(
    "GET",
    `/api/v1/research-runs/${encodeURIComponent(id)}/methodology/requirements-contract`,
    { params: { project } },
  );
}
export async function recordProtocolPlan(id, data, project) {
  return request("POST", `/api/v1/research-runs/${encodeURIComponent(id)}/protocol-plan`, {
    body: data,
    params: { project },
  });
}
export async function getProtocolPlan(id, project) {
  return request("GET", `/api/v1/research-runs/${encodeURIComponent(id)}/protocol-plan`, {
    params: { project },
  });
}
export async function createControl(data, project) {
  return request("POST", "/api/v1/controls", { body: data, params: { project } });
}
export async function listControls(project) {
  return request("GET", "/api/v1/controls", { params: { project } });
}
export async function getControl(id, project) {
  return request("GET", `/api/v1/controls/${encodeURIComponent(id)}`, { params: { project } });
}
export async function getControlByUid(uid, project) {
  return request("GET", `/api/v1/controls/uid/${encodeURIComponent(uid)}`, { params: { project } });
}
export async function updateControl(id, data, project) {
  return request("PUT", `/api/v1/controls/${encodeURIComponent(id)}`, { body: data, params: { project } });
}
export async function deleteControl(id, project) {
  await request("DELETE", `/api/v1/controls/${encodeURIComponent(id)}`, { params: { project } });
}
export async function transitionControlStatus(id, status, project) {
  return request("PUT", `/api/v1/controls/${encodeURIComponent(id)}/status`, {
    body: { status },
    params: { project },
  });
}
export async function createControlLink(controlId, data, project) {
  return request("POST", `/api/v1/controls/${encodeURIComponent(controlId)}/links`, {
    body: data,
    params: { project },
  });
}
export async function listControlLinks(controlId, { targetType, project } = {}) {
  return request("GET", `/api/v1/controls/${encodeURIComponent(controlId)}/links`, {
    params: { target_type: targetType, project },
  });
}
export async function deleteControlLink(controlId, linkId, project) {
  await request(
    "DELETE",
    `/api/v1/controls/${encodeURIComponent(controlId)}/links/${encodeURIComponent(linkId)}`,
    { params: { project } },
  );
}

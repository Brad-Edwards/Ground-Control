// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { IDENTITY_ADMIN_ROOT, request } from "./api-controls-2.js";
import { toCamelCase } from "./close-issue.js";

export async function listProjects() {
  return request("GET", "/api/v1/projects");
}
export async function getProject(identifier) {
  return request("GET", `/api/v1/projects/${encodeURIComponent(identifier)}`);
}
export async function createProject(data) {
  return request("POST", "/api/v1/projects", { body: data });
}
export async function updateProject(identifier, data) {
  return request("PUT", `/api/v1/projects/${encodeURIComponent(identifier)}`, { body: data });
}
export async function replaceResearchIntake(identifier, data) {
  return request("PUT", `/api/v1/projects/${encodeURIComponent(identifier)}/research-intake`, { body: data });
}
export async function getRequirementHistory(id, expand) {
  const qs = expand ? "?expand=true" : "";
  return request("GET", `/api/v1/requirements/${encodeURIComponent(id)}/history${qs}`);
}
export async function getRelationHistory(reqId, relId) {
  return request("GET", `/api/v1/requirements/${encodeURIComponent(reqId)}/relations/${encodeURIComponent(relId)}/history`);
}
export async function getTraceabilityLinkHistory(reqId, linkId) {
  return request("GET", `/api/v1/requirements/${encodeURIComponent(reqId)}/traceability/${encodeURIComponent(linkId)}/history`);
}
export async function getRequirementTimeline(id, changeCategory, actor, from, to, limit, offset, expand) {
  const params = new URLSearchParams();
  if (changeCategory) params.set("changeCategory", changeCategory);
  if (actor) params.set("actor", actor);
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  if (limit != null) params.set("limit", String(limit));
  if (offset != null) params.set("offset", String(offset));
  if (expand) params.set("expand", "true");
  const qs = params.toString();
  return request("GET", `/api/v1/requirements/${encodeURIComponent(id)}/timeline${qs ? `?${qs}` : ""}`);
}
export async function getRequirementDiff(id, fromRevision, toRevision) {
  const params = new URLSearchParams();
  params.set("fromRevision", String(fromRevision));
  params.set("toRevision", String(toRevision));
  return request("GET", `/api/v1/requirements/${encodeURIComponent(id)}/diff?${params.toString()}`);
}
export async function getProjectTimeline(project, changeCategory, actor, from, to, limit, offset) {
  const params = new URLSearchParams();
  if (project) params.set("project", project);
  if (changeCategory) params.set("changeCategory", changeCategory);
  if (actor) params.set("actor", actor);
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  if (limit != null) params.set("limit", String(limit));
  if (offset != null) params.set("offset", String(offset));
  const qs = params.toString();
  return request("GET", `/api/v1/audit/timeline${qs ? `?${qs}` : ""}`);
}
export async function createBaseline(data, project) {
  return request("POST", "/api/v1/baselines", { body: data, params: { project } });
}
export async function listBaselines(project) {
  return request("GET", "/api/v1/baselines", { params: { project } });
}
export async function getBaseline(id) {
  return request("GET", `/api/v1/baselines/${encodeURIComponent(id)}`);
}
export async function getBaselineSnapshot(id) {
  return request("GET", `/api/v1/baselines/${encodeURIComponent(id)}/snapshot`);
}
export async function compareBaselines(id, otherId) {
  return request("GET", `/api/v1/baselines/${encodeURIComponent(id)}/compare/${encodeURIComponent(otherId)}`);
}
export async function deleteBaseline(id) {
  await request("DELETE", `/api/v1/baselines/${encodeURIComponent(id)}`);
}
export async function embedRequirement(requirementId) {
  return request("POST", `/api/v1/embeddings/${encodeURIComponent(requirementId)}`);
}
export async function getEmbeddingStatus(requirementId) {
  return request("GET", `/api/v1/embeddings/${encodeURIComponent(requirementId)}/status`);
}
export async function embedProject(project, force) {
  return request("POST", "/api/v1/embeddings/batch", {
    params: { project, force: force ? "true" : undefined },
  });
}
export async function deleteEmbedding(requirementId) {
  await request("DELETE", `/api/v1/embeddings/${encodeURIComponent(requirementId)}`);
}
export async function analyzeSemanticSimilarity(project, threshold) {
  return request("GET", "/api/v1/analysis/semantic-similarity", {
    params: { project, threshold },
  });
}
export async function createQualityGate(data, project) {
  return request("POST", "/api/v1/quality-gates", { body: data, params: { project } });
}
export async function listQualityGates(project) {
  return request("GET", "/api/v1/quality-gates", { params: { project } });
}
export async function getQualityGate(id) {
  return request("GET", `/api/v1/quality-gates/${encodeURIComponent(id)}`);
}
export async function updateQualityGate(id, data) {
  return request("PUT", `/api/v1/quality-gates/${encodeURIComponent(id)}`, { body: data });
}
export async function deleteQualityGate(id) {
  await request("DELETE", `/api/v1/quality-gates/${encodeURIComponent(id)}`);
}
export async function evaluateQualityGates(project) {
  return request("POST", "/api/v1/quality-gates/evaluate", { params: { project } });
}
export async function createDocument(data, project) {
  return request("POST", "/api/v1/documents", { body: data, params: { project } });
}
export async function listDocuments(project) {
  return request("GET", "/api/v1/documents", { params: { project } });
}
export async function getDocument(id) {
  return request("GET", `/api/v1/documents/${encodeURIComponent(id)}`);
}
export async function updateDocument(id, data) {
  return request("PUT", `/api/v1/documents/${encodeURIComponent(id)}`, { body: data });
}
export async function deleteDocument(id) {
  await request("DELETE", `/api/v1/documents/${encodeURIComponent(id)}`);
}
export async function addSectionContent(sectionId, data) {
  return request("POST", `/api/v1/sections/${encodeURIComponent(sectionId)}/content`, { body: data });
}
export async function listSectionContent(sectionId) {
  return request("GET", `/api/v1/sections/${encodeURIComponent(sectionId)}/content`);
}
export async function updateSectionContent(id, data) {
  return request("PUT", `/api/v1/sections/content/${encodeURIComponent(id)}`, { body: data });
}
export async function deleteSectionContent(id) {
  await request("DELETE", `/api/v1/sections/content/${encodeURIComponent(id)}`);
}
export async function createRiskScenarioLink(riskScenarioId, data, project) {
  return request("POST", `/api/v1/risk-scenarios/${encodeURIComponent(riskScenarioId)}/links`, {
    body: data,
    params: { project },
  });
}
export async function listRiskScenarioLinks(riskScenarioId, { targetType, project } = {}) {
  return request("GET", `/api/v1/risk-scenarios/${encodeURIComponent(riskScenarioId)}/links`, {
    params: { target_type: targetType, project },
  });
}
export async function deleteRiskScenarioLink(riskScenarioId, linkId, project) {
  await request(
    "DELETE",
    `/api/v1/risk-scenarios/${encodeURIComponent(riskScenarioId)}/links/${encodeURIComponent(linkId)}`,
    { params: { project } },
  );
}
export async function createEvidenceArtifact(data, project) {
  return request("POST", "/api/v1/evidence-artifacts", { body: data, params: { project } });
}
export async function listEvidenceArtifacts({ project, evidenceType, includeSuperseded } = {}) {
  return request("GET", "/api/v1/evidence-artifacts", {
    params: { project, evidenceType, includeSuperseded },
  });
}
export async function getEvidenceArtifact(id, project) {
  return request("GET", `/api/v1/evidence-artifacts/${encodeURIComponent(id)}`, { params: { project } });
}
export async function supersedeEvidenceArtifact(id, data, project) {
  return request("POST", `/api/v1/evidence-artifacts/${encodeURIComponent(id)}/supersede`, {
    body: data,
    params: { project },
  });
}
export async function createVerificationResult(data, project) {
  // evidence is a Map<String,Object> — inner keys are user/tool-defined and
  // must NOT be camel-cased by toCamelCase(). Build the camelCase body
  // explicitly and pass it as rawBody to skip the toCamelCase() pass in
  // request(). All other fields go through the normal toCamelCase path.
  const { evidence: evidenceMap, ...rest } = data;
  const rawBody = { ...toCamelCase(rest) };
  if (evidenceMap !== undefined) rawBody.evidence = evidenceMap;
  return request("POST", "/api/v1/verification-results", { rawBody, params: { project } });
}
export async function listVerificationResults({ requirementId, prover, result, project } = {}) {
  return request("GET", "/api/v1/verification-results", {
    params: { requirement_id: requirementId, prover, result, project },
  });
}
export async function getVerificationResult(id, project) {
  return request("GET", `/api/v1/verification-results/${encodeURIComponent(id)}`, { params: { project } });
}
export async function updateVerificationResult(id, data, project) {
  // Same opaque-map treatment as createVerificationResult: evidence inner keys
  // must not be camel-cased. Build the camelCase body explicitly.
  const { evidence: evidenceMap, ...rest } = data;
  const rawBody = { ...toCamelCase(rest) };
  if (evidenceMap !== undefined) rawBody.evidence = evidenceMap;
  return request("PUT", `/api/v1/verification-results/${encodeURIComponent(id)}`, {
    rawBody,
    params: { project },
  });
}
export async function deleteVerificationResult(id, project) {
  await request("DELETE", `/api/v1/verification-results/${encodeURIComponent(id)}`, { params: { project } });
}
export async function listPlugins({ type, capability, project } = {}) {
  return request("GET", "/api/v1/plugins", { params: { type, capability, project } });
}
export async function getPlugin(name) {
  return request("GET", `/api/v1/plugins/${encodeURIComponent(name)}`);
}
export async function registerPlugin(data, project) {
  return request("POST", "/api/v1/plugins", { body: data, params: { project } });
}
export async function unregisterPlugin(name, project) {
  await request("DELETE", `/api/v1/plugins/${encodeURIComponent(name)}`, { params: { project } });
}
export async function createTrustPolicy(data, project) {
  return request("POST", "/api/v1/trust-policies", { body: data, params: { project } });
}
export async function listTrustPolicies(project) {
  return request("GET", "/api/v1/trust-policies", { params: { project } });
}
export async function getTrustPolicy(id) {
  return request("GET", `/api/v1/trust-policies/${encodeURIComponent(id)}`);
}
export async function updateTrustPolicy(id, data) {
  return request("PUT", `/api/v1/trust-policies/${encodeURIComponent(id)}`, { body: data });
}
export async function deleteTrustPolicy(id) {
  await request("DELETE", `/api/v1/trust-policies/${encodeURIComponent(id)}`);
}
export async function installPackFromRegistry(data, project) {
  return request("POST", "/api/v1/pack-install-records/install", { body: data, params: { project } });
}
export async function upgradePackFromRegistry(data, project) {
  return request("POST", "/api/v1/pack-install-records/upgrade", { body: data, params: { project } });
}
export async function listPackInstallRecords(project, { packId } = {}) {
  return request("GET", "/api/v1/pack-install-records", { params: { project, packId } });
}
export async function getPackInstallRecord(id) {
  return request("GET", `/api/v1/pack-install-records/${encodeURIComponent(id)}`);
}
export async function listAdminUsers() {
  return request("GET", "/api/v1/admin/users");
}
export async function createAdminUser(data) {
  return request("POST", "/api/v1/admin/users", { body: data });
}
export async function updateAdminUserRole(username, role) {
  return request("PATCH", `/api/v1/admin/users/${encodeURIComponent(username)}/role`, {
    body: { role },
  });
}
export async function updateAdminUserEnabled(username, enabled) {
  return request("PATCH", `/api/v1/admin/users/${encodeURIComponent(username)}/enabled`, {
    body: { enabled },
  });
}
export async function deleteAdminUser(username) {
  await request("DELETE", `/api/v1/admin/users/${encodeURIComponent(username)}`);
}
export async function listIdentityPermissions() {
  return request("GET", `${IDENTITY_ADMIN_ROOT}/permissions`);
}
export async function listIdentityRecords(resource, { page, size } = {}) {
  return request("GET", `${IDENTITY_ADMIN_ROOT}/${resource}`, { params: { page, size } });
}
export async function getIdentityRecord(resource, id) {
  return request("GET", `${IDENTITY_ADMIN_ROOT}/${resource}/${encodeURIComponent(id)}`);
}
export async function createIdentityRecord(resource, data, { project } = {}) {
  return request("POST", `${IDENTITY_ADMIN_ROOT}/${resource}`, {
    body: data,
    params: { project },
  });
}
export async function updateIdentityRecord(resource, id, data) {
  return request("PATCH", `${IDENTITY_ADMIN_ROOT}/${resource}/${encodeURIComponent(id)}`, {
    body: data,
  });
}
export async function revokeIdentityRecord(resource, id) {
  return request("POST", `${IDENTITY_ADMIN_ROOT}/${resource}/${encodeURIComponent(id)}/revoke`);
}

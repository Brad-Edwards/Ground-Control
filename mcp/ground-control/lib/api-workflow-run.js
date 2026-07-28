// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { request } from "./api-controls-2.js";
import { readApprovedUploadFile, resolveUploadWorkspaceRoot } from "./api-requirements.js";
import { toCamelCase } from "./close-issue.js";

export async function materializeGraph() {
  return request("POST", "/api/v1/admin/graph/materialize");
}
export async function getAncestors(uid, depth, project) {
  return request("GET", `/api/v1/requirements/graph/ancestors/${encodeURIComponent(uid)}`, {
    params: { depth, project },
  });
}
export async function getDescendants(uid, depth, project) {
  return request("GET", `/api/v1/requirements/graph/descendants/${encodeURIComponent(uid)}`, {
    params: { depth, project },
  });
}
export async function findPaths(source, target, project) {
  return request("GET", "/api/v1/requirements/graph/paths", {
    params: { source, target, project },
  });
}
export async function getGraphVisualization(project, entityTypes) {
  return request("GET", "/api/v1/graph/visualization", {
    params: { project, entityTypes: entityTypes ? entityTypes.join(",") : undefined },
  });
}
export async function extractSubgraph(rootNodeIds, project, entityTypes, maxDepth) {
  return request("POST", "/api/v1/graph/subgraph/query", {
    params: { project },
    body: { root_node_ids: rootNodeIds, entity_types: entityTypes, max_depth: maxDepth },
  });
}
export async function traverseGraph(rootNodeIds, project, entityTypes, maxDepth) {
  return request("POST", "/api/v1/graph/traversal/query", {
    params: { project },
    body: { root_node_ids: rootNodeIds, entity_types: entityTypes, max_depth: maxDepth },
  });
}
export async function findGraphPaths(sourceNodeId, targetNodeId, project, entityTypes, maxDepth) {
  return request("POST", "/api/v1/graph/paths/query", {
    params: { project },
    body: {
      source_node_id: sourceNodeId,
      target_node_id: targetNodeId,
      entity_types: entityTypes,
      max_depth: maxDepth,
    },
  });
}
export async function createSection(documentId, data) {
  return request("POST", `/api/v1/documents/${encodeURIComponent(documentId)}/sections`, { body: data });
}
export async function listSections(documentId) {
  return request("GET", `/api/v1/documents/${encodeURIComponent(documentId)}/sections`);
}
export async function getSectionTree(documentId) {
  return request("GET", `/api/v1/documents/${encodeURIComponent(documentId)}/sections/tree`);
}
export async function getSection(id) {
  return request("GET", `/api/v1/sections/${encodeURIComponent(id)}`);
}
export async function updateSection(id, data) {
  return request("PUT", `/api/v1/sections/${encodeURIComponent(id)}`, { body: data });
}
export async function deleteSection(id) {
  await request("DELETE", `/api/v1/sections/${encodeURIComponent(id)}`);
}
export async function createAdr(data, project) {
  return request("POST", "/api/v1/adrs", { body: data, params: { project } });
}
export async function listAdrs(project) {
  return request("GET", "/api/v1/adrs", { params: { project } });
}
export async function getAdr(id) {
  return request("GET", `/api/v1/adrs/${encodeURIComponent(id)}`);
}
export async function getAdrByUid(uid, project) {
  return request("GET", `/api/v1/adrs/uid/${encodeURIComponent(uid)}`, { params: { project } });
}
export async function updateAdr(id, data) {
  return request("PUT", `/api/v1/adrs/${encodeURIComponent(id)}`, { body: data });
}
export async function deleteAdr(id) {
  await request("DELETE", `/api/v1/adrs/${encodeURIComponent(id)}`);
}
export async function transitionAdrStatus(id, status) {
  return request("PUT", `/api/v1/adrs/${encodeURIComponent(id)}/status`, { body: { status } });
}
export async function getAdrRequirements(id) {
  return request("GET", `/api/v1/adrs/${encodeURIComponent(id)}/requirements`);
}
export async function createRiskScenario(data, project) {
  return request("POST", "/api/v1/risk-scenarios", { body: data, params: { project } });
}
export async function listRiskScenarios(project) {
  return request("GET", "/api/v1/risk-scenarios", { params: { project } });
}
export async function getRiskScenario(id, project) {
  return request("GET", `/api/v1/risk-scenarios/${encodeURIComponent(id)}`, { params: { project } });
}
export async function getRiskScenarioByUid(uid, project) {
  return request("GET", `/api/v1/risk-scenarios/uid/${encodeURIComponent(uid)}`, { params: { project } });
}
export async function updateRiskScenario(id, data, project) {
  return request("PUT", `/api/v1/risk-scenarios/${encodeURIComponent(id)}`, { body: data, params: { project } });
}
export async function deleteRiskScenario(id, project) {
  await request("DELETE", `/api/v1/risk-scenarios/${encodeURIComponent(id)}`, { params: { project } });
}
export async function transitionRiskScenarioStatus(id, status, project) {
  return request("PUT", `/api/v1/risk-scenarios/${encodeURIComponent(id)}/status`, {
    body: { status },
    params: { project },
  });
}
export async function getRiskScenarioRequirements(id, project) {
  return request("GET", `/api/v1/risk-scenarios/${encodeURIComponent(id)}/requirements`, { params: { project } });
}
export async function getRiskScenarioTrace(id, project) {
  return request("GET", `/api/v1/risk-scenarios/${encodeURIComponent(id)}/trace`, { params: { project } });
}
export async function createFinding(data, project) {
  return request("POST", "/api/v1/findings", { body: data, params: { project } });
}
export async function listFindings(project) {
  return request("GET", "/api/v1/findings", { params: { project } });
}
export async function getFinding(id, project) {
  return request("GET", `/api/v1/findings/${encodeURIComponent(id)}`, { params: { project } });
}
export async function getFindingByUid(uid, project) {
  return request("GET", `/api/v1/findings/uid/${encodeURIComponent(uid)}`, { params: { project } });
}
export async function updateFinding(id, data, project) {
  return request("PUT", `/api/v1/findings/${encodeURIComponent(id)}`, { body: data, params: { project } });
}
export async function deleteFinding(id, project) {
  await request("DELETE", `/api/v1/findings/${encodeURIComponent(id)}`, { params: { project } });
}
export async function transitionFindingStatus(id, status, project) {
  return request("PUT", `/api/v1/findings/${encodeURIComponent(id)}/status`, {
    body: { status },
    params: { project },
  });
}
export async function createFindingLink(findingId, data, project) {
  return request("POST", `/api/v1/findings/${encodeURIComponent(findingId)}/links`, {
    body: data,
    params: { project },
  });
}
export async function listFindingLinks(findingId, project) {
  return request("GET", `/api/v1/findings/${encodeURIComponent(findingId)}/links`, {
    params: { project },
  });
}
export async function deleteFindingLink(findingId, linkId, project) {
  await request(
    "DELETE",
    `/api/v1/findings/${encodeURIComponent(findingId)}/links/${encodeURIComponent(linkId)}`,
    { params: { project } },
  );
}
export async function createAudit(data, project) {
  return request("POST", "/api/v1/audits", { body: data, params: { project } });
}
export async function listAudits(project) {
  return request("GET", "/api/v1/audits", { params: { project } });
}
export async function getAudit(id, project) {
  return request("GET", `/api/v1/audits/${encodeURIComponent(id)}`, { params: { project } });
}
export async function getAuditByUid(uid, project) {
  return request("GET", `/api/v1/audits/uid/${encodeURIComponent(uid)}`, { params: { project } });
}
export async function updateAudit(id, data, project) {
  return request("PUT", `/api/v1/audits/${encodeURIComponent(id)}`, { body: data, params: { project } });
}
export async function deleteAudit(id, project) {
  await request("DELETE", `/api/v1/audits/${encodeURIComponent(id)}`, { params: { project } });
}
export async function transitionAuditStatus(id, status, project) {
  return request("PUT", `/api/v1/audits/${encodeURIComponent(id)}/status`, {
    body: { status },
    params: { project },
  });
}
export async function createAuditLink(auditId, data, project) {
  return request("POST", `/api/v1/audits/${encodeURIComponent(auditId)}/links`, {
    body: data,
    params: { project },
  });
}
export async function listAuditLinks(auditId, project) {
  return request("GET", `/api/v1/audits/${encodeURIComponent(auditId)}/links`, {
    params: { project },
  });
}
export async function deleteAuditLink(auditId, linkId, project) {
  await request(
    "DELETE",
    `/api/v1/audits/${encodeURIComponent(auditId)}/links/${encodeURIComponent(linkId)}`,
    { params: { project } },
  );
}
export async function registerPackRegistryEntry(data, project) {
  return request("POST", "/api/v1/pack-registry", { body: data, params: { project } });
}
export async function importPackRegistryEntry(filePath, data, project) {
  const workspaceRoot = await resolveUploadWorkspaceRoot();
  const { bytes, basename: name } = readApprovedUploadFile(filePath, {
    workspaceRoot,
    allowedExtensions: [".json"],
    fieldName: "file_path",
  });
  const form = new FormData();
  form.append("file", new Blob([bytes]), name);
  if (data && Object.keys(data).length > 0) {
    form.append(
      "options",
      new Blob([JSON.stringify(toCamelCase(data))], { type: "application/json" }),
      "options.json",
    );
  }
  return request("POST", "/api/v1/pack-registry/import", { formData: form, params: { project } });
}
export async function listPackRegistryEntries(project, { packType } = {}) {
  return request("GET", "/api/v1/pack-registry", { params: { project, packType } });
}
export async function listPackVersions(packId, project) {
  return request("GET", `/api/v1/pack-registry/${encodeURIComponent(packId)}`, { params: { project } });
}
export async function getPackRegistryEntry(packId, version, project) {
  return request("GET", `/api/v1/pack-registry/${encodeURIComponent(packId)}/${encodeURIComponent(version)}`, { params: { project } });
}
export async function updatePackRegistryEntry(packId, version, data, project) {
  return request("PUT", `/api/v1/pack-registry/${encodeURIComponent(packId)}/${encodeURIComponent(version)}`, { body: data, params: { project } });
}
export async function withdrawPackRegistryEntry(packId, version, project) {
  return request("PUT", `/api/v1/pack-registry/${encodeURIComponent(packId)}/${encodeURIComponent(version)}/withdraw`, { params: { project } });
}
export async function deletePackRegistryEntry(packId, version, project) {
  await request("DELETE", `/api/v1/pack-registry/${encodeURIComponent(packId)}/${encodeURIComponent(version)}`, { params: { project } });
}
export async function resolvePack(data, project) {
  return request("POST", "/api/v1/pack-registry/resolve", { body: data, params: { project } });
}
export async function checkPackCompatibility(data, project) {
  return request("POST", "/api/v1/pack-registry/check-compatibility", { body: data, params: { project } });
}
export async function createWorkflowRun(data, project, { signal } = {}) {
  return request("POST", "/api/v1/workflow-runs", { body: data, params: { project }, signal });
}
export async function recordWorkflowRunEvent(runId, data, project, { signal } = {}) {
  return request("POST", `/api/v1/workflow-runs/${encodeURIComponent(runId)}/events`, {
    body: data,
    params: { project },
    signal,
  });
}
export async function importWorkflowRunCost(runId, data, project) {
  return request("POST", `/api/v1/workflow-runs/${encodeURIComponent(runId)}/cost`, {
    body: data,
    params: { project },
  });
}
export async function listWorkflowRuns({ project, limit } = {}) {
  return request("GET", "/api/v1/workflow-runs", { params: { project, limit } });
}
export async function listWorkflowRunEvents(runId, { project, limit } = {}) {
  return request("GET", `/api/v1/workflow-runs/${encodeURIComponent(runId)}/events`, {
    params: { project, limit },
  });
}
/**
 * ADR-090 process variables for one project (issue #1355): per-station yield and rework, plus
 * finding counts by reviewer/detector, category, severity, and disposition.
 */
export async function measureWorkflowRuns({ project, from, to } = {}) {
  return request("GET", "/api/v1/workflow-runs/measurement", { params: { project, from, to } });
}

/**
 * Move one gate finding to a terminal disposition. Detection and disposition are different
 * moments, so this is deliberately a separate call from recording the attempt that found it.
 */
export async function recordWorkflowFindingDisposition(findingId, { project, disposition } = {}) {
  return request(
    "POST",
    `/api/v1/workflow-runs/findings/${encodeURIComponent(findingId)}/disposition`,
    { params: { project }, body: { disposition } },
  );
}

export async function aggregateWorkflowRuns({
  project,
  repo,
  runtime,
  requirement,
  workflowType,
  outcome,
  from,
  to,
} = {}) {
  return request("GET", "/api/v1/workflow-runs/aggregate", {
    params: { project, repo, runtime, requirement, workflowType, outcome, from, to },
  });
}
export async function crossProjectAggregateWorkflowRuns({
  repo,
  runtime,
  requirement,
  workflowType,
  outcome,
  from,
  to,
} = {}) {
  return request("GET", "/api/v1/workflow-runs/cross-project-aggregate", {
    params: { repo, runtime, requirement, workflowType, outcome, from, to },
  });
}

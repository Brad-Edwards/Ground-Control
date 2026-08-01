// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { request } from "./api-controls-2.js";

export async function createAsset(data, project) {
  return request("POST", "/api/v1/assets", { body: data, params: { project } });
}
export async function listAssets({ project, type, owner, steward, environment, criticality, scope, subtype } = {}) {
  return request("GET", "/api/v1/assets", {
    params: { project, type, owner, steward, environment, criticality, scope, subtype },
  });
}
export async function getAsset(id, project) {
  return request("GET", `/api/v1/assets/${encodeURIComponent(id)}`, { params: { project } });
}
export async function getAssetByUid(uid, project) {
  return request("GET", `/api/v1/assets/uid/${encodeURIComponent(uid)}`, { params: { project } });
}
export async function updateAsset(id, data, project) {
  return request("PUT", `/api/v1/assets/${encodeURIComponent(id)}`, { body: data, params: { project } });
}
export async function deleteAsset(id, project) {
  await request("DELETE", `/api/v1/assets/${encodeURIComponent(id)}`, { params: { project } });
}
export async function archiveAsset(id, project) {
  return request("POST", `/api/v1/assets/${encodeURIComponent(id)}/archive`, { params: { project } });
}
export async function createAssetRelation(assetId, data, project) {
  return request("POST", `/api/v1/assets/${encodeURIComponent(assetId)}/relations`, {
    body: data,
    params: { project },
  });
}
export async function getAssetRelations(assetId, project) {
  return request("GET", `/api/v1/assets/${encodeURIComponent(assetId)}/relations`, { params: { project } });
}
export async function deleteAssetRelation(assetId, relationId, project) {
  await request(
    "DELETE",
    `/api/v1/assets/${encodeURIComponent(assetId)}/relations/${encodeURIComponent(relationId)}`,
    { params: { project } },
  );
}
export async function updateAssetRelation(assetId, relationId, data, project) {
  return request(
    "PUT",
    `/api/v1/assets/${encodeURIComponent(assetId)}/relations/${encodeURIComponent(relationId)}`,
    { body: data, params: { project } },
  );
}
export async function detectAssetCycles(project) {
  return request("GET", "/api/v1/assets/topology/cycles", { params: { project } });
}
export async function assetImpactAnalysis(assetId, project) {
  return request("GET", `/api/v1/assets/${encodeURIComponent(assetId)}/topology/impact`, { params: { project } });
}
export async function extractAssetSubgraph(data, project) {
  return request("POST", "/api/v1/assets/topology/subgraph", { body: data, params: { project } });
}
export async function createAssetLink(assetId, data, project) {
  return request("POST", `/api/v1/assets/${encodeURIComponent(assetId)}/links`, {
    body: data,
    params: { project },
  });
}
export async function getAssetLinks(assetId, targetType, project) {
  return request("GET", `/api/v1/assets/${encodeURIComponent(assetId)}/links`, {
    params: { target_type: targetType, project },
  });
}
export async function deleteAssetLink(assetId, linkId, project) {
  await request("DELETE", `/api/v1/assets/${encodeURIComponent(assetId)}/links/${encodeURIComponent(linkId)}`, {
    params: { project },
  });
}
export async function getAssetLinksByTarget(targetType, targetEntityId, targetIdentifier, project) {
  return request("GET", "/api/v1/assets/links/by-target", {
    params: { target_type: targetType, target_entity_id: targetEntityId, target_identifier: targetIdentifier, project },
  });
}
export async function createAssetExternalId(assetId, data, project) {
  return request("POST", `/api/v1/assets/${encodeURIComponent(assetId)}/external-ids`, {
    body: data,
    params: { project },
  });
}
export async function getAssetExternalIds(assetId, sourceSystem, project) {
  return request("GET", `/api/v1/assets/${encodeURIComponent(assetId)}/external-ids`, {
    params: { source_system: sourceSystem, project },
  });
}
export async function updateAssetExternalId(assetId, extIdId, data, project) {
  return request(
    "PUT",
    `/api/v1/assets/${encodeURIComponent(assetId)}/external-ids/${encodeURIComponent(extIdId)}`,
    { body: data, params: { project } },
  );
}
export async function deleteAssetExternalId(assetId, extIdId, project) {
  await request(
    "DELETE",
    `/api/v1/assets/${encodeURIComponent(assetId)}/external-ids/${encodeURIComponent(extIdId)}`,
    { params: { project } },
  );
}
export async function findAssetByExternalId(sourceSystem, sourceId, project) {
  return request("GET", "/api/v1/assets/external-ids/by-source", {
    params: { source_system: sourceSystem, source_id: sourceId, project },
  });
}
export async function createObservation(assetId, data, project) {
  return request("POST", `/api/v1/assets/${encodeURIComponent(assetId)}/observations`, {
    body: data,
    params: { project },
  });
}
export async function listObservations(assetId, { category, key, project } = {}) {
  return request("GET", `/api/v1/assets/${encodeURIComponent(assetId)}/observations`, {
    params: { category, key, project },
  });
}
export async function getObservation(assetId, observationId, project) {
  return request(
    "GET",
    `/api/v1/assets/${encodeURIComponent(assetId)}/observations/${encodeURIComponent(observationId)}`,
    { params: { project } },
  );
}
export async function updateObservation(assetId, observationId, data, project) {
  return request(
    "PUT",
    `/api/v1/assets/${encodeURIComponent(assetId)}/observations/${encodeURIComponent(observationId)}`,
    { body: data, params: { project } },
  );
}
export async function deleteObservation(assetId, observationId, project) {
  await request(
    "DELETE",
    `/api/v1/assets/${encodeURIComponent(assetId)}/observations/${encodeURIComponent(observationId)}`,
    { params: { project } },
  );
}
export async function listLatestObservations(assetId, project) {
  return request("GET", `/api/v1/assets/${encodeURIComponent(assetId)}/observations/latest`, {
    params: { project },
  });
}
export async function registerAssetSubtypeSchema(data, project) {
  return request("POST", "/api/v1/assets/subtype-schemas", { body: data, params: { project } });
}
export async function listAssetSubtypeSchemas({ project, assetType, subtype } = {}) {
  return request("GET", "/api/v1/assets/subtype-schemas", {
    params: { project, assetType, subtype },
  });
}
export async function getAssetSubtypeSchema(id, project) {
  return request("GET", `/api/v1/assets/subtype-schemas/${encodeURIComponent(id)}`, {
    params: { project },
  });
}
export async function getActiveAssetSubtypeSchema(assetType, subtype, project) {
  return request("GET", "/api/v1/assets/subtype-schemas/active", {
    params: { project, assetType, subtype },
  });
}
export async function updateAssetSubtypeSchema(id, data, project) {
  return request("PUT", `/api/v1/assets/subtype-schemas/${encodeURIComponent(id)}`, {
    body: data,
    params: { project },
  });
}
export async function deprecateAssetSubtypeSchema(id, project) {
  return request("POST", `/api/v1/assets/subtype-schemas/${encodeURIComponent(id)}/deprecate`, {
    params: { project },
  });
}
export async function createThreatModel(data, project) {
  return request("POST", "/api/v1/threat-models", { body: data, params: { project } });
}
export async function listThreatModels(project) {
  return request("GET", "/api/v1/threat-models", { params: { project } });
}
export async function getThreatModel(id, project) {
  return request("GET", `/api/v1/threat-models/${encodeURIComponent(id)}`, { params: { project } });
}
export async function getThreatModelByUid(uid, project) {
  return request("GET", `/api/v1/threat-models/uid/${encodeURIComponent(uid)}`, { params: { project } });
}
export async function updateThreatModel(id, data, project) {
  return request("PUT", `/api/v1/threat-models/${encodeURIComponent(id)}`, { body: data, params: { project } });
}
export async function deleteThreatModel(id, project) {
  await request("DELETE", `/api/v1/threat-models/${encodeURIComponent(id)}`, { params: { project } });
}
export async function transitionThreatModelStatus(id, status, project) {
  return request("PUT", `/api/v1/threat-models/${encodeURIComponent(id)}/status`, {
    body: { status },
    params: { project },
  });
}
export async function getThreatModelLinkedRequirements(id, project) {
  return request("GET", `/api/v1/threat-models/${encodeURIComponent(id)}/requirements`, { params: { project } });
}
export async function getThreatModelTrace(id, project) {
  return request("GET", `/api/v1/threat-models/${encodeURIComponent(id)}/trace`, { params: { project } });
}
export async function createThreatModelLink(threatModelId, data, project) {
  return request("POST", `/api/v1/threat-models/${encodeURIComponent(threatModelId)}/links`, {
    body: data,
    params: { project },
  });
}
export async function listThreatModelLinks(threatModelId, project) {
  return request("GET", `/api/v1/threat-models/${encodeURIComponent(threatModelId)}/links`, {
    params: { project },
  });
}
export async function deleteThreatModelLink(threatModelId, linkId, project) {
  await request(
    "DELETE",
    `/api/v1/threat-models/${encodeURIComponent(threatModelId)}/links/${encodeURIComponent(linkId)}`,
    { params: { project } },
  );
}
export async function createScopedControlImplementation(data, project) {
  return request("POST", "/api/v1/scoped-control-implementations", { body: data, params: { project } });
}
export async function listScopedControlImplementations(project) {
  return request("GET", "/api/v1/scoped-control-implementations", { params: { project } });
}
export async function getScopedControlImplementation(id, project) {
  return request("GET", `/api/v1/scoped-control-implementations/${encodeURIComponent(id)}`, { params: { project } });
}
export async function updateScopedControlImplementation(id, data, project) {
  return request("PUT", `/api/v1/scoped-control-implementations/${encodeURIComponent(id)}`, {
    body: data,
    params: { project },
  });
}
export async function deleteScopedControlImplementation(id, project) {
  await request("DELETE", `/api/v1/scoped-control-implementations/${encodeURIComponent(id)}`, { params: { project } });
}
export async function createRiskControlMapping(data, project) {
  return request("POST", "/api/v1/risk-control-mappings", { body: data, params: { project } });
}
export async function listRiskControlMappings(project) {
  return request("GET", "/api/v1/risk-control-mappings", { params: { project } });
}
export async function getRiskControlMapping(id, project) {
  return request("GET", `/api/v1/risk-control-mappings/${encodeURIComponent(id)}`, { params: { project } });
}
export async function updateRiskControlMapping(id, data, project) {
  return request("PUT", `/api/v1/risk-control-mappings/${encodeURIComponent(id)}`, {
    body: data,
    params: { project },
  });
}
export async function deleteRiskControlMapping(id, project) {
  await request("DELETE", `/api/v1/risk-control-mappings/${encodeURIComponent(id)}`, { params: { project } });
}
export async function attachMappingObservation(mappingId, observationId, project) {
  return request("POST", `/api/v1/risk-control-mappings/${encodeURIComponent(mappingId)}/observations`, {
    body: { observationId },
    params: { project },
  });
}
export async function detachMappingObservation(mappingId, observationId, project) {
  return request("DELETE",
    `/api/v1/risk-control-mappings/${encodeURIComponent(mappingId)}/observations/${encodeURIComponent(observationId)}`,
    { params: { project } });
}
export async function addMappingEvidenceRef(mappingId, data, project) {
  return request("POST", `/api/v1/risk-control-mappings/${encodeURIComponent(mappingId)}/evidence`, {
    body: data,
    params: { project },
  });
}
export async function getUnmappedScenarios(project) {
  return request("GET", "/api/v1/analysis/risk-control/unmapped-scenarios", { params: { project } });
}
export async function getUnmappedRecords(project, transitive = true) {
  return request("GET", "/api/v1/analysis/risk-control/unmapped-records", {
    params: { project, transitive },
  });
}
export async function getUnmappedControls(project) {
  return request("GET", "/api/v1/analysis/risk-control/unmapped-controls", { params: { project } });
}
export async function getAssessmentFeed(assessmentResultId, project) {
  return request("GET", `/api/v1/analysis/risk-control/assessment-feed/${encodeURIComponent(assessmentResultId)}`, {
    params: { project },
  });
}
export async function getUnmappedThreats(project) {
  return request("GET", "/api/v1/analysis/risk-control/unmapped-threats", { params: { project } });
}
export async function getThreatUnmappedControls(project) {
  return request("GET", "/api/v1/analysis/risk-control/threat-unmapped-controls", { params: { project } });
}

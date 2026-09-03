// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { request } from "./api-controls-2.js";

export async function createWorkflowRun(data, project, { signal } = {}) {
  return request("POST", "/api/v1/workflow-runs", { body: data, params: { project }, signal });
}

const REST_STATION_RESULT = Object.freeze({
  pass: "PASS",
  fail: "FAIL",
  skipped_station: "SKIPPED_STATION",
  cancelled: "CANCELLED",
  not_evaluable: "NOT_EVALUABLE",
  unobserved: "UNOBSERVED",
});

function workflowRunEventBody(data) {
  if (data?.station_result === undefined) return data;
  const stationResult = REST_STATION_RESULT[data.station_result];
  if (stationResult === undefined) {
    throw new TypeError(`Unknown station_result: ${String(data.station_result)}`);
  }
  return { ...data, station_result: stationResult };
}

export async function recordWorkflowRunEvent(runId, data, project, { signal } = {}) {
  return request("POST", `/api/v1/workflow-runs/${encodeURIComponent(runId)}/events`, {
    body: workflowRunEventBody(data),
    params: { project },
    signal,
  });
}

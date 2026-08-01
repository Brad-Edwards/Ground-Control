// Per-field type overrides. Two kinds:
//   1. Enum precision: springdoc emits a bare `string` for some enum-backed fields; pin the union.
//   2. Runtime nullability: springdoc does not emit nullability, but some response fields are null
//      at runtime (boxed Java types with no value yet). Pin them to `T | null` so the generated
//      client is honest and consumers null-check them. Each entry is verified against the backend
//      record it projects.
export const exactPropertyTypes = {
  "TimelineEntryResponse.changeCategory": "ChangeCategory",
  "GraphVisualizationNodeResponse.entityType": "GraphEntityType",
  "GraphEdgeResponse.sourceEntityType": "GraphEntityType",
  "GraphEdgeResponse.targetEntityType": "GraphEntityType",
  // gateState is null for bulk list entries and executions whose gate state cannot be queried
  // (GC-O009 (b), #1279). springdoc emits a bare $ref (non-null) for it.
  "WorkflowExecutionResponse.gateState": "GateStateResponse | null",
  // Analysis summaries/violations carry a requirement status; the backend projects it as
  // Status.name() so springdoc emits a bare string. Pin it to the Status union.
  "RequirementSummaryResponse.status": "Status",
  "ConsistencyViolationResponse.sourceStatus": "Status",
  "ConsistencyViolationResponse.targetStatus": "Status",
  // Runtime-nullable response fields (boxed Java types with no value yet). Verified against their
  // backend records: a run may have no PR/end time; aggregates and hotspots have no cycle/cost data
  // for an empty window; a traceability link may be unsynced or unarchived.
  "WorkflowRunResponse.prNumber": "number | null",
  "WorkflowRunResponse.endedAt": "string | null",
  "OpenRunResponse.currentPhase": "string | null",
  "OpenRunResponse.currentPhaseTitle": "string | null",
  "OpenRunResponse.currentPhaseSince": "string | null",
  "OpenRunResponse.currentCycle": "number | null",
  "OpenRunResponse.routing": "RoutingObservationResponse | null",
  "RunSummaryResponse.repo": "string | null",
  "RunSummaryResponse.issueNumber": "number | null",
  "RunSummaryResponse.prNumber": "number | null",
  "RunSummaryResponse.branch": "string | null",
  "RunSummaryResponse.runtimeDriver": "string | null",
  "RunSummaryResponse.startedAt": "string | null",
  "RunSummaryResponse.endedAt": "string | null",
  "RunSummaryResponse.costProxy": "number | null",
  "RunSummaryResponse.costCurrency": "string | null",
  "RunSummaryResponse.tokenUsage": "number | null",
  "RoutingObservationResponse.stepAlias": "string | null",
  "RoutingObservationResponse.tier":
    '"LOW" | "MEDIUM" | "HIGH" | "NOT_APPLICABLE" | "UNOBSERVED" | null',
  "RoutingObservationResponse.model": "string | null",
  "RoutingObservationResponse.expectedModel": "string | null",
  "RoutingObservationResponse.modelMatchesExpected": "boolean | null",
  "GateAttemptResponse.cycleIndex": "number | null",
  "GateAttemptResponse.durationMs": "number | null",
  "GateAttemptResponse.eventType":
    '"STARTED" | "COMPLETED" | "FAILED" | "ESCALATED" | "SKIPPED" | null',
  "GateAttemptResponse.occurredAt": "string | null",
  "WorkflowRunAggregateResponse.cycleTimeP50Min": "number | null",
  "WorkflowRunAggregateResponse.cycleTimeP95Min": "number | null",
  "WorkflowRunAggregateResponse.cycleTimeP99Min": "number | null",
  "WorkflowRunAggregateResponse.costProxyPerMergedRun": "number | null",
  "WorkflowRunAggregateResponse.costProxyPerClosedRun": "number | null",
  "PhaseHotspotResponse.maxCycleIndex": "number | null",
  "TraceabilityLinkResponse.archivedAt": "string | null",
  "TraceabilityLinkResponse.lastSyncedAt": "string | null",
  "RequirementResponse.archivedAt": "string | null",
  // Request fields the backend accepts as null to unset/clear: a cursor with a selected case but no
  // step, and a note cleared alongside its clearNotes flag.
  "UpdateTestRunCursorRequest.currentStepResultId": "string | null",
  "UpdateTestRunCaseResultRequest.notes": "string | null",
  "UpdateTestRunStepResultRequest.comment": "string | null",
};

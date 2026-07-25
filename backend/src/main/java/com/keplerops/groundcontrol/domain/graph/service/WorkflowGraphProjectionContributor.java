package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Read-only mixed-graph projection of ADR-061 workflow reporting telemetry. */
@Component
public class WorkflowGraphProjectionContributor implements GraphProjectionContributor {

    private static final String EDGE_RUN_FOR_WORK_ITEM = "RUN_FOR_WORK_ITEM";
    private static final String EDGE_WORKFLOW_PHASE_EVENT = "WORKFLOW_PHASE_EVENT";

    private final WorkflowRunRepository runRepository;
    private final WorkflowPhaseEventRepository eventRepository;

    public WorkflowGraphProjectionContributor(
            WorkflowRunRepository runRepository, WorkflowPhaseEventRepository eventRepository) {
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        var nodes = new ArrayList<GraphNode>();
        var workItems = new LinkedHashMap<String, GraphNode>();
        for (var run : runRepository.findForGraphProjection(projectId)) {
            nodes.add(runNode(run));
            if (hasCompleteWorkItemIdentity(run)) {
                var workItem = workItemNode(projectId, run);
                workItems.putIfAbsent(workItem.id(), workItem);
            }
        }
        nodes.addAll(workItems.values());
        return nodes;
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        var runs = runRepository.findForGraphProjection(projectId).stream()
                .filter(WorkflowGraphProjectionContributor::hasCompleteWorkItemIdentity)
                .collect(Collectors.toMap(WorkflowRun::getId, Function.identity()));
        var edges = new ArrayList<GraphEdge>();
        for (var run : runs.values()) {
            edges.add(runForWorkItemEdge(projectId, run));
        }
        for (var event : eventRepository.findForGraphProjection(projectId)) {
            var run = runs.get(event.getRunId());
            if (run != null) {
                edges.add(phaseEventEdge(projectId, run, event));
            }
        }
        return edges;
    }

    private static GraphNode runNode(WorkflowRun run) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("workflowType", run.getWorkflowType());
        if (run.getRuntimeDriver() != null) {
            properties.put("runtimeDriver", run.getRuntimeDriver());
        }
        properties.put("finalState", run.getFinalState().name());
        properties.put("outcome", run.getOutcome().name());
        properties.put("provenance", run.getProvenance().name());
        if (run.getStartedAt() != null) {
            properties.put("startedAt", run.getStartedAt().toString());
        }
        if (run.getEndedAt() != null) {
            properties.put("endedAt", run.getEndedAt().toString());
        }
        return new GraphNode(
                GraphIds.nodeId(GraphEntityType.WORKFLOW_RUN, run.getId()),
                run.getId().toString(),
                GraphEntityType.WORKFLOW_RUN,
                run.getProject(),
                null,
                run.getWorkflowType(),
                properties);
    }

    private static GraphNode workItemNode(UUID projectId, WorkflowRun run) {
        String id = workItemNodeId(projectId, run);
        return new GraphNode(
                id,
                id.substring(id.indexOf(':') + 1),
                GraphEntityType.WORK_ITEM_REFERENCE,
                run.getProject(),
                null,
                run.getRepo() + "#" + run.getIssueNumber(),
                Map.of("repo", run.getRepo(), "issueNumber", run.getIssueNumber()));
    }

    private static GraphEdge runForWorkItemEdge(UUID projectId, WorkflowRun run) {
        return new GraphEdge(
                run.getId() + ":run-for-work-item",
                EDGE_RUN_FOR_WORK_ITEM,
                GraphIds.nodeId(GraphEntityType.WORKFLOW_RUN, run.getId()),
                workItemNodeId(projectId, run),
                GraphEntityType.WORKFLOW_RUN,
                GraphEntityType.WORK_ITEM_REFERENCE,
                Map.of());
    }

    private static GraphEdge phaseEventEdge(UUID projectId, WorkflowRun run, WorkflowPhaseEvent event) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("phase", event.getPhase());
        properties.put("eventType", event.getEventType().name());
        if (event.getCycleIndex() != null) {
            properties.put("cycleIndex", event.getCycleIndex());
        }
        properties.put("occurredAt", event.getOccurredAt().toString());
        if (event.getDurationMs() != null) {
            properties.put("durationMs", event.getDurationMs());
        }
        if (event.getOutcome() != null) {
            properties.put("outcome", event.getOutcome());
        }
        properties.put("provenance", event.getProvenance().name());
        return new GraphEdge(
                event.getId().toString(),
                EDGE_WORKFLOW_PHASE_EVENT,
                GraphIds.nodeId(GraphEntityType.WORKFLOW_RUN, run.getId()),
                workItemNodeId(projectId, run),
                GraphEntityType.WORKFLOW_RUN,
                GraphEntityType.WORK_ITEM_REFERENCE,
                properties);
    }

    private static boolean hasCompleteWorkItemIdentity(WorkflowRun run) {
        return run.getRepo() != null
                && !run.getRepo().isBlank()
                && run.getIssueNumber() != null
                && run.getIssueNumber() > 0;
    }

    private static String workItemNodeId(UUID projectId, WorkflowRun run) {
        return GraphIds.workflowWorkItemReferenceNodeId(projectId, run.getRepo(), run.getIssueNumber());
    }
}

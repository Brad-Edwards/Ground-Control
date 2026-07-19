package com.keplerops.groundcontrol.unit.domain.graph;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.service.WorkflowGraphProjectionContributor;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowGraphProjectionContributorTest {

    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String PROJECT = "ground-control";

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowPhaseEventRepository eventRepository;

    @InjectMocks
    private WorkflowGraphProjectionContributor contributor;

    @Test
    void projectsRunsAndDeduplicatedWorkItemReferencesWithAClosedPropertySet() {
        var first = completeRun("autarchy-ai/Ground-Control", 1311);
        first.setRuntimeDriver("codex");
        first.setStartedAt(Instant.parse("2026-07-19T12:00:00Z"));
        first.setEndedAt(Instant.parse("2026-07-19T12:30:00Z"));
        first.setBranch("must-not-enter-the-graph");
        first.setProvider("must-not-enter-the-graph");
        first.setModel("must-not-enter-the-graph");
        first.setTokenUsage(42L);
        var second = completeRun("autarchy-ai/Ground-Control", 1311);
        var partial = newRun();
        partial.setRepo("autarchy-ai/Ground-Control");

        when(runRepository.findForGraphProjection(PROJECT_ID)).thenReturn(List.of(first, second, partial));

        var nodes = contributor.contributeNodes(PROJECT_ID);

        assertThat(nodes).hasSize(4);
        assertThat(nodes)
                .filteredOn(node -> node.entityType() == GraphEntityType.WORKFLOW_RUN)
                .hasSize(3);
        var workItems = nodes.stream()
                .filter(node -> node.entityType() == GraphEntityType.WORK_ITEM_REFERENCE)
                .toList();
        assertThat(workItems).hasSize(1);
        assertThat(workItems.getFirst().properties())
                .containsOnlyKeys("repo", "issueNumber")
                .containsEntry("repo", "autarchy-ai/Ground-Control")
                .containsEntry("issueNumber", 1311);

        var firstNode = nodes.stream()
                .filter(node -> node.domainId().equals(first.getId().toString()))
                .findFirst()
                .orElseThrow();
        assertThat(firstNode.properties())
                .containsOnlyKeys(
                        "workflowType", "runtimeDriver", "finalState", "outcome", "provenance", "startedAt", "endedAt")
                .containsEntry("workflowType", "IMPLEMENT")
                .containsEntry("runtimeDriver", "codex")
                .containsEntry("finalState", "RUNNING")
                .containsEntry("outcome", "NONE")
                .containsEntry("provenance", "ISSUE_THREAD")
                .doesNotContainKeys("branch", "provider", "model", "tokenUsage", "requirementUids");
        verify(runRepository).findForGraphProjection(PROJECT_ID);
    }

    @Test
    void projectsStableAssociationAndEveryRepeatedPhaseEventInDirection() {
        var run = completeRun("autarchy-ai/Ground-Control", 1311);
        var first = event(run.getId(), "ci", PhaseEventType.STARTED, Instant.parse("2026-07-19T12:00:00Z"));
        first.setCycleIndex(1);
        first.setOutcome("queued");
        var second = event(run.getId(), "ci", PhaseEventType.COMPLETED, Instant.parse("2026-07-19T12:05:00Z"));

        when(runRepository.findForGraphProjection(PROJECT_ID)).thenReturn(List.of(run));
        when(eventRepository.findForGraphProjection(PROJECT_ID)).thenReturn(List.of(first, second));

        var edges = contributor.contributeEdges(PROJECT_ID);

        assertThat(edges).hasSize(3);
        var targetId = GraphIds.workflowWorkItemReferenceNodeId(PROJECT_ID, "autarchy-ai/Ground-Control", 1311);
        assertThat(edges)
                .allMatch(edge -> edge.sourceId().equals(GraphIds.nodeId(GraphEntityType.WORKFLOW_RUN, run.getId())))
                .allMatch(edge -> edge.targetId().equals(targetId))
                .extracting(GraphEdge::edgeType)
                .containsExactlyInAnyOrder("RUN_FOR_WORK_ITEM", "WORKFLOW_PHASE_EVENT", "WORKFLOW_PHASE_EVENT");
        assertThat(edges)
                .filteredOn(edge -> edge.edgeType().equals("WORKFLOW_PHASE_EVENT"))
                .extracting(GraphEdge::id)
                .containsExactlyInAnyOrder(
                        first.getId().toString(), second.getId().toString());
        var firstEdge = edges.stream()
                .filter(edge -> edge.id().equals(first.getId().toString()))
                .findFirst()
                .orElseThrow();
        assertThat(firstEdge.properties())
                .containsOnlyKeys("phase", "eventType", "cycleIndex", "occurredAt", "outcome", "provenance")
                .containsEntry("phase", "ci")
                .containsEntry("eventType", "STARTED")
                .containsEntry("cycleIndex", 1)
                .containsEntry("outcome", "queued")
                .containsEntry("provenance", "ISSUE_THREAD");
        verify(runRepository).findForGraphProjection(PROJECT_ID);
        verify(eventRepository).findForGraphProjection(PROJECT_ID);
    }

    @Test
    void omitsEdgesForPartialRunIdentityAndDanglingEvents() {
        var partial = newRun();
        partial.setRepo("autarchy-ai/Ground-Control");
        var dangling = event(UUID.randomUUID(), "ci", PhaseEventType.FAILED, Instant.parse("2026-07-19T12:00:00Z"));

        when(runRepository.findForGraphProjection(PROJECT_ID)).thenReturn(List.of(partial));
        when(eventRepository.findForGraphProjection(PROJECT_ID)).thenReturn(List.of(dangling));

        assertThat(contributor.contributeEdges(PROJECT_ID)).isEmpty();
    }

    private static WorkflowRun completeRun(String repo, int issueNumber) {
        var run = newRun();
        run.setRepo(repo);
        run.setIssueNumber(issueNumber);
        return run;
    }

    private static WorkflowRun newRun() {
        var run = new WorkflowRun(PROJECT, "IMPLEMENT", TelemetryProvenance.ISSUE_THREAD);
        setField(run, "id", UUID.randomUUID());
        run.setFinalState(WorkflowRunState.RUNNING);
        run.setOutcome(WorkflowRunOutcome.NONE);
        return run;
    }

    private static WorkflowPhaseEvent event(UUID runId, String phase, PhaseEventType type, Instant occurredAt) {
        var event =
                new WorkflowPhaseEvent(runId, PROJECT, phase, type, occurredAt, null, TelemetryProvenance.ISSUE_THREAD);
        setField(event, "id", UUID.randomUUID());
        return event;
    }
}

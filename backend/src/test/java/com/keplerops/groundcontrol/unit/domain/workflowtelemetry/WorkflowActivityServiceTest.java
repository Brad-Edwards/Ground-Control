package com.keplerops.groundcontrol.unit.domain.workflowtelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.workflowtelemetry.CapabilityTier;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventEmitter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowGateFindingRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.StationCatalog;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowActivityProperties;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowActivityService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class WorkflowActivityServiceTest {

    private static final String PROJECT = "ground-control";
    private static final Instant AS_OF = Instant.parse("2026-07-30T12:00:00Z");

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowPhaseEventRepository phaseEventRepository;

    @Mock
    private WorkflowGateFindingRepository findingRepository;

    @Mock
    private StationCatalog stationCatalog;

    private WorkflowActivityProperties properties;
    private WorkflowActivityService service;

    @BeforeEach
    void setUp() {
        properties = new WorkflowActivityProperties();
        properties.setStallThreshold(Duration.ofMinutes(20));
        properties.setMaxOpenRuns(2);
        properties.setRecentRuns(3);
        service = new WorkflowActivityService(
                runRepository, phaseEventRepository, findingRepository, stationCatalog, properties, () -> AS_OF);
    }

    @Test
    void snapshotBatchLoadsOpenRunActivityAndKeepsTerminalContextBounded() {
        var running = run(WorkflowRunState.RUNNING, null);
        var waiting = run(WorkflowRunState.READY_FOR_REVIEW, null);
        var terminal = run(WorkflowRunState.MERGED, AS_OF.minusSeconds(60));
        var current =
                lifecycleEvent(running.getId(), "completion_gate", PhaseEventType.STARTED, AS_OF.minusSeconds(300), 2);
        var completedGate =
                lifecycleEvent(running.getId(), "codex_review", PhaseEventType.COMPLETED, AS_OF.minusSeconds(360), 1);
        completedGate.setStationId("codex_review");
        completedGate.setStationResult(StationResult.FAIL);
        completedGate.setFindingsDropped(2);
        setField(completedGate, "durationMs", 12_000L);
        var routing = routingEvent(running.getId(), AS_OF.minusSeconds(330));

        when(runRepository.countByProjectAndFinalStateIn(eqProject(), any())).thenReturn(5L);
        when(runRepository.findByProjectAndFinalStateInOrderByCreatedAtDesc(eqProject(), any(), any(Pageable.class)))
                .thenReturn(List.of(running, waiting));
        when(runRepository.findRecentTerminalRuns(eqProject(), any(), any(Pageable.class)))
                .thenReturn(List.of(terminal));
        when(phaseEventRepository.findLatestLifecycleEvents(eqProject(), any())).thenReturn(List.of(current));
        when(phaseEventRepository.findLatestStationAttempts(eqProject(), any())).thenReturn(List.of(completedGate));
        when(phaseEventRepository.findLatestRoutingObservations(eqProject(), any()))
                .thenReturn(List.of(routing));
        when(findingRepository.countByPhaseEventIds(eqProject(), any()))
                .thenReturn(List.of(countRow(completedGate.getId(), 4)));
        when(stationCatalog.displayNameForPhase("completion_gate")).thenReturn("Completion gate");
        when(stationCatalog.stationTitle("codex_review")).thenReturn("Codex review");
        when(stationCatalog.stationOrder()).thenReturn(List.of("completion_gate", "codex_review"));

        var snapshot = service.snapshot(PROJECT);

        assertThat(snapshot.asOf()).isEqualTo(AS_OF);
        assertThat(snapshot.openRunTotal()).isEqualTo(5);
        assertThat(snapshot.openRunsTruncated()).isTrue();
        assertThat(snapshot.openRuns()).hasSize(2);
        assertThat(snapshot.recentlyFinished()).containsExactly(terminal);

        var row = snapshot.openRuns().getFirst();
        assertThat(row.run()).isSameAs(running);
        assertThat(row.currentPhase()).isEqualTo("completion_gate");
        assertThat(row.currentPhaseTitle()).isEqualTo("Completion gate");
        assertThat(row.currentPhaseSince()).isEqualTo(AS_OF.minusSeconds(300));
        assertThat(row.currentCycle()).isEqualTo(2);
        assertThat(row.stallThreshold()).isEqualTo(Duration.ofMinutes(20));
        assertThat(row.routing().stage()).isEqualTo("planning");
        assertThat(row.routing().tier()).isEqualTo(CapabilityTier.HIGH);
        assertThat(row.routing().model()).isEqualTo("claude-opus-4-8");
        assertThat(row.gates()).hasSize(2);
        assertThat(row.gates().getFirst()).satisfies(gate -> {
            assertThat(gate.stationId()).isEqualTo("completion_gate");
            assertThat(gate.stationResult()).isEqualTo(StationResult.UNOBSERVED);
            assertThat(gate.eventType()).isNull();
        });
        assertThat(row.gates().get(1)).satisfies(gate -> {
            assertThat(gate.stationId()).isEqualTo("codex_review");
            assertThat(gate.stationTitle()).isEqualTo("Codex review");
            assertThat(gate.eventType()).isEqualTo(PhaseEventType.COMPLETED);
            assertThat(gate.stationResult()).isEqualTo(StationResult.FAIL);
            assertThat(gate.findingCount()).isEqualTo(4);
            assertThat(gate.findingsDropped()).isEqualTo(2);
        });

        var openPage = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository).findByProjectAndFinalStateInOrderByCreatedAtDesc(eqProject(), any(), openPage.capture());
        assertThat(openPage.getValue().getPageSize()).isEqualTo(properties.getMaxOpenRuns());
        var terminalPage = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository).findRecentTerminalRuns(eqProject(), any(), terminalPage.capture());
        assertThat(terminalPage.getValue().getPageSize()).isEqualTo(properties.getRecentRuns());
        verify(phaseEventRepository).findLatestLifecycleEvents(eqProject(), any());
        verify(phaseEventRepository).findLatestStationAttempts(eqProject(), any());
        verify(phaseEventRepository).findLatestRoutingObservations(eqProject(), any());
        verify(findingRepository).countByPhaseEventIds(eqProject(), any());
    }

    @Test
    void snapshotWithNoOpenRunsSkipsEveryEventAndFindingQuery() {
        var terminal = run(WorkflowRunState.FAILED, null);
        when(runRepository.countByProjectAndFinalStateIn(eqProject(), any())).thenReturn(0L);
        when(runRepository.findByProjectAndFinalStateInOrderByCreatedAtDesc(eqProject(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(runRepository.findRecentTerminalRuns(eqProject(), any(), any(Pageable.class)))
                .thenReturn(List.of(terminal));

        var snapshot = service.snapshot(PROJECT);

        assertThat(snapshot.openRuns()).isEmpty();
        assertThat(snapshot.openRunsTruncated()).isFalse();
        assertThat(snapshot.recentlyFinished()).containsExactly(terminal);
        verify(phaseEventRepository, never()).findLatestLifecycleEvents(any(), any());
        verify(phaseEventRepository, never()).findLatestStationAttempts(any(), any());
        verify(phaseEventRepository, never()).findLatestRoutingObservations(any(), any());
        verify(findingRepository, never()).countByPhaseEventIds(any(), any());
    }

    private static String eqProject() {
        return org.mockito.ArgumentMatchers.eq(PROJECT);
    }

    private static WorkflowRun run(WorkflowRunState state, Instant endedAt) {
        var run = new WorkflowRun(PROJECT, "implement", TelemetryProvenance.LIVE_EMISSION);
        setField(run, "id", UUID.randomUUID());
        run.setRepo("autarchy-ai/Ground-Control");
        run.setIssueNumber(1437);
        run.setBranch("1437-live-activity-view");
        run.setStartedAt(AS_OF.minusSeconds(900));
        run.setFinalState(state);
        run.setOutcome(state == WorkflowRunState.MERGED ? WorkflowRunOutcome.MERGED : WorkflowRunOutcome.NONE);
        run.setEndedAt(endedAt);
        return run;
    }

    private static WorkflowPhaseEvent lifecycleEvent(
            UUID runId, String phase, PhaseEventType type, Instant occurredAt, int cycle) {
        var event = new WorkflowPhaseEvent(
                runId, PROJECT, phase, type, occurredAt, null, TelemetryProvenance.LIVE_EMISSION);
        setField(event, "id", UUID.randomUUID());
        event.setCycleIndex(cycle);
        event.setEmitter(PhaseEventEmitter.ADR061_WORKFLOW_TELEMETRY);
        return event;
    }

    private static WorkflowPhaseEvent routingEvent(UUID runId, Instant occurredAt) {
        var event = new WorkflowPhaseEvent(
                runId,
                PROJECT,
                "planning",
                PhaseEventType.COMPLETED,
                occurredAt,
                45_000L,
                TelemetryProvenance.LIVE_EMISSION);
        setField(event, "id", UUID.randomUUID());
        event.setEmitter(PhaseEventEmitter.ADR036_STEP_JSONL);
        event.setStepAlias("Step 4");
        event.setTier(CapabilityTier.HIGH);
        event.setModel("claude-opus-4-8");
        return event;
    }

    private static WorkflowGateFindingRepository.PhaseEventFindingCount countRow(UUID eventId, long count) {
        return new WorkflowGateFindingRepository.PhaseEventFindingCount() {
            @Override
            public UUID getPhaseEventId() {
                return eventId;
            }

            @Override
            public long getFindingCount() {
                return count;
            }
        };
    }

    private static void setField(Object target, String name, Object value) {
        var type = target.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("Field not found: " + name);
    }
}

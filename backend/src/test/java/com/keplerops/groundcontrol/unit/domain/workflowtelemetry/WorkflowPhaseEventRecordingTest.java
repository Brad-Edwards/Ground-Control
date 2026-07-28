package com.keplerops.groundcontrol.unit.domain.workflowtelemetry;

import static com.keplerops.groundcontrol.unit.domain.workflowtelemetry.WorkflowTelemetryFixtures.FROM;
import static com.keplerops.groundcontrol.unit.domain.workflowtelemetry.WorkflowTelemetryFixtures.phaseEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordPhaseEventCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowMeasurementService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class WorkflowPhaseEventRecordingTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowPhaseEventRepository phaseEventRepository;

    @Mock
    private WorkflowMeasurementService measurementService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WorkflowTelemetryService service;

    // ---- recordPhaseEvent ----------------------------------------------------------------------

    @Test
    void recordPhaseEventThrowsNotFoundWhenRunMissingOrForeignProject() {
        // A foreign-project run resolves to empty via findByIdAndProject, so a cross-project caller
        // is treated the same as not-found and cannot append events to another project's run.
        var runId = UUID.randomUUID();
        when(runRepository.findByIdAndProjectForUpdate(runId, "gc")).thenReturn(Optional.empty());
        var command = new RecordPhaseEventCommand(
                runId,
                "gc",
                "ci",
                PhaseEventType.FAILED,
                1,
                FROM,
                1000L,
                "failure",
                TelemetryProvenance.ISSUE_THREAD,
                null,
                null,
                null,
                null,
                null);
        assertThatThrownBy(() -> service.recordPhaseEvent(command)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void recordPhaseEventDenormalizesRunProjectOntoEvent() {
        var runId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.ISSUE_THREAD);
        when(runRepository.findByIdAndProjectForUpdate(runId, "ground-control")).thenReturn(Optional.of(run));
        when(phaseEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordPhaseEvent(new RecordPhaseEventCommand(
                runId,
                "ground-control",
                "codex_review",
                PhaseEventType.COMPLETED,
                2,
                FROM,
                5000L,
                "clean",
                TelemetryProvenance.ISSUE_THREAD,
                null,
                null,
                null,
                null,
                null));

        var captor = ArgumentCaptor.forClass(WorkflowPhaseEvent.class);
        verify(phaseEventRepository).save(captor.capture());
        WorkflowPhaseEvent event = captor.getValue();
        assertThat(event.getProject()).isEqualTo("ground-control");
        assertThat(event.getPhase()).isEqualTo("codex_review");
        assertThat(event.getCycleIndex()).isEqualTo(2);
        assertThat(event.getEventType()).isEqualTo(PhaseEventType.COMPLETED);
    }

    @Test
    void recordPhaseEventRejectsReservedMarkerInPhase() {
        var command = new RecordPhaseEventCommand(
                UUID.randomUUID(),
                "gc",
                "<!-- gc:phase -->",
                PhaseEventType.STARTED,
                null,
                FROM,
                null,
                null,
                TelemetryProvenance.ISSUE_THREAD,
                null,
                null,
                null,
                null,
                null);
        assertThatThrownBy(() -> service.recordPhaseEvent(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("reserved");
    }

    // ---- recordPhaseEvent: attempt ordinal + deterministic identity (issue #1435) ---------------

    @Test
    void recordPhaseEventAssignsTheNextAttemptOrdinalToAStartedEventWithNoCycleIndex() {
        // STARTED opens an attempt. An emitter cannot know how many earlier attempts a restart or a
        // different process already recorded, so the ordinal is derived from durable history.
        var runId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.LIVE_EMISSION);
        when(runRepository.findByIdAndProjectForUpdate(runId, "ground-control")).thenReturn(Optional.of(run));
        when(phaseEventRepository.countByRunIdAndPhaseAndEventType(runId, "ci", PhaseEventType.STARTED))
                .thenReturn(2L);
        when(phaseEventRepository.findByRunIdAndSourceId(any(), any())).thenReturn(Optional.empty());
        when(phaseEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var saved = service.recordPhaseEvent(phaseEvent(runId, "ci", PhaseEventType.STARTED, null, null));

        assertThat(saved.getCycleIndex()).isEqualTo(2);
        assertThat(saved.getSourceId()).isEqualTo("ci:STARTED:2");
    }

    @Test
    void recordPhaseEventAssignsAttemptZeroToANonStartedEventWithNoCycleIndex() {
        // An emitter that cannot attest attempt order (issue-thread backfill) lands on the first
        // attempt, so its record converges with live emission instead of appending a phantom retry.
        var runId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.ISSUE_THREAD);
        when(runRepository.findByIdAndProjectForUpdate(runId, "ground-control")).thenReturn(Optional.of(run));
        when(phaseEventRepository.findByRunIdAndSourceId(any(), any())).thenReturn(Optional.empty());
        when(phaseEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var saved = service.recordPhaseEvent(phaseEvent(runId, "ci", PhaseEventType.COMPLETED, null, null));

        assertThat(saved.getCycleIndex()).isZero();
        assertThat(saved.getSourceId()).isEqualTo("ci:COMPLETED:0");
    }

    @Test
    void recordPhaseEventReturnsTheExistingEventWhenTheSameLogicalFactIsRecordedTwice() {
        // Live emission and a later backfill describe the same attempt. The append-only table stays
        // append-only per LOGICAL fact: the second write is a no-op, not a duplicated event.
        var runId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.LIVE_EMISSION);
        var already = new WorkflowPhaseEvent(
                runId, "ground-control", "ci", PhaseEventType.COMPLETED, FROM, 10L, TelemetryProvenance.LIVE_EMISSION);
        already.setSourceId("ci:COMPLETED:0");
        when(runRepository.findByIdAndProjectForUpdate(runId, "ground-control")).thenReturn(Optional.of(run));
        when(phaseEventRepository.findByRunIdAndSourceId(runId, "ci:COMPLETED:0"))
                .thenReturn(Optional.of(already));

        var saved = service.recordPhaseEvent(phaseEvent(runId, "ci", PhaseEventType.COMPLETED, 0, null));

        assertThat(saved).isSameAs(already);
        verify(phaseEventRepository, never()).save(any());
    }

    @Test
    void recordPhaseEventKeepsAnExplicitSourceIdSuppliedByTheEmitter() {
        var runId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.LIVE_EMISSION);
        when(runRepository.findByIdAndProjectForUpdate(runId, "ground-control")).thenReturn(Optional.of(run));
        when(phaseEventRepository.findByRunIdAndSourceId(runId, "custom-key")).thenReturn(Optional.empty());
        when(phaseEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var saved = service.recordPhaseEvent(phaseEvent(runId, "ci", PhaseEventType.COMPLETED, 1, "custom-key"));

        assertThat(saved.getSourceId()).isEqualTo("custom-key");
    }

    @Test
    void deriveSourceIdTreatsAnAbsentOrdinalAsTheFirstAttempt() {
        // The entity fills this in on persist and the service computes the same value before its
        // idempotency lookup. If the two ever disagreed, a re-recorded fact would be deduplicated
        // against a key that is not the one stored, and the duplicate would land anyway.
        assertThat(WorkflowPhaseEvent.deriveSourceId("ci", PhaseEventType.COMPLETED, null))
                .isEqualTo("ci:COMPLETED:0");
        assertThat(WorkflowPhaseEvent.deriveSourceId("ci", PhaseEventType.STARTED, 2))
                .isEqualTo("ci:STARTED:2");
    }

    @Test
    void recordPhaseEventRejectsANegativeCycleIndex() {
        // A negative ordinal would flow into the derived identity as "ci:COMPLETED:-1", which no
        // other emitter and no V204 backfill row can ever produce, so the fact would never converge.
        var command = new RecordPhaseEventCommand(
                UUID.randomUUID(),
                "ground-control",
                "ci",
                PhaseEventType.COMPLETED,
                -1,
                FROM,
                10L,
                null,
                TelemetryProvenance.LIVE_EMISSION,
                null,
                null,
                null,
                null,
                null);
        assertThatThrownBy(() -> service.recordPhaseEvent(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("cycleIndex");
    }

    @Test
    void recordPhaseEventRejectsANegativeDuration() {
        var command = new RecordPhaseEventCommand(
                UUID.randomUUID(),
                "ground-control",
                "ci",
                PhaseEventType.COMPLETED,
                0,
                FROM,
                -1L,
                null,
                TelemetryProvenance.LIVE_EMISSION,
                null,
                null,
                null,
                null,
                null);
        assertThatThrownBy(() -> service.recordPhaseEvent(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("durationMs");
    }

    @Test
    void recordPhaseEventRejectsReservedMarkerInSourceId() {
        var command = phaseEvent(UUID.randomUUID(), "ci", PhaseEventType.COMPLETED, 0, "<!-- gc:phase -->");
        assertThatThrownBy(() -> service.recordPhaseEvent(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("reserved");
    }

    // ---- listPhaseEvents (issue #1435) ---------------------------------------------------------

    @Test
    void listPhaseEventsReturnsTheRunsEventsInOrder() {
        var runId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.LIVE_EMISSION);
        var event = new WorkflowPhaseEvent(
                runId, "ground-control", "ci", PhaseEventType.STARTED, FROM, null, TelemetryProvenance.LIVE_EMISSION);
        when(runRepository.findByIdAndProject(runId, "ground-control")).thenReturn(Optional.of(run));
        when(phaseEventRepository.findByRunIdAndProjectOrderByOccurredAtAscIdAsc(
                        eq(runId), eq("ground-control"), any()))
                .thenReturn(List.of(event));

        assertThat(service.listPhaseEvents(runId, "ground-control", 50)).containsExactly(event);
    }

    @Test
    void listPhaseEventsTreatsAForeignProjectRunAsNotFound() {
        // The run id is not a capability: resolving it project-scoped is what authorizes the read, so
        // a caller holding another project's run id must not receive that project's events.
        var runId = UUID.randomUUID();
        when(runRepository.findByIdAndProject(runId, "other")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listPhaseEvents(runId, "other", 50)).isInstanceOf(NotFoundException.class);
        verify(phaseEventRepository, never()).findByRunIdAndProjectOrderByOccurredAtAscIdAsc(any(), any(), any());
    }

    @Test
    void listPhaseEventsRejectsAMissingRunIdOrProject() {
        var runId = UUID.randomUUID();
        assertThatThrownBy(() -> service.listPhaseEvents(null, "ground-control", 50))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("runId");
        assertThatThrownBy(() -> service.listPhaseEvents(runId, "  ", 50))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("project");
    }

    @Test
    void listPhaseEventsClampsTheRequestedPageSize() {
        // An unbounded page would let one request pull a whole project's event history into memory.
        var runId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.LIVE_EMISSION);
        when(runRepository.findByIdAndProject(runId, "ground-control")).thenReturn(Optional.of(run));
        when(phaseEventRepository.findByRunIdAndProjectOrderByOccurredAtAscIdAsc(any(), any(), any()))
                .thenReturn(List.of());

        service.listPhaseEvents(runId, "ground-control", 100_000);
        service.listPhaseEvents(runId, "ground-control", 0);

        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(phaseEventRepository, times(2))
                .findByRunIdAndProjectOrderByOccurredAtAscIdAsc(any(), any(), pageable.capture());
        assertThat(pageable.getAllValues().get(0).getPageSize()).isEqualTo(500);
        assertThat(pageable.getAllValues().get(1).getPageSize()).isEqualTo(1);
    }
}

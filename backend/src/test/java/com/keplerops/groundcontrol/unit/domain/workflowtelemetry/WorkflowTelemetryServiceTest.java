package com.keplerops.groundcontrol.unit.domain.workflowtelemetry;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static com.keplerops.groundcontrol.unit.domain.workflowtelemetry.WorkflowTelemetryFixtures.FROM;
import static com.keplerops.groundcontrol.unit.domain.workflowtelemetry.WorkflowTelemetryFixtures.liveCommand;
import static com.keplerops.groundcontrol.unit.domain.workflowtelemetry.WorkflowTelemetryFixtures.openRun;
import static com.keplerops.groundcontrol.unit.domain.workflowtelemetry.WorkflowTelemetryFixtures.phaseEvent;
import static com.keplerops.groundcontrol.unit.domain.workflowtelemetry.WorkflowTelemetryFixtures.runCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.ImportRunCostCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordWorkflowRunCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowMeasurementService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryChangeEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class WorkflowTelemetryServiceTest {

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

    private static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");

    // ---- recordRun: create vs merge upsert -----------------------------------------------------

    @Test
    void recordRunCreatesNewRunWhenNoneMatchesTheUpsertKey() {
        // The new-run path inserts via saveAndFlush so a unique-key violation surfaces eagerly.
        when(runRepository.findRunForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(runRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var command = runCommand()
                .withState(WorkflowRunState.READY_FOR_REVIEW)
                .withOutcome(WorkflowRunOutcome.MERGED)
                .build();

        var saved = service.recordRun(command);

        var captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runRepository).saveAndFlush(captor.capture());
        WorkflowRun run = captor.getValue();
        assertThat(run.getProject()).isEqualTo("ground-control");
        assertThat(run.getIssueNumber()).isEqualTo(859);
        assertThat(run.getBranch()).isEqualTo("859-feature");
        assertThat(run.getWorkflowType()).isEqualTo("implement");
        assertThat(run.getFinalState()).isEqualTo(WorkflowRunState.READY_FOR_REVIEW);
        assertThat(run.getOutcome()).isEqualTo(WorkflowRunOutcome.MERGED);
        assertThat(run.getProvenance()).isEqualTo(TelemetryProvenance.ISSUE_THREAD);
        assertThat(saved).isSameAs(run);
    }

    @Test
    void recordRunRaisesConflictWhenConcurrentInsertViolatesTheUniqueKey() {
        // Two concurrent observations of the same key: the loser's insert is rejected by the unique
        // index, surfaced as a retryable ConflictException (a retry then takes the update path).
        when(runRepository.findRunForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(runRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        var command = runCommand().build();
        assertThatThrownBy(() -> service.recordRun(command))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("retry");
    }

    @Test
    void recordRunMergesNonNullFieldsOntoExistingRun() {
        // An existing RUNNING run is refined by a later observation carrying only the merge outcome.
        var existing = new WorkflowRun("ground-control", "implement", TelemetryProvenance.ISSUE_THREAD);
        existing.setIssueNumber(859);
        existing.setBranch("859-feature");
        existing.setStartedAt(FROM);
        when(runRepository.findRunForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(existing));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var update = new RecordWorkflowRunCommand(
                "ground-control",
                null,
                859,
                42,
                "859-feature",
                "implement",
                null,
                null,
                null,
                TO,
                WorkflowRunState.MERGED,
                WorkflowRunOutcome.MERGED,
                TelemetryProvenance.ISSUE_THREAD,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        service.recordRun(update);

        // The early-start timestamp survives; the merge outcome and end time are applied.
        assertThat(existing.getStartedAt()).isEqualTo(FROM);
        assertThat(existing.getEndedAt()).isEqualTo(TO);
        assertThat(existing.getPrNumber()).isEqualTo(42);
        assertThat(existing.getFinalState()).isEqualTo(WorkflowRunState.MERGED);
        assertThat(existing.getOutcome()).isEqualTo(WorkflowRunOutcome.MERGED);
    }

    // ---- recordRun: monotonic merge (issue #1435) ----------------------------------------------

    @Test
    void recordRunKeepsTheEarliestStartedAt() {
        // A later observation of the same run must not push the run's start time forward: cycle time
        // is measured from when the run actually began.
        var existing = openRun(FROM);
        when(runRepository.findRunForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(existing));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRun(liveCommand(TO, null, WorkflowRunState.RUNNING));

        assertThat(existing.getStartedAt()).isEqualTo(FROM);
    }

    @Test
    void recordRunRejectsEndedAtBeforeStartedAt() {
        var existing = openRun(TO);
        when(runRepository.findRunForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(existing));

        var command = liveCommand(null, FROM, WorkflowRunState.MERGED);
        assertThatThrownBy(() -> service.recordRun(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("endedAt");
    }

    @Test
    void recordRunDoesNotReopenATerminalRun() {
        // A delayed live write or a stale issue-thread backfill arriving after the merge must never
        // put a completed run back into RUNNING, which would corrupt every active-run count.
        var existing = openRun(FROM);
        existing.setFinalState(WorkflowRunState.MERGED);
        existing.setOutcome(WorkflowRunOutcome.MERGED);
        existing.setEndedAt(TO);
        when(runRepository.findRunForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(existing));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRun(liveCommand(FROM, null, WorkflowRunState.RUNNING));

        assertThat(existing.getFinalState()).isEqualTo(WorkflowRunState.MERGED);
        assertThat(existing.getOutcome()).isEqualTo(WorkflowRunOutcome.MERGED);
        assertThat(existing.getEndedAt()).isEqualTo(TO);
    }

    @Test
    void recordRunSupersedesOtherOpenRunsOfTheSameWorkItemOnALiveOpen() {
        // A fresh live attempt on a new branch is the only abandonment the tool layer can observe:
        // the previous attempt for the same issue is over, so it gets a terminal state and an end.
        var abandoned = new WorkflowRun("ground-control", "implement", TelemetryProvenance.LIVE_EMISSION);
        abandoned.setIssueNumber(859);
        abandoned.setBranch("859-old-attempt");
        abandoned.setStartedAt(FROM);
        when(runRepository.findRunForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(runRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.findOpenRunsForWorkItem("ground-control", null, 859, "859-feature"))
                .thenReturn(List.of(abandoned));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRun(liveCommand(FROM, null, WorkflowRunState.RUNNING));

        assertThat(abandoned.getFinalState()).isEqualTo(WorkflowRunState.SUPERSEDED);
        assertThat(abandoned.getEndedAt()).isNotNull();
    }

    @Test
    void recordRunDoesNotSupersedeWhenTheObservationIsABackfill() {
        // Reconstructing an old thread with gc_workflow_run_ingest must never close a live run.
        when(runRepository.findRunForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(runRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordRun(
                runCommand().withProvenance(TelemetryProvenance.ISSUE_THREAD).build());

        verify(runRepository, never()).findOpenRunsForWorkItem(any(), any(), any(), any());
    }

    @Test
    void recordRunRejectsBlankProject() {
        var command = runCommand().withProject("  ").build();
        assertThatThrownBy(() -> service.recordRun(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("project");
    }

    @Test
    void recordRunRejectsNullProvenance() {
        var command = runCommand().withProvenance(null).build();
        assertThatThrownBy(() -> service.recordRun(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("provenance");
    }

    @Test
    void recordRunRejectsReservedMarkerInBranch() {
        var command = runCommand().withBranch("x<!-- gc:phase -->").build();
        assertThatThrownBy(() -> service.recordRun(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void recordRunRejectsNegativeCostProxy() {
        var command = runCommand().withCost(new BigDecimal("-1.00")).build();
        assertThatThrownBy(() -> service.recordRun(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("costProxy");
    }

    // ---- live-stream change notification (issue #1436) ------------------------------------------

    @Test
    void recordRunAnnouncesTheSavedRun() {
        var runId = UUID.randomUUID();
        when(runRepository.findRunForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(runRepository.saveAndFlush(any())).thenAnswer(inv -> {
            setField(inv.getArgument(0), "id", runId);
            return inv.getArgument(0);
        });

        service.recordRun(runCommand().build());

        var change = capturedChange();
        assertThat(change.kind()).isEqualTo(WorkflowTelemetryChangeEvent.Kind.RUN);
        assertThat(change.project()).isEqualTo("ground-control");
        assertThat(change.entityId()).isEqualTo(runId);
    }

    @Test
    void recordRunAnnouncesEveryRunItSupersedes() {
        // The retired attempt changes state too; without its own notification a watching dashboard
        // would keep showing it as RUNNING until the next poll.
        var supersededId = UUID.randomUUID();
        var superseded = new WorkflowRun("ground-control", "implement", TelemetryProvenance.LIVE_EMISSION);
        setField(superseded, "id", supersededId);
        superseded.setFinalState(WorkflowRunState.RUNNING);
        when(runRepository.findRunForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(runRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.findOpenRunsForWorkItem(any(), any(), any(), any())).thenReturn(List.of(superseded));

        service.recordRun(runCommand()
                .withProvenance(TelemetryProvenance.LIVE_EMISSION)
                .withState(WorkflowRunState.RUNNING)
                .build());

        var captor = ArgumentCaptor.forClass(WorkflowTelemetryChangeEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(WorkflowTelemetryChangeEvent::entityId)
                .contains(supersededId);
    }

    @Test
    void recordPhaseEventAnnouncesOnlyAGenuinelyNewAppend() {
        var runId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.LIVE_EMISSION);
        when(runRepository.findByIdAndProjectForUpdate(runId, "ground-control")).thenReturn(Optional.of(run));
        when(phaseEventRepository.save(any())).thenAnswer(inv -> {
            setField(inv.getArgument(0), "id", eventId);
            return inv.getArgument(0);
        });

        service.recordPhaseEvent(phaseEvent(runId, "ci", PhaseEventType.COMPLETED, 0, null));

        var change = capturedChange();
        assertThat(change.kind()).isEqualTo(WorkflowTelemetryChangeEvent.Kind.PHASE_EVENT);
        assertThat(change.runId()).isEqualTo(runId);
        assertThat(change.entityId()).isEqualTo(eventId);
    }

    @Test
    void recordPhaseEventAnnouncesNothingOnTheIdempotentReplay() {
        // A retry or a backfill of an already-stored fact must not re-notify; a subscriber that
        // missed the original resynchronizes on reconnect instead.
        var runId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.LIVE_EMISSION);
        when(runRepository.findByIdAndProjectForUpdate(runId, "ground-control")).thenReturn(Optional.of(run));
        when(phaseEventRepository.findByRunIdAndSourceId(eq(runId), any()))
                .thenReturn(Optional.of(new WorkflowPhaseEvent(
                        runId,
                        "ground-control",
                        "ci",
                        PhaseEventType.COMPLETED,
                        FROM,
                        10L,
                        TelemetryProvenance.LIVE_EMISSION)));

        service.recordPhaseEvent(phaseEvent(runId, "ci", PhaseEventType.COMPLETED, 0, null));

        verify(eventPublisher, never()).publishEvent(any(WorkflowTelemetryChangeEvent.class));
        verify(phaseEventRepository, never()).save(any());
    }

    @Test
    void importCostAnnouncesTheRefinedRun() {
        var runId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.LIVE_EMISSION);
        setField(run, "id", runId);
        when(runRepository.findByIdAndProject(runId, "ground-control")).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.importCost(new ImportRunCostCommand(
                runId, "ground-control", "anthropic", "claude", 3, 20, new BigDecimal("1.50"), "USD", 1000L));

        assertThat(capturedChange().entityId()).isEqualTo(runId);
    }

    // These two assert only the service's empty -> NotFoundException mapping. They deliberately do
    // NOT claim project isolation: the repository is mocked here, so they would keep passing if the
    // project predicate were dropped from the query. The real-DB two-project denial lives in
    // WorkflowRunStreamPublicationIntegrationTest, which is the only place that can prove it.

    @Test
    void getRunThrowsNotFoundWhenTheScopedLookupResolvesEmpty() {
        var runId = UUID.randomUUID();
        when(runRepository.findByIdAndProject(runId, "ground-control")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRun(runId, "ground-control")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getPhaseEventThrowsNotFoundWhenTheScopedLookupResolvesEmpty() {
        var eventId = UUID.randomUUID();
        when(phaseEventRepository.findByIdAndProject(eventId, "ground-control")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPhaseEvent(eventId, "ground-control"))
                .isInstanceOf(NotFoundException.class);
    }

    private WorkflowTelemetryChangeEvent capturedChange() {
        var captor = ArgumentCaptor.forClass(WorkflowTelemetryChangeEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    // ---- run-state vocabulary ------------------------------------------------------------------

    @Test
    void terminalRunStatesAreExactlyTheEndStates() {
        // The monotonic merge keys on this: mislabelling READY_FOR_REVIEW or ESCALATED as terminal
        // would freeze a run that is still moving, and the reverse would let a merged run reopen.
        assertThat(java.util.Arrays.stream(WorkflowRunState.values())
                        .filter(WorkflowRunState::isTerminal)
                        .toList())
                .containsExactlyInAnyOrder(
                        WorkflowRunState.MERGED,
                        WorkflowRunState.CLOSED,
                        WorkflowRunState.ABANDONED,
                        WorkflowRunState.SUPERSEDED,
                        WorkflowRunState.FAILED);
    }
}

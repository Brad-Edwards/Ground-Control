package com.keplerops.groundcontrol.unit.domain.workflowtelemetry;

import static com.keplerops.groundcontrol.TestUtil.setField;
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
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository.RunRollupRow;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.ImportRunCostCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordPhaseEventCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordWorkflowRunCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowRunFilter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryChangeEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class WorkflowTelemetryServiceTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowPhaseEventRepository phaseEventRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WorkflowTelemetryService service;

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
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

    // ---- importCost ----------------------------------------------------------------------------

    @Test
    void importCostAppliesOnlyNonNullFields() {
        var runId = UUID.randomUUID();
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.ISSUE_THREAD);
        run.setProvider("anthropic");
        when(runRepository.findByIdAndProject(runId, "ground-control")).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.importCost(new ImportRunCostCommand(
                runId, "ground-control", null, "claude-opus-4-8", null, null, new BigDecimal("12.5000"), "USD", 1000L));

        assertThat(run.getProvider()).isEqualTo("anthropic"); // untouched (null in command)
        assertThat(run.getModel()).isEqualTo("claude-opus-4-8");
        assertThat(run.getCostProxy()).isEqualByComparingTo("12.5000");
        assertThat(run.getCostCurrency()).isEqualTo("USD");
        assertThat(run.getTokenUsage()).isEqualTo(1000L);
    }

    @Test
    void importCostThrowsNotFoundWhenRunMissingOrForeignProject() {
        var runId = UUID.randomUUID();
        when(runRepository.findByIdAndProject(runId, "gc")).thenReturn(Optional.empty());
        var command = new ImportRunCostCommand(runId, "gc", null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.importCost(command)).isInstanceOf(NotFoundException.class);
    }

    // ---- aggregate: window validation + mapping ------------------------------------------------

    @Test
    void aggregateRejectsFromAfterTo() {
        var filter = new WorkflowRunFilter(TO, FROM, "p", null, null, null, null, null);
        assertThatThrownBy(() -> service.aggregate(filter))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("before");
    }

    @Test
    void aggregateRejectsWindowExceedingMaxDays() {
        var longTo = FROM.plusSeconds(400L * 24 * 3600); // 400 days > 366
        var filter = new WorkflowRunFilter(FROM, longTo, "p", null, null, null, null, null);
        assertThatThrownBy(() -> service.aggregate(filter))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("366");
    }

    @Test
    void aggregateMapsRollupAndDerivesCostPerOutcome() {
        when(runRepository.aggregateRuns(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(rollup(10, 4, 2, 3, new BigDecimal("100.0000"), new BigDecimal("40.0000")));
        when(phaseEventRepository.aggregatePhaseHotspots(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(hotspot("ci", 5, 2, 0, 1000L, 2000L, 3)));

        var result = service.aggregate(new WorkflowRunFilter(FROM, TO, "ground-control", null, null, null, null, null));

        assertThat(result.totalRuns()).isEqualTo(10);
        assertThat(result.mergedRuns()).isEqualTo(4);
        assertThat(result.activeRuns()).isEqualTo(3);
        // 40.0000 merged cost over 4 merged runs => 10.0000 per merged run.
        assertThat(result.costProxyPerMergedRun()).isEqualByComparingTo("10.0000");
        assertThat(result.phaseHotspots()).hasSize(1);
        assertThat(result.phaseHotspots().get(0).phase()).isEqualTo("ci");
        assertThat(result.phaseHotspots().get(0).failedCount()).isEqualTo(2);
        assertThat(result.phaseHotspots().get(0).maxCycleIndex()).isEqualTo(3);
    }

    @Test
    void aggregateCostPerMergedRunIsNullWhenNoMergedRuns() {
        when(runRepository.aggregateRuns(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(rollup(2, 0, 1, 1, BigDecimal.ZERO, BigDecimal.ZERO));
        when(phaseEventRepository.aggregatePhaseHotspots(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        var result = service.aggregate(new WorkflowRunFilter(FROM, TO, "p", null, null, null, null, null));

        assertThat(result.costProxyPerMergedRun()).isNull();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static RunCommandBuilder runCommand() {
        return new RunCommandBuilder();
    }

    /** A run already open on the canonical (project, issue, branch) identity, started at {@code at}. */
    private static WorkflowRun openRun(Instant at) {
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.LIVE_EMISSION);
        run.setIssueNumber(859);
        run.setBranch("859-feature");
        run.setStartedAt(at);
        return run;
    }

    /** A live-emission observation of the same run identity, varying only the timestamps and state. */
    private static RecordWorkflowRunCommand liveCommand(Instant startedAt, Instant endedAt, WorkflowRunState state) {
        return new RecordWorkflowRunCommand(
                "ground-control",
                null,
                859,
                null,
                "859-feature",
                "implement",
                "claude-code",
                null,
                startedAt,
                endedAt,
                state,
                WorkflowRunOutcome.NONE,
                TelemetryProvenance.LIVE_EMISSION,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static RecordPhaseEventCommand phaseEvent(
            UUID runId, String phase, PhaseEventType eventType, Integer cycleIndex, String sourceId) {
        return new RecordPhaseEventCommand(
                runId,
                "ground-control",
                phase,
                eventType,
                cycleIndex,
                FROM,
                25L,
                null,
                TelemetryProvenance.LIVE_EMISSION,
                sourceId);
    }

    /** Small fluent builder so each validation test varies exactly one field. */
    private static final class RunCommandBuilder {
        private String project = "ground-control";
        private String branch = "859-feature";
        private WorkflowRunState state = WorkflowRunState.RUNNING;
        private WorkflowRunOutcome outcome = WorkflowRunOutcome.NONE;
        private TelemetryProvenance provenance = TelemetryProvenance.ISSUE_THREAD;
        private BigDecimal cost = null;

        RunCommandBuilder withProject(String p) {
            this.project = p;
            return this;
        }

        RunCommandBuilder withBranch(String b) {
            this.branch = b;
            return this;
        }

        RunCommandBuilder withState(WorkflowRunState s) {
            this.state = s;
            return this;
        }

        RunCommandBuilder withOutcome(WorkflowRunOutcome o) {
            this.outcome = o;
            return this;
        }

        RunCommandBuilder withProvenance(TelemetryProvenance p) {
            this.provenance = p;
            return this;
        }

        RunCommandBuilder withCost(BigDecimal c) {
            this.cost = c;
            return this;
        }

        RecordWorkflowRunCommand build() {
            return new RecordWorkflowRunCommand(
                    project,
                    null,
                    859,
                    null,
                    branch,
                    "implement",
                    "claude-code",
                    Set.of("GC-O009"),
                    FROM,
                    null,
                    state,
                    outcome,
                    provenance,
                    null,
                    null,
                    null,
                    null,
                    cost,
                    null,
                    null);
        }
    }

    private static RunRollupRow rollup(
            long total, long merged, long closed, long active, BigDecimal totalCost, BigDecimal mergedCost) {
        return new RunRollupRow() {
            @Override
            public long getTotalRuns() {
                return total;
            }

            @Override
            public long getMergedRuns() {
                return merged;
            }

            @Override
            public long getClosedRuns() {
                return closed;
            }

            @Override
            public long getActiveRuns() {
                return active;
            }

            @Override
            public long getEscalatedRuns() {
                return 0;
            }

            @Override
            public long getAbandonedRuns() {
                return 0;
            }

            @Override
            public long getSupersededRuns() {
                return 0;
            }

            @Override
            public Double getCycleTimeP50Min() {
                return 12.0;
            }

            @Override
            public Double getCycleTimeP95Min() {
                return 30.0;
            }

            @Override
            public Double getCycleTimeP99Min() {
                return 45.0;
            }

            @Override
            public BigDecimal getTotalCostProxy() {
                return totalCost;
            }

            @Override
            public BigDecimal getMergedCostProxy() {
                return mergedCost;
            }

            @Override
            public BigDecimal getClosedCostProxy() {
                return BigDecimal.ZERO;
            }

            @Override
            public long getTotalModelInvocations() {
                return 0;
            }

            @Override
            public long getTotalWallClockMinutes() {
                return 0;
            }

            @Override
            public long getTotalTokenUsage() {
                return 0;
            }
        };
    }

    private static WorkflowPhaseEventRepository.PhaseHotspotRow hotspot(
            String phase, long count, long failed, long escalated, Long p50, Long p95, Integer maxCycle) {
        return new WorkflowPhaseEventRepository.PhaseHotspotRow() {
            @Override
            public String getPhase() {
                return phase;
            }

            @Override
            public long getEventCount() {
                return count;
            }

            @Override
            public long getFailedCount() {
                return failed;
            }

            @Override
            public long getEscalatedCount() {
                return escalated;
            }

            @Override
            public Long getP50Ms() {
                return p50;
            }

            @Override
            public Long getP95Ms() {
                return p95;
            }

            @Override
            public Integer getMaxCycleIndex() {
                return maxCycle;
            }
        };
    }
}

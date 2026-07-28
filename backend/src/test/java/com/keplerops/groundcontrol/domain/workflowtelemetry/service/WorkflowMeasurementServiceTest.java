package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingDisposition;
import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingSourceKind;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowGateFinding;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowGateFindingRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
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

/**
 * The ADR-090 measurement projection (issue #1355).
 *
 * <p>Each test pins a way the model is usually got wrong: a verdict inferred from a lifecycle
 * event, a redelivery double-counted, a severity invented for a source that has none, or a
 * terminal disposition silently overwritten.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowMeasurementServiceTest {

    @Mock
    private WorkflowGateFindingRepository gateFindingRepository;

    @Mock
    private WorkflowPhaseEventRepository phaseEventRepository;

    @InjectMocks
    private WorkflowMeasurementService service;

    private static WorkflowPhaseEvent event(UUID eventId) {
        var e = new WorkflowPhaseEvent(
                UUID.randomUUID(),
                "gc",
                "ci",
                PhaseEventType.COMPLETED,
                Instant.parse("2026-07-28T10:00:00Z"),
                1000L,
                TelemetryProvenance.LIVE_EMISSION);
        e.setStationId("ci");
        setField(e, "id", eventId);
        return e;
    }

    private static GateFindingCommand finding(String key) {
        return new GateFindingCommand(
                key, FindingSourceKind.DETECTOR, "policy", "adr-guard", null, null, FindingDisposition.OPEN);
    }

    @Test
    void findingsArePersistedWithTheAttemptsOwnStation() {
        var eventId = UUID.randomUUID();

        service.persistFindings(event(eventId), List.of(finding("k1")));

        var saved = ArgumentCaptor.forClass(WorkflowGateFinding.class);
        verify(gateFindingRepository).save(saved.capture());
        // The station is the attempt's, never the caller's: a batch cannot attribute its
        // findings to a station other than the one that produced them.
        assertThat(saved.getValue().getStationId()).isEqualTo("ci");
        assertThat(saved.getValue().getPhaseEventId()).isEqualTo(eventId);
        assertThat(saved.getValue().getDisposition()).isEqualTo(FindingDisposition.OPEN);
        // Policy expresses no severity; inventing one would fabricate a distribution.
        assertThat(saved.getValue().getSeverity()).isNull();
    }

    @Test
    void anEmptyBatchWritesNoRows() {
        // "The gate ran and found nothing" is a real observation, carried by the attempt's
        // own PASS verdict rather than by a placeholder finding row.
        service.persistFindings(event(UUID.randomUUID()), List.of());

        verify(gateFindingRepository, never()).save(any());
    }

    @Test
    void aDuplicateFindingKeyWithinOneBatchIsRejected() {
        assertThatThrownBy(() ->
                        service.persistFindings(event(UUID.randomUUID()), List.of(finding("same"), finding("same"))))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("duplicate findingKey");
    }

    @Test
    void anOversizedBatchIsRefused() {
        var many = java.util.stream.IntStream.range(0, 501)
                .mapToObj(i -> finding("k" + i))
                .toList();

        assertThatThrownBy(() -> service.persistFindings(event(UUID.randomUUID()), many))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void dispositionIsMonotonicAndIdempotent() {
        var f = new WorkflowGateFinding(
                UUID.randomUUID(), UUID.randomUUID(), "gc", "policy", FindingSourceKind.DETECTOR, "policy", "k1");
        var id = UUID.randomUUID();
        setField(f, "id", id);
        when(gateFindingRepository.findByIdAndProject(id, "gc")).thenReturn(Optional.of(f));
        when(gateFindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordFindingDisposition(id, "gc", FindingDisposition.FIXED);
        service.recordFindingDisposition(id, "gc", FindingDisposition.FIXED);

        assertThat(f.getDisposition()).isEqualTo(FindingDisposition.FIXED);
    }

    @Test
    void aConflictingTerminalDispositionIsRefusedRatherThanOverwritten() {
        var f = new WorkflowGateFinding(
                UUID.randomUUID(), UUID.randomUUID(), "gc", "policy", FindingSourceKind.DETECTOR, "policy", "k1");
        var id = UUID.randomUUID();
        setField(f, "id", id);
        f.applyDisposition(FindingDisposition.FIXED);
        when(gateFindingRepository.findByIdAndProject(id, "gc")).thenReturn(Optional.of(f));

        // Two sources disagreeing about whether something was fixed is a fact worth
        // surfacing, not one to settle by write order.
        assertThatThrownBy(() -> service.recordFindingDisposition(id, "gc", FindingDisposition.WONTFIX))
                .isInstanceOf(ConflictException.class);
        assertThat(f.getDisposition()).isEqualTo(FindingDisposition.FIXED);
    }

    @Test
    void recordingOpenAsADispositionIsRejected() {
        // This records a decision; "still open" is the absence of one.
        assertThatThrownBy(() -> service.recordFindingDisposition(UUID.randomUUID(), "gc", FindingDisposition.OPEN))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void onlyEvaluableAttemptsReachTheYieldQuery() {
        when(phaseEventRepository.findEvaluableAttempts(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.aggregateStationYield("gc", null, null);

        var results = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(phaseEventRepository).findEvaluableAttempts(any(), results.capture(), any(), any());
        // Skipped, cancelled, not-evaluable and unobserved attempts stay measurable coverage
        // but must never enter a yield denominator, or an unmeasured gate reads as a failing one.
        assertThat(results.getValue()).containsExactlyInAnyOrder(StationResult.PASS, StationResult.FAIL);
    }

    @Test
    void anUnboundedWindowIsRefused() {
        assertThatThrownBy(() -> service.aggregateStationYield(
                        "gc", Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")))
                .isInstanceOf(DomainValidationException.class);
    }
}

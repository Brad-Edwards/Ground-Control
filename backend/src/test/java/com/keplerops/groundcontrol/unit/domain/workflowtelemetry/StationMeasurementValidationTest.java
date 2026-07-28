package com.keplerops.groundcontrol.unit.domain.workflowtelemetry;

import static com.keplerops.groundcontrol.unit.domain.workflowtelemetry.WorkflowTelemetryFixtures.FROM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingSourceKind;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.GateFindingCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordPhaseEventCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.StationCatalog;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowMeasurementService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The measurement axes must not be able to contradict each other (issue #1355, ADR-090).
 *
 * <p>Runs against the real catalogue the build copies from {@code contracts/measurement/}, not a
 * mock: the point of the check is that the backend and the published contract agree on what a
 * station is, and a stubbed catalogue would assert only that the code calls itself.
 *
 * <p>These combinations are permanent once stored. Nothing downstream re-derives one axis from
 * another — that separation is the whole change — so a contradictory row is indistinguishable from
 * a genuine observation for the rest of its life.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StationMeasurementValidationTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowPhaseEventRepository phaseEventRepository;

    @Mock
    private WorkflowMeasurementService measurementService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private WorkflowTelemetryService service;
    private UUID runId;

    @BeforeEach
    void setUp() {
        service = new WorkflowTelemetryService(
                runRepository, phaseEventRepository, measurementService, new StationCatalog(), eventPublisher);
        runId = UUID.randomUUID();
        var run = new WorkflowRun("gc", "implement", TelemetryProvenance.ISSUE_THREAD);
        when(runRepository.findByIdAndProjectForUpdate(runId, "gc")).thenReturn(Optional.of(run));
        when(phaseEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private RecordPhaseEventCommand command(
            String stationId, StationResult result, PhaseEventType type, List<GateFindingCommand> findings) {
        return new RecordPhaseEventCommand(
                runId,
                "gc",
                stationId == null ? "planning" : stationId,
                type,
                0,
                FROM,
                1000L,
                null,
                TelemetryProvenance.ISSUE_THREAD,
                null,
                stationId,
                result,
                findings,
                null);
    }

    private static List<GateFindingCommand> oneFinding() {
        return List.of(new GateFindingCommand("k1", FindingSourceKind.DETECTOR, "policy", null, null, null, null));
    }

    @Test
    void aCatalogueStationRecordsItsVerdict() {
        var event =
                service.recordPhaseEvent(command("policy", StationResult.FAIL, PhaseEventType.COMPLETED, oneFinding()));

        assertThat(event.getStationId()).isEqualTo("policy");
        assertThat(event.getStationResult()).isEqualTo(StationResult.FAIL);
    }

    @Test
    void aStationIdOutsideTheCatalogueIsRefused() {
        // A typo does not fail on its own. It opens a phantom station holding one attempt and
        // silently removes that attempt from the real station's denominator, and no later query can
        // tell the phantom from a station that genuinely ran once.
        assertThatThrownBy(() ->
                        service.recordPhaseEvent(command("polcy", StationResult.PASS, PhaseEventType.COMPLETED, null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("polcy");
        verify(phaseEventRepository, never()).save(any());
    }

    @Test
    void aLifecycleMarkerCannotCarryAVerdict() {
        // A marker records that something happened. It inspects nothing, so a pass or fail on one
        // is the axis conflation this change exists to remove.
        assertThatThrownBy(() ->
                        service.recordPhaseEvent(command("plan", StationResult.PASS, PhaseEventType.COMPLETED, null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("lifecycle marker");
    }

    @Test
    void aLifecycleMarkerCannotCarryFindings() {
        assertThatThrownBy(
                        () -> service.recordPhaseEvent(command("plan", null, PhaseEventType.COMPLETED, oneFinding())))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("lifecycle marker");
    }

    @Test
    void aMarkerWithNoMeasurementIsRecorded() {
        var event = service.recordPhaseEvent(command("plan", null, PhaseEventType.COMPLETED, null));

        assertThat(event.getStationId()).isEqualTo("plan");
        assertThat(event.getStationResult()).isEqualTo(StationResult.UNOBSERVED);
    }

    @Test
    void aStartedAttemptHasNotFinishedInspectingAndCarriesNoVerdict() {
        assertThatThrownBy(
                        () -> service.recordPhaseEvent(command("ci", StationResult.PASS, PhaseEventType.STARTED, null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("STARTED");
        verify(phaseEventRepository, never()).save(any());
    }

    @Test
    void aStartedAttemptCarriesNoFindings() {
        assertThatThrownBy(() -> service.recordPhaseEvent(command("ci", null, PhaseEventType.STARTED, oneFinding())))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("STARTED");
    }

    @Test
    void aStartedAttemptWithoutMeasurementOpensNormally() {
        var event = service.recordPhaseEvent(command("ci", null, PhaseEventType.STARTED, null));

        assertThat(event.getStationId()).isEqualTo("ci");
        assertThat(event.getStationResult()).isEqualTo(StationResult.UNOBSERVED);
    }

    @Test
    void aStageWithNoStationCannotReportAVerdictOrFindings() {
        assertThatThrownBy(() ->
                        service.recordPhaseEvent(command(null, StationResult.PASS, PhaseEventType.COMPLETED, null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("stationId");
        assertThatThrownBy(() -> service.recordPhaseEvent(command(null, null, PhaseEventType.COMPLETED, oneFinding())))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("stationId");
    }

    @Test
    void unobservedIsNotAVerdictAndPassesEveryCombination() {
        // UNOBSERVED states that nothing was measured, which is exactly what a STARTED event and a
        // marker both report. Treating it as a verdict would make honest emissions unrecordable.
        assertThat(service.recordPhaseEvent(command("ci", StationResult.UNOBSERVED, PhaseEventType.STARTED, null))
                        .getStationResult())
                .isEqualTo(StationResult.UNOBSERVED);
        assertThat(service.recordPhaseEvent(command("plan", StationResult.UNOBSERVED, PhaseEventType.COMPLETED, null))
                        .getStationResult())
                .isEqualTo(StationResult.UNOBSERVED);
    }

    @Test
    void theCatalogueMatchesTheOneTheContractPublishes() {
        // The validator is only as good as its agreement with the contract. A catalogue that failed
        // to load would accept nothing; one that loaded a stale copy would accept the wrong set.
        var catalog = new StationCatalog();

        assertThat(catalog.stationIds()).contains("spotbugs", "policy", "vale", "ci", "sonarcloud");
        assertThat(catalog.isMarker("plan")).isTrue();
        assertThat(catalog.isStation("plan")).isFalse();
    }
}

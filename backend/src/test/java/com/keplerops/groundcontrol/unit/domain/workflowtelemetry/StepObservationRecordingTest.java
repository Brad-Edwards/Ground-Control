package com.keplerops.groundcontrol.unit.domain.workflowtelemetry;

import static com.keplerops.groundcontrol.unit.domain.workflowtelemetry.WorkflowTelemetryFixtures.FROM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.CapabilityTier;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventEmitter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordPhaseEventCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.StationCatalog;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowMeasurementService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
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
 * A durable ADR-036 step observation is a phase-event row distinguished by its emitter (ADR-090
 * amendment, issue #1354). It carries operation-outcome economics, never a station verdict, and the
 * backend — not the emitter — resolves the catalogue station from the stage in {@code phase}.
 *
 * <p>Runs against the real catalogue the build copies from {@code contracts/measurement/}, so the
 * stage→station resolution is proven against the published contract rather than a stub.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StepObservationRecordingTest {

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
        var run = new WorkflowRun("gc", "implement", TelemetryProvenance.LIVE_EMISSION);
        when(runRepository.findByIdAndProjectForUpdate(runId, "gc")).thenReturn(Optional.of(run));
        when(phaseEventRepository.findByRunIdAndSourceId(any(), any())).thenReturn(Optional.empty());
        when(phaseEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** A step command with the ADR-036 facts, overriding only the fields a test varies. */
    private RecordPhaseEventCommand step(
            String stage, CapabilityTier tier, String stationId, StationResult stationResult, String sourceId) {
        return new RecordPhaseEventCommand(
                runId,
                "gc",
                stage,
                PhaseEventType.COMPLETED,
                0,
                FROM,
                1200L,
                "ok",
                TelemetryProvenance.LIVE_EMISSION,
                sourceId,
                stationId,
                stationResult,
                null,
                null,
                PhaseEventEmitter.ADR036_STEP_JSONL,
                "gc.measurement/v1",
                "Step 6",
                tier,
                "claude-haiku-4-5",
                "claude-haiku-4-5",
                Boolean.TRUE,
                8421L,
                612L);
    }

    @Test
    void stationStageResolvesTheCanonicalStationAndStaysUnobserved() {
        var saved = service.recordPhaseEvent(
                step("completion_gate", CapabilityTier.LOW, null, null, "adr036_step:completion_gate:0"));

        assertThat(saved.getEmitter()).isEqualTo(PhaseEventEmitter.ADR036_STEP_JSONL);
        // The backend resolved the station from the stage; the emitter sent none.
        assertThat(saved.getStationId()).isEqualTo("completion_gate");
        // Operation outcome only — a routed step running is not a gate passing.
        assertThat(saved.getStationResult()).isEqualTo(StationResult.UNOBSERVED);
        assertThat(saved.getTier()).isEqualTo(CapabilityTier.LOW);
        assertThat(saved.getModel()).isEqualTo("claude-haiku-4-5");
        assertThat(saved.getMeasurementVersion()).isEqualTo("gc.measurement/v1");
        assertThat(saved.getStepAlias()).isEqualTo("Step 6");
        assertThat(saved.getInputTokens()).isEqualTo(8421L);
        assertThat(saved.getOutputTokens()).isEqualTo(612L);
        // Operation outcome lives on its own field, distinct from the station-result axis.
        assertThat(saved.getOutcome()).isEqualTo("ok");
    }

    @Test
    void aStageAliasOfADifferentlyNamedStationResolvesToThatStation() {
        var saved = service.recordPhaseEvent(
                step("ci_monitor", CapabilityTier.LOW, null, null, "adr036_step:ci_monitor:0"));

        assertThat(saved.getStationId()).isEqualTo("ci");
        assertThat(saved.getStationResult()).isEqualTo(StationResult.UNOBSERVED);
    }

    @Test
    void aNonStationStageResolvesToNoStationButIsStillRecorded() {
        var saved = service.recordPhaseEvent(
                step("implementation", CapabilityTier.MEDIUM, null, null, "adr036_step:implementation:0"));

        assertThat(saved.getStationId()).isNull();
        assertThat(saved.getEmitter()).isEqualTo(PhaseEventEmitter.ADR036_STEP_JSONL);
        assertThat(saved.getTier()).isEqualTo(CapabilityTier.MEDIUM);
    }

    @Test
    void aMarkerStageResolvesToNoStation() {
        var saved =
                service.recordPhaseEvent(step("planning", CapabilityTier.HIGH, null, null, "adr036_step:planning:0"));

        assertThat(saved.getStationId()).isNull();
        assertThat(saved.getStationResult()).isEqualTo(StationResult.UNOBSERVED);
    }

    @Test
    void anUndeclaredStageIsRejectedRatherThanOpeningAPhantomPhase() {
        var command = step("totally_made_up_stage", CapabilityTier.LOW, null, null, "adr036_step:x:0");
        assertThatThrownBy(() -> service.recordPhaseEvent(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("unknown ADR-036 stage");
    }

    @Test
    void aStepObservationRequiresACapabilityTier() {
        var command = step("completion_gate", null, null, null, "adr036_step:completion_gate:0");
        assertThatThrownBy(() -> service.recordPhaseEvent(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("capability tier");
    }

    @Test
    void aStepObservationCannotStateAStationVerdict() {
        // The whole point of the emitter axis: a routed step succeeding is not a gate passing.
        var command = step("completion_gate", CapabilityTier.LOW, null, StationResult.PASS, "adr036_step:cg:0");
        assertThatThrownBy(() -> service.recordPhaseEvent(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("station result");
    }

    @Test
    void aStepObservationMustNotSendAStationIdBecauseTheBackendResolvesIt() {
        var command = step("completion_gate", CapabilityTier.LOW, "completion_gate", null, "adr036_step:cg:0");
        assertThatThrownBy(() -> service.recordPhaseEvent(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("station id");
    }

    @Test
    void aStepObservationHonoursItsNamespacedSourceIdSoItNeverCollidesWithAStationAttempt() {
        var saved = service.recordPhaseEvent(
                step("completion_gate", CapabilityTier.LOW, null, null, "adr036_step:completion_gate:0"));

        // Namespaced to the ADR-036 emitter, so it can never dedup against a live station attempt
        // whose identity is completion_gate:COMPLETED:0.
        assertThat(saved.getSourceId()).isEqualTo("adr036_step:completion_gate:0");
    }

    @Test
    void aStepObservationRejectsAReservedMarkerInItsFields() {
        // The class Javadoc's promise — forged-marker text can never round-trip through telemetry —
        // must hold for the new step fields, not only phase/sourceId.
        var command = full(
                PhaseEventEmitter.ADR036_STEP_JSONL,
                CapabilityTier.LOW,
                "gc.measurement/v1",
                "<!-- gc:phase -->",
                "claude-haiku-4-5",
                Boolean.TRUE,
                8421L,
                612L);
        assertThatThrownBy(() -> service.recordPhaseEvent(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void aStepObservationRejectsNegativeTokenCounts() {
        var negativeInput = full(
                PhaseEventEmitter.ADR036_STEP_JSONL,
                CapabilityTier.LOW,
                "gc.measurement/v1",
                "claude-haiku-4-5",
                "claude-haiku-4-5",
                Boolean.TRUE,
                -1L,
                612L);
        assertThatThrownBy(() -> service.recordPhaseEvent(negativeInput))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("inputTokens must not be negative");
        var negativeOutput = full(
                PhaseEventEmitter.ADR036_STEP_JSONL,
                CapabilityTier.LOW,
                "gc.measurement/v1",
                "claude-haiku-4-5",
                "claude-haiku-4-5",
                Boolean.TRUE,
                8421L,
                -1L);
        assertThatThrownBy(() -> service.recordPhaseEvent(negativeOutput))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("outputTokens must not be negative");
    }

    @Test
    void aLifecycleEventCannotCarryStepObservationFields() {
        // The emitter is only a reliable discriminator if an ADR-061 row never holds step economics.
        var command = full(
                PhaseEventEmitter.ADR061_WORKFLOW_TELEMETRY, CapabilityTier.LOW, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.recordPhaseEvent(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("step-observation fields");
    }

    @Test
    void aStepObservationRequiresItsCompleteClosedFieldSet() {
        // measurement version, reported model, expected model, and the consistency flag are all part
        // of the closed contract — an ADR-036 row that omits any of them is not a durable record.
        var noMeasurementVersion = full(
                PhaseEventEmitter.ADR036_STEP_JSONL,
                CapabilityTier.LOW,
                null,
                "claude-haiku-4-5",
                "claude-haiku-4-5",
                Boolean.TRUE,
                8421L,
                612L);
        assertThatThrownBy(() -> service.recordPhaseEvent(noMeasurementVersion))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("measurementVersion");
        var noExpectedModel = full(
                PhaseEventEmitter.ADR036_STEP_JSONL,
                CapabilityTier.LOW,
                "gc.measurement/v1",
                "claude-haiku-4-5",
                null,
                Boolean.TRUE,
                8421L,
                612L);
        assertThatThrownBy(() -> service.recordPhaseEvent(noExpectedModel))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("expectedModel");
        var noConsistencyFlag = full(
                PhaseEventEmitter.ADR036_STEP_JSONL,
                CapabilityTier.LOW,
                "gc.measurement/v1",
                "claude-haiku-4-5",
                "claude-haiku-4-5",
                null,
                8421L,
                612L);
        assertThatThrownBy(() -> service.recordPhaseEvent(noConsistencyFlag))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("consistency flag");
    }

    /** A full step-observation command, so a test can vary exactly the field it exercises. */
    private RecordPhaseEventCommand full(
            PhaseEventEmitter emitter,
            CapabilityTier tier,
            String measurementVersion,
            String model,
            String expectedModel,
            Boolean modelMatchesExpected,
            Long inputTokens,
            Long outputTokens) {
        return new RecordPhaseEventCommand(
                runId,
                "gc",
                "completion_gate",
                PhaseEventType.COMPLETED,
                0,
                FROM,
                1200L,
                "ok",
                TelemetryProvenance.LIVE_EMISSION,
                "adr036_step:completion_gate:0",
                null,
                null,
                null,
                null,
                emitter,
                measurementVersion,
                "Step 6",
                tier,
                model,
                expectedModel,
                modelMatchesExpected,
                inputTokens,
                outputTokens);
    }
}

package com.keplerops.groundcontrol.unit.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.api.workflowtelemetry.PhaseEventResponse;
import com.keplerops.groundcontrol.domain.workflowtelemetry.CapabilityTier;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventEmitter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link PhaseEventResponse#from} maps a {@link WorkflowPhaseEvent} onto the JSON the REST endpoint
 * and the SSE stream both serialize. Its constructor takes several adjacent same-typed parameters
 * built positionally from getter calls ({@code String model, String expectedModel}; {@code Long
 * inputTokens, Long outputTokens}), so a transposition would compile and pass every controller test
 * yet silently corrupt the queryable per-step record this feature exists to deliver (issue #1354).
 * Every field here is given a distinct value so a swap fails.
 */
class PhaseEventResponseTest {

    @Test
    void fromMapsEveryFieldOntoItsCorrespondinglyNamedResponseField() {
        var id = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var occurredAt = Instant.parse("2026-07-29T12:00:00Z");
        var event = new WorkflowPhaseEvent(
                runId,
                "the-project",
                "completion_gate",
                PhaseEventType.COMPLETED,
                occurredAt,
                1234L,
                TelemetryProvenance.LIVE_EMISSION);
        event.setCycleIndex(3);
        event.setOutcome("ok");
        event.setSourceId("adr036_step:completion_gate:3");
        event.setStationId("completion_gate");
        event.setStationResult(StationResult.UNOBSERVED);
        event.setFindingsDropped(7);
        event.setEmitter(PhaseEventEmitter.ADR036_STEP_JSONL);
        event.setMeasurementVersion("gc.measurement/v1");
        event.setStepAlias("Step 6");
        event.setTier(CapabilityTier.MEDIUM);
        // Deliberately distinct so a model/expectedModel or inputTokens/outputTokens swap is caught.
        event.setModel("reported-model");
        event.setExpectedModel("expected-model");
        event.setModelMatchesExpected(false);
        event.setInputTokens(111L);
        event.setOutputTokens(222L);
        setId(event, id);

        var response = PhaseEventResponse.from(event);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.runId()).isEqualTo(runId);
        assertThat(response.project()).isEqualTo("the-project");
        assertThat(response.phase()).isEqualTo("completion_gate");
        assertThat(response.eventType()).isEqualTo(PhaseEventType.COMPLETED);
        assertThat(response.cycleIndex()).isEqualTo(3);
        assertThat(response.occurredAt()).isEqualTo(occurredAt);
        assertThat(response.durationMs()).isEqualTo(1234L);
        assertThat(response.outcome()).isEqualTo("ok");
        assertThat(response.provenance()).isEqualTo(TelemetryProvenance.LIVE_EMISSION);
        assertThat(response.sourceId()).isEqualTo("adr036_step:completion_gate:3");
        assertThat(response.stationId()).isEqualTo("completion_gate");
        assertThat(response.stationResult()).isEqualTo(StationResult.UNOBSERVED);
        assertThat(response.findingsDropped()).isEqualTo(7);
        assertThat(response.emitter()).isEqualTo(PhaseEventEmitter.ADR036_STEP_JSONL);
        assertThat(response.measurementVersion()).isEqualTo("gc.measurement/v1");
        assertThat(response.stepAlias()).isEqualTo("Step 6");
        assertThat(response.tier()).isEqualTo(CapabilityTier.MEDIUM);
        assertThat(response.model()).isEqualTo("reported-model");
        assertThat(response.expectedModel()).isEqualTo("expected-model");
        assertThat(response.modelMatchesExpected()).isFalse();
        assertThat(response.inputTokens()).isEqualTo(111L);
        assertThat(response.outputTokens()).isEqualTo(222L);
    }

    /** The id is DB-generated; set it reflectively so the mapping of a persisted row is under test. */
    private static void setId(WorkflowPhaseEvent event, UUID id) {
        try {
            var field = WorkflowPhaseEvent.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(event, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

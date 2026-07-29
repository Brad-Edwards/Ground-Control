package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventEmitter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Input validation for the workflow-telemetry write and read paths (issue #859).
 *
 * <p>Its own type so the rules stay reviewable in one place: every caller-supplied string is
 * checked for the reserved {@code <!-- gc:} marker sequence, so forged-marker text can never
 * round-trip into telemetry and back out onto an issue thread as if the server had written it.
 */
final class WorkflowTelemetryValidation {

    /** Reserved sequence that opens every {@code gc:} workflow marker; never allowed in stored fields. */
    private static final String RESERVED_MARKER = "<!-- gc:";

    /** Maximum allowed aggregation window in days. */
    static final int MAX_WINDOW_DAYS = 366;

    private WorkflowTelemetryValidation() {}

    /**
     * Chronology invariant checked before any field is applied: a run cannot end before it started.
     * The start time may come from the stored run or from this observation, whichever is earlier.
     */
    static void validateChronology(WorkflowRun existing, RecordWorkflowRunCommand command) {
        if (command.endedAt() == null) {
            return;
        }
        Instant start = WorkflowRunCommandMapper.earliest(
                existing == null ? null : existing.getStartedAt(), command.startedAt());
        if (start != null && command.endedAt().isBefore(start)) {
            throw new DomainValidationException("endedAt must not be before startedAt");
        }
    }

    static void validateEconomics(
            Integer modelInvocationCount, Integer wallClockMinutes, BigDecimal costProxy, Long tokenUsage) {
        if (modelInvocationCount != null && modelInvocationCount < 0) {
            throw new DomainValidationException("modelInvocationCount must not be negative");
        }
        if (wallClockMinutes != null && wallClockMinutes < 0) {
            throw new DomainValidationException("wallClockMinutes must not be negative");
        }
        if (costProxy != null && costProxy.signum() < 0) {
            throw new DomainValidationException("costProxy must not be negative");
        }
        if (tokenUsage != null && tokenUsage < 0) {
            throw new DomainValidationException("tokenUsage must not be negative");
        }
    }

    static void validateWindow(Instant from, Instant to) {
        if (from == null) {
            throw new DomainValidationException("from must not be null");
        }
        if (to == null) {
            throw new DomainValidationException("to must not be null");
        }
        if (!from.isBefore(to)) {
            throw new DomainValidationException("from must be before to");
        }
        long days = Duration.between(from, to).toDays();
        if (days > MAX_WINDOW_DAYS) {
            throw new DomainValidationException(
                    "time window must not exceed " + MAX_WINDOW_DAYS + " days (requested " + days + " days)");
        }
    }

    /**
     * The measurement facts must agree with the lifecycle event carrying them (issue #1355).
     *
     * <p>Three axes travel on one row, and the whole point of this change is that they stay
     * disjoint. Nothing downstream re-derives one from another, which is what makes an
     * unvalidated combination permanent: a STARTED event carrying {@code PASS} is stored as a
     * finished, passing attempt at a station that had not finished, and no later query can tell it
     * from a real one.
     *
     * @param catalog the authoritative station catalogue
     * @param stationId the emitted station id, or null for a non-station stage
     * @param stationResult the emitted verdict, or null when the emitter states none
     * @param eventType the lifecycle event the facts arrived on
     * @param findings the batch the attempt observed, possibly null or empty
     */
    static void validateMeasurement(
            StationCatalog catalog,
            String stationId,
            StationResult stationResult,
            PhaseEventType eventType,
            List<GateFindingCommand> findings,
            Integer findingsDropped) {
        // UNOBSERVED is not a verdict. It states that nothing was measured, which is exactly what a
        // marker and an opening attempt both honestly report, so it must pass every rule below.
        var hasVerdict = stationResult != null && stationResult != StationResult.UNOBSERVED;
        var hasFindings = findings != null && !findings.isEmpty();

        validateDroppedCount(findingsDropped, hasFindings);
        if (stationId == null) {
            validateStagelessMeasurement(hasVerdict, hasFindings);
            return;
        }
        validateStationMeasurement(catalog, stationId, eventType, hasVerdict, hasFindings);
    }

    /** A truncation count only makes sense alongside the batch it truncated. */
    private static void validateDroppedCount(Integer findingsDropped, boolean hasFindings) {
        var dropped = findingsDropped == null ? 0 : findingsDropped;
        if (dropped < 0) {
            throw new DomainValidationException("findingsDropped must not be negative");
        }
        if (dropped > 0 && !hasFindings) {
            // The cap discards the overflow past a full batch. A drop with nothing delivered means
            // the emitter lost the batch rather than truncated it, and recording it as a truncation
            // would describe a cap that never engaged.
            throw new DomainValidationException(
                    "findingsDropped requires a delivered findings batch to have truncated");
        }
    }

    /** A stage with no station inspected nothing, so it has nothing to report. */
    private static void validateStagelessMeasurement(boolean hasVerdict, boolean hasFindings) {
        if (hasVerdict) {
            throw new DomainValidationException(
                    "stationResult requires a stationId; a stage with no station has nothing to report");
        }
        if (hasFindings) {
            throw new DomainValidationException(
                    "findings require a stationId; a stage with no station inspected nothing");
        }
    }

    /** The station id must be one the catalogue defines, and its facts must fit the lifecycle event. */
    private static void validateStationMeasurement(
            StationCatalog catalog,
            String stationId,
            PhaseEventType eventType,
            boolean hasVerdict,
            boolean hasFindings) {
        if (catalog.isMarker(stationId)) {
            // A marker records that something happened, not that something was inspected. Letting
            // one carry a verdict is the axis conflation this issue exists to remove.
            if (hasVerdict || hasFindings) {
                throw new DomainValidationException(stationId
                        + " is a lifecycle marker: it inspects nothing and can carry no station result or findings");
            }
            return;
        }
        if (!catalog.isStation(stationId)) {
            // A typo does not fail loudly on its own: it opens a phantom station holding one
            // attempt, and silently removes that attempt from the real station's denominator.
            throw new DomainValidationException(
                    "unknown stationId '" + stationId + "'; the catalogue defines " + catalog.stationIds());
        }
        if (eventType == PhaseEventType.STARTED && (hasVerdict || hasFindings)) {
            throw new DomainValidationException(
                    "a STARTED attempt has not finished inspecting and cannot carry a station result or findings");
        }
    }

    /** The emitter plus the station id and station result the write should persist (issue #1354). */
    record StepEmission(PhaseEventEmitter emitter, String stationId, StationResult stationResult) {
        boolean isStepObservation() {
            return emitter == PhaseEventEmitter.ADR036_STEP_JSONL;
        }
    }

    /**
     * Resolve the emitter, the catalogue station, and the station result for a phase-event write, and
     * enforce the closed emitter contract (ADR-090 amendment, issue #1354).
     *
     * <p>The {@code emitter} is only a trustworthy analytics discriminator if the two row shapes stay
     * disjoint. A lifecycle/station emission passes its own station id and verdict through but must
     * carry <em>no</em> ADR-036 step economics. A durable ADR-036 step observation is the mirror
     * image: the backend resolves the station from the stage in {@code phase} (the emitter sends no
     * station id and no verdict, and the row is always {@link StationResult#UNOBSERVED} because a
     * routed step running is not a gate passing), and it must supply its complete closed field set.
     */
    static StepEmission resolveStepEmission(StationCatalog catalog, RecordPhaseEventCommand command) {
        rejectReservedMarkers(
                command.measurementVersion(), command.stepAlias(), command.model(), command.expectedModel());
        if (command.inputTokens() != null && command.inputTokens() < 0) {
            throw new DomainValidationException("inputTokens must not be negative");
        }
        if (command.outputTokens() != null && command.outputTokens() < 0) {
            throw new DomainValidationException("outputTokens must not be negative");
        }
        var emitter = command.emitter() == null ? PhaseEventEmitter.ADR061_WORKFLOW_TELEMETRY : command.emitter();
        if (emitter != PhaseEventEmitter.ADR036_STEP_JSONL) {
            rejectStepObservationFields(command);
            return new StepEmission(emitter, command.stationId(), command.stationResult());
        }
        requireStepObservationContract(catalog, command);
        return new StepEmission(
                emitter, catalog.resolveStationForStage(command.phase()).orElse(null), StationResult.UNOBSERVED);
    }

    /**
     * A lifecycle/station event must not carry ADR-036 step economics: allowing it would make the
     * emitter an unreliable discriminator, since an ADR-061 row could then hold tier/model data a
     * per-step query would read as a step observation.
     */
    private static void rejectStepObservationFields(RecordPhaseEventCommand command) {
        if (command.tier() != null
                || command.model() != null
                || command.expectedModel() != null
                || command.measurementVersion() != null
                || command.stepAlias() != null
                || command.modelMatchesExpected() != null
                || command.inputTokens() != null
                || command.outputTokens() != null) {
            throw new DomainValidationException(
                    "a lifecycle/station event must not carry ADR-036 step-observation fields; the emitter is the discriminator");
        }
    }

    /** A durable ADR-036 step observation must supply its complete closed field set (issue #1354). */
    private static void requireStepObservationContract(StationCatalog catalog, RecordPhaseEventCommand command) {
        if (command.tier() == null) {
            throw new DomainValidationException("a durable step observation requires a capability tier");
        }
        requireText(command.measurementVersion(), "measurementVersion");
        requireText(command.model(), "model");
        requireText(command.expectedModel(), "expectedModel");
        if (command.modelMatchesExpected() == null) {
            throw new DomainValidationException(
                    "a step observation requires the model/expected-model consistency flag");
        }
        if (command.stationResult() != null && command.stationResult() != StationResult.UNOBSERVED) {
            throw new DomainValidationException(
                    "a step observation carries operation outcome only and cannot state a station result");
        }
        if (command.stationId() != null) {
            throw new DomainValidationException(
                    "a step observation must not send a station id; the backend resolves it from the stage");
        }
        if (!catalog.isKnownStage(command.phase())) {
            throw new DomainValidationException(
                    "unknown ADR-036 stage '" + command.phase() + "'; it is not declared in the station catalogue");
        }
    }

    /**
     * A durable ADR-036 step observation has a namespaced identity and immutable measurement facts, so
     * re-recording the same {@code (run, sourceId)} with different facts is a conflict, not the silent
     * overwrite lifecycle rows use for live-vs-backfill convergence (ADR-090 amendment, issue #1354).
     */
    static void assertReplayFactsMatch(
            WorkflowPhaseEvent stored, RecordPhaseEventCommand command, StepEmission emission) {
        if (!emission.isStepObservation()) {
            return;
        }
        if (stored.getEmitter() != PhaseEventEmitter.ADR036_STEP_JSONL
                || stored.getTier() != command.tier()
                || !Objects.equals(stored.getModel(), command.model())
                || !Objects.equals(stored.getMeasurementVersion(), command.measurementVersion())
                || !Objects.equals(stored.getDurationMs(), command.durationMs())
                || !Objects.equals(stored.getOutcome(), command.outcome())
                || !Objects.equals(stored.getInputTokens(), command.inputTokens())
                || !Objects.equals(stored.getOutputTokens(), command.outputTokens())) {
            throw new ConflictException("step observation " + stored.getSourceId()
                    + " was already recorded with different measurement facts");
        }
    }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
    }

    static void rejectReservedMarkers(String... values) {
        for (String value : values) {
            if (value != null && value.contains(RESERVED_MARKER)) {
                throw new DomainValidationException(
                        "field must not contain a reserved '" + RESERVED_MARKER + "' marker");
            }
        }
    }
}

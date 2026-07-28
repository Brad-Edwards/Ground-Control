package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
        var hasVerdict = stationResult != null && stationResult != StationResult.UNOBSERVED;
        var hasFindings = findings != null && !findings.isEmpty();
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
        if (stationId == null) {
            if (hasVerdict) {
                throw new DomainValidationException(
                        "stationResult requires a stationId; a stage with no station has nothing to report");
            }
            if (hasFindings) {
                throw new DomainValidationException(
                        "findings require a stationId; a stage with no station inspected nothing");
            }
            return;
        }
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

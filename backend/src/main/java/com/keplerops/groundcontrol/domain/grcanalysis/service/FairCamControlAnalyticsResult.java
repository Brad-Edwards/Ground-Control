package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.FairCamControlDomain;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Structured result of a FAIR-CAM control analytics view per GC-I017.
 *
 * <p>Carries the GC-L007 preflight result-contract fields and explicit FAIR-CAM
 * domain attribution per control item ({@code LOSS_EVENT_CONTROL},
 * {@code VARIANCE_MANAGEMENT_CONTROL}, {@code DECISION_SUPPORT_CONTROL}).
 *
 * <p>FAIR-CAM analytics never collapse capability / coverage / operational
 * performance into a single effectiveness score; the three dimensions are
 * reported independently and the (separately maintained)
 * {@link ControlEffectivenessRating} is reported beside them rather than
 * absorbing them.
 */
public record FairCamControlAnalyticsResult(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        String scale,
        String units,
        List<ControlAnalyticsItem> controls,
        Counts counts,
        List<String> limitations) {

    public record ControlAnalyticsItem(
            UUID assessmentId,
            UUID controlId,
            String controlUid,
            String controlTitle,
            FairCamControlDomain fairCamControlDomain,
            LocalDate assessedAt,
            String assessor,
            ControlEffectivenessRating designEffectiveness,
            ControlEffectivenessRating operatingEffectiveness,
            Dimensions dimensions,
            List<String> supportingTestIds,
            List<String> limitations) {}

    /**
     * The three FAIR-CAM measurement dimensions for an analyzed control. Each
     * is a fraction in {@code [0.0, 1.0]} with explicit {@code scale} /
     * {@code units} on the envelope so downstream consumers cannot silently
     * collapse them into one number.
     */
    public record Dimensions(
            DimensionMeasurement capability,
            DimensionMeasurement coverage,
            DimensionMeasurement operationalPerformance) {}

    public record DimensionMeasurement(double value, String scale, String units, String derivation) {}

    public record Counts(int total, Map<String, Integer> byDomain, int withLimitations) {}
}

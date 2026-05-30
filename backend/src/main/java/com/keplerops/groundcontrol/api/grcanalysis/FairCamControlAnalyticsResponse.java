package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.FairCamControlDomain;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlAnalyticsResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API DTO for the FAIR-CAM control analytics endpoint per GC-I017. Decouples
 * the public JSON contract from the domain service record so future domain
 * refactors do not silently change the wire shape.
 */
public record FairCamControlAnalyticsResponse(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        String scale,
        String units,
        List<ControlAnalyticsItem> controls,
        Counts counts,
        List<String> limitations) {

    public static FairCamControlAnalyticsResponse from(FairCamControlAnalyticsResult result) {
        return new FairCamControlAnalyticsResponse(
                result.analysisKind(),
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                result.scale(),
                result.units(),
                result.controls().stream().map(ControlAnalyticsItem::from).toList(),
                Counts.from(result.counts()),
                List.copyOf(result.limitations()));
    }

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
            List<String> limitations) {

        public static ControlAnalyticsItem from(FairCamControlAnalyticsResult.ControlAnalyticsItem item) {
            return new ControlAnalyticsItem(
                    item.assessmentId(),
                    item.controlId(),
                    item.controlUid(),
                    item.controlTitle(),
                    item.fairCamControlDomain(),
                    item.assessedAt(),
                    item.assessor(),
                    item.designEffectiveness(),
                    item.operatingEffectiveness(),
                    Dimensions.from(item.dimensions()),
                    List.copyOf(item.supportingTestIds()),
                    List.copyOf(item.limitations()));
        }
    }

    public record Dimensions(
            DimensionMeasurement capability,
            DimensionMeasurement coverage,
            DimensionMeasurement operationalPerformance) {

        public static Dimensions from(FairCamControlAnalyticsResult.Dimensions d) {
            return new Dimensions(
                    DimensionMeasurement.from(d.capability()),
                    DimensionMeasurement.from(d.coverage()),
                    DimensionMeasurement.from(d.operationalPerformance()));
        }
    }

    public record DimensionMeasurement(double value, String scale, String units, String derivation) {
        public static DimensionMeasurement from(FairCamControlAnalyticsResult.DimensionMeasurement m) {
            return new DimensionMeasurement(m.value(), m.scale(), m.units(), m.derivation());
        }
    }

    public record Counts(int total, Map<String, Integer> byDomain, int withLimitations) {
        public static Counts from(FairCamControlAnalyticsResult.Counts c) {
            return new Counts(c.total(), Map.copyOf(c.byDomain()), c.withLimitations());
        }
    }
}

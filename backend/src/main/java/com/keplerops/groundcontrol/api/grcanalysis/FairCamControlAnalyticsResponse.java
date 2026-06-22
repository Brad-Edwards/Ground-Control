package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlAnalyticsResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlDomain;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamEffectDimension;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API DTO for FAIR-CAM control analytics per GC-I017. Decouples the public JSON
 * contract from the domain service record so future domain refactors do not
 * silently change the wire shape.
 */
public record FairCamControlAnalyticsResponse(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        List<ControlAnalyticsItem> controls,
        Counts counts,
        List<String> limitations) {

    public static FairCamControlAnalyticsResponse from(FairCamControlAnalyticsResult result) {
        return new FairCamControlAnalyticsResponse(
                result.analysisKind(),
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                result.controls().stream().map(ControlAnalyticsItem::from).toList(),
                Counts.from(result.counts()),
                List.copyOf(result.limitations()));
    }

    public record ControlAnalyticsItem(
            String endpointType,
            UUID endpointId,
            UUID controlId,
            UUID scopedImplementationId,
            String controlUid,
            String controlName,
            List<DomainAttribution> domainAttributions,
            Measurement capability,
            Measurement coverage,
            Measurement operationalPerformance,
            List<EffectEntry> effects,
            List<String> evidenceRefs,
            List<String> limitations) {

        public static ControlAnalyticsItem from(FairCamControlAnalyticsResult.ControlAnalyticsItem item) {
            return new ControlAnalyticsItem(
                    item.endpointType(),
                    item.endpointId(),
                    item.controlId(),
                    item.scopedImplementationId(),
                    item.controlUid(),
                    item.controlName(),
                    item.domainAttributions().stream()
                            .map(DomainAttribution::from)
                            .toList(),
                    item.capability() == null ? null : Measurement.from(item.capability()),
                    item.coverage() == null ? null : Measurement.from(item.coverage()),
                    item.operationalPerformance() == null ? null : Measurement.from(item.operationalPerformance()),
                    item.effects().stream().map(EffectEntry::from).toList(),
                    List.copyOf(item.evidenceRefs()),
                    List.copyOf(item.limitations()));
        }
    }

    public record DomainAttribution(FairCamControlDomain domain, String source, String analysisEndpoint) {

        public static DomainAttribution from(FairCamControlAnalyticsResult.DomainAttribution da) {
            return new DomainAttribution(da.domain(), da.source(), da.analysisEndpoint());
        }
    }

    public record Measurement(String scale, String units, Object value, String basis) {

        public static Measurement from(FairCamControlAnalyticsResult.Measurement m) {
            return new Measurement(m.scale(), m.units(), m.value(), m.basis());
        }
    }

    public record EffectEntry(FairCamEffectDimension dimension, Object value, String analysisEndpoint) {

        public static EffectEntry from(FairCamControlAnalyticsResult.EffectEntry e) {
            return new EffectEntry(e.dimension(), e.value(), e.analysisEndpoint());
        }
    }

    public record Counts(int total, Map<String, Integer> byDomain, int withLimitations) {

        public static Counts from(FairCamControlAnalyticsResult.Counts counts) {
            return new Counts(counts.total(), Map.copyOf(counts.byDomain()), counts.withLimitations());
        }
    }
}

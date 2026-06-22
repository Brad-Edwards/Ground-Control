package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain result for FAIR-CAM control analytics per GC-I017. Carries the
 * methodology-attributed envelope for control-level analytics so MCP/agent
 * callers receive structured, limitation-decorated output.
 */
public record FairCamControlAnalyticsResult(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        List<ControlAnalyticsItem> controls,
        Counts counts,
        List<String> limitations) {

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
            List<String> limitations) {}

    /**
     * A FAIR-CAM domain attribution for one control endpoint. {@code source} is the
     * substrate that drove the classification ({@code methodology_influence} or
     * {@code mapping_control_role}). {@code analysisEndpoint} identifies the mapping's
     * analysis endpoint (e.g. {@code RISK_SCENARIO:<uuid>}) the attribution is
     * contextual to, since FAIR-CAM facts are per-mapping, not per-control.
     */
    public record DomainAttribution(FairCamControlDomain domain, String source, String analysisEndpoint) {}

    public record Measurement(String scale, String units, Object value, String basis) {}

    /**
     * A methodology-specific effect on one FAIR-CAM dimension. {@code value} is the
     * opaque methodology-influence payload (pass-through). {@code analysisEndpoint}
     * identifies the mapping's analysis endpoint the effect is contextual to, so two
     * mappings of the same control with different effects on the same dimension stay
     * distinct rather than collapsing.
     */
    public record EffectEntry(FairCamEffectDimension dimension, Object value, String analysisEndpoint) {}

    public record Counts(int total, Map<String, Integer> byDomain, int withLimitations) {}
}

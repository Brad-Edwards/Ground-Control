package com.keplerops.groundcontrol.domain.grcanalysis.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Scope and as-of criteria for a FAIR-CAM control analytics query per GC-I017.
 *
 * <p>Bundles the optional filters the analysis supports so the service and its delegators take a
 * single criteria argument rather than a long positional parameter list. Every filter is optional;
 * supplied filters compose as an intersection over the candidate mappings.
 */
public record FairCamControlAnalyticsQuery(
        Instant asOf,
        int freshnessWindowDays,
        UUID controlId,
        UUID scopedImplementationId,
        UUID riskScenarioId,
        UUID riskRegisterRecordId,
        UUID threatModelId,
        UUID methodologyProfileId,
        FairCamControlDomain domain) {}

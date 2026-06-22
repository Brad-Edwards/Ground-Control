package com.keplerops.groundcontrol.unit.api.grcanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.api.grcanalysis.FairCamControlAnalyticsResponse;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlAnalyticsResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamControlDomain;
import com.keplerops.groundcontrol.domain.grcanalysis.service.FairCamEffectDimension;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FairCamControlAnalyticsResponseTest {

    private static final UUID CONTROL_ID = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();
    private static final Instant AS_OF = Instant.parse("2026-06-21T00:00:00Z");

    /** Builds a fully-populated single-control domain result and maps it to the API response. */
    private FairCamControlAnalyticsResponse sampleResponse() {
        var domainAttribution = new FairCamControlAnalyticsResult.DomainAttribution(
                FairCamControlDomain.LOSS_EVENT_CONTROL, "methodology_influence", "RISK_SCENARIO:abc");
        var capability = new FairCamControlAnalyticsResult.Measurement(
                "ordinal", "ControlEffectivenessRating", "EFFECTIVE", "latest design_effectiveness assessment as-of");
        var coverage = new FairCamControlAnalyticsResult.Measurement(
                "count", "endpoints", 2, "distinct analysis endpoints mapped");
        var opPerf = new FairCamControlAnalyticsResult.Measurement(
                "ordinal",
                "ControlEffectivenessRating",
                "PARTIALLY_EFFECTIVE",
                "latest operating_effectiveness as-of; 1 fresh PASS test(s) within 90 days");
        var effect = new FairCamControlAnalyticsResult.EffectEntry(
                FairCamEffectDimension.LOSS_EVENT_FREQUENCY, 0.3, "RISK_SCENARIO:abc");
        var item = new FairCamControlAnalyticsResult.ControlAnalyticsItem(
                "CONTROL",
                ENDPOINT_ID,
                CONTROL_ID,
                null,
                "CTRL-1",
                "Test Control",
                List.of(domainAttribution),
                capability,
                coverage,
                opPerf,
                List.of(effect),
                List.of("ev-ref-1"),
                List.of("some limitation"));

        var counts = new FairCamControlAnalyticsResult.Counts(1, Map.of("loss_event_control", 1), 1);
        var domainResult = new FairCamControlAnalyticsResult(
                "fair_cam_control_analytics",
                "ground-control",
                AS_OF,
                "fair-cam-control-analytics-v1",
                List.of(item),
                counts,
                List.of());

        return FairCamControlAnalyticsResponse.from(domainResult);
    }

    @Test
    void from_mapsTopLevelAndItemIdentityFields() {
        FairCamControlAnalyticsResponse response = sampleResponse();

        assertThat(response.analysisKind()).isEqualTo("fair_cam_control_analytics");
        assertThat(response.project()).isEqualTo("ground-control");
        assertThat(response.asOf()).isEqualTo(AS_OF);
        assertThat(response.derivationMethod()).isEqualTo("fair-cam-control-analytics-v1");
        assertThat(response.controls()).hasSize(1);

        var respItem = response.controls().get(0);
        assertThat(respItem.endpointType()).isEqualTo("CONTROL");
        assertThat(respItem.endpointId()).isEqualTo(ENDPOINT_ID);
        assertThat(respItem.controlId()).isEqualTo(CONTROL_ID);
        assertThat(respItem.controlUid()).isEqualTo("CTRL-1");
        assertThat(respItem.controlName()).isEqualTo("Test Control");
        assertThat(respItem.capability().value()).isEqualTo("EFFECTIVE");
        assertThat(respItem.coverage().value()).isEqualTo(2);
        assertThat(respItem.operationalPerformance().value()).isEqualTo("PARTIALLY_EFFECTIVE");
    }

    @Test
    void from_mapsAttributionsEffectsEvidenceAndCounts() {
        FairCamControlAnalyticsResponse response = sampleResponse();

        var respItem = response.controls().get(0);
        assertThat(respItem.domainAttributions()).hasSize(1);
        assertThat(respItem.domainAttributions().get(0).domain()).isEqualTo(FairCamControlDomain.LOSS_EVENT_CONTROL);
        assertThat(respItem.domainAttributions().get(0).source()).isEqualTo("methodology_influence");
        assertThat(respItem.domainAttributions().get(0).analysisEndpoint()).isEqualTo("RISK_SCENARIO:abc");
        assertThat(respItem.effects()).hasSize(1);
        assertThat(respItem.effects().get(0).dimension()).isEqualTo(FairCamEffectDimension.LOSS_EVENT_FREQUENCY);
        assertThat(respItem.effects().get(0).analysisEndpoint()).isEqualTo("RISK_SCENARIO:abc");
        assertThat(respItem.evidenceRefs()).containsExactly("ev-ref-1");
        assertThat(respItem.limitations()).containsExactly("some limitation");

        assertThat(response.counts().total()).isEqualTo(1);
        assertThat(response.counts().byDomain()).containsEntry("loss_event_control", 1);
        assertThat(response.counts().withLimitations()).isEqualTo(1);
        assertThat(response.limitations()).isEmpty();
    }

    @Test
    void from_emptyControlsList_mapsCorrectly() {
        var result = new FairCamControlAnalyticsResult(
                "fair_cam_control_analytics",
                "ground-control",
                Instant.parse("2026-06-21T00:00:00Z"),
                "fair-cam-control-analytics-v1",
                List.of(),
                new FairCamControlAnalyticsResult.Counts(0, Map.of(), 0),
                List.of());

        FairCamControlAnalyticsResponse response = FairCamControlAnalyticsResponse.from(result);

        assertThat(response.controls()).isEmpty();
        assertThat(response.counts().total()).isZero();
        assertThat(response.counts().byDomain()).isEmpty();
    }

    @Test
    void from_countsByDomain_mapsCorrectly() {
        var counts = new FairCamControlAnalyticsResult.Counts(
                3, Map.of("loss_event_control", 2, "decision_support_control", 1), 1);
        var result = new FairCamControlAnalyticsResult(
                "fair_cam_control_analytics",
                "ground-control",
                Instant.parse("2026-06-21T00:00:00Z"),
                "fair-cam-control-analytics-v1",
                List.of(),
                counts,
                List.of());

        FairCamControlAnalyticsResponse response = FairCamControlAnalyticsResponse.from(result);

        assertThat(response.counts().byDomain()).containsEntry("loss_event_control", 2);
        assertThat(response.counts().byDomain()).containsEntry("decision_support_control", 1);
        assertThat(response.counts().withLimitations()).isEqualTo(1);
    }
}

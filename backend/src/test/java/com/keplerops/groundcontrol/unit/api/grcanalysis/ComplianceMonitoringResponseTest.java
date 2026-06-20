package com.keplerops.groundcontrol.unit.api.grcanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.api.grcanalysis.ComplianceMonitoringResponse;
import com.keplerops.groundcontrol.domain.grcanalysis.service.ComplianceMonitoringResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ComplianceMonitoringResponseTest {

    private static final Instant AS_OF = Instant.parse("2026-06-20T00:00:00Z");
    private static final UUID IMPACT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STALE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID GAP_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static ComplianceMonitoringResult populatedResult() {
        return new ComplianceMonitoringResult(
                "continuous_compliance_monitoring",
                "ground-control",
                AS_OF,
                "continuous-compliance-monitoring-v1",
                new ComplianceMonitoringResult.Inputs("ground-control", AS_OF, 90),
                List.of(new ComplianceMonitoringResult.ImpactItem(
                        "CONTROL_MODIFICATION",
                        "CONTROL",
                        IMPACT_ID,
                        "CTRL-1",
                        AS_OF.minusSeconds(3600),
                        "Control modified within lookback window")),
                List.of(new ComplianceMonitoringResult.GapItem("COVERAGE", "CONTROL", GAP_ID, "CTRL-GAP", "No model")),
                List.of(new ComplianceMonitoringResult.StaleItem(
                        "OBSERVATION", STALE_ID, "patch-level", "EXPIRED", AS_OF)),
                new ComplianceMonitoringResult.DriftCauseCounts(1, 0, 1),
                List.of("limitation-a"));
    }

    @Test
    void from_mapsTopLevelFields() {
        ComplianceMonitoringResponse response = ComplianceMonitoringResponse.from(populatedResult());

        assertThat(response.analysisKind()).isEqualTo("continuous_compliance_monitoring");
        assertThat(response.project()).isEqualTo("ground-control");
        assertThat(response.asOf()).isEqualTo(AS_OF);
        assertThat(response.derivationMethod()).isEqualTo("continuous-compliance-monitoring-v1");
    }

    @Test
    void from_mapsNestedRecords() {
        ComplianceMonitoringResponse response = ComplianceMonitoringResponse.from(populatedResult());

        assertThat(response.inputs().freshnessWindowDays()).isEqualTo(90);
        assertThat(response.impactSet()).hasSize(1);
        assertThat(response.impactSet().getFirst().entityUid()).isEqualTo("CTRL-1");
        assertThat(response.gapSet()).hasSize(1);
        assertThat(response.gapSet().getFirst().gapKind()).isEqualTo("COVERAGE");
        assertThat(response.staleSet()).hasSize(1);
        assertThat(response.staleSet().getFirst().state()).isEqualTo("EXPIRED");
        assertThat(response.driftCauseCounts().controlModification()).isEqualTo(1);
        assertThat(response.driftCauseCounts().artifactGraphChange()).isZero();
        assertThat(response.driftCauseCounts().evidenceExpiration()).isEqualTo(1);
        assertThat(response.limitations()).containsExactly("limitation-a");
    }

    @Test
    void from_emptyLists_mapCleanly() {
        ComplianceMonitoringResult result = new ComplianceMonitoringResult(
                "continuous_compliance_monitoring",
                "ground-control",
                AS_OF,
                "continuous-compliance-monitoring-v1",
                new ComplianceMonitoringResult.Inputs("ground-control", AS_OF, 90),
                List.of(),
                List.of(),
                List.of(),
                new ComplianceMonitoringResult.DriftCauseCounts(0, 0, 0),
                List.of());

        ComplianceMonitoringResponse response = ComplianceMonitoringResponse.from(result);

        assertThat(response.impactSet()).isEmpty();
        assertThat(response.gapSet()).isEmpty();
        assertThat(response.staleSet()).isEmpty();
        assertThat(response.limitations()).isEmpty();
    }
}

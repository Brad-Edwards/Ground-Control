package com.keplerops.groundcontrol.unit.domain.workflowtelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.workflowtelemetry.service.StationCatalog;
import org.junit.jupiter.api.Test;

/**
 * The backend resolves an ADR-036 routing stage to its catalogue station (ADR-090 amendment, issue
 * #1354). A durable step observation carries {@code phase=stage_id}; the canonical {@code station_id}
 * is resolved here so the emitter never has to duplicate the catalogue.
 *
 * <p>Runs against the real catalogue the build copies from {@code contracts/measurement/}: the point
 * is that the backend and the published contract agree on which stage is which station.
 */
class StationCatalogStageResolutionTest {

    private final StationCatalog catalog = new StationCatalog();

    @Test
    void stationAliasResolvesToCanonicalStationId() {
        // A stage that is a station's own name.
        assertThat(catalog.resolveStationForStage("completion_gate")).contains("completion_gate");
        // A stage that is an alias of a differently-named station.
        assertThat(catalog.resolveStationForStage("review_cycle_1_consume")).contains("codex_review");
        assertThat(catalog.resolveStationForStage("ci_monitor")).contains("ci");
        assertThat(catalog.resolveStationForStage("base_sync")).contains("git_publish");
    }

    @Test
    void markerAndNonStationStagesAreKnownButCarryNoStation() {
        // A declared lifecycle-marker stage: inspects nothing, so no station — but it is not unknown.
        assertThat(catalog.isKnownStage("planning")).isTrue();
        assertThat(catalog.resolveStationForStage("planning")).isEmpty();
        // A declared non-station stage.
        assertThat(catalog.isKnownStage("implementation")).isTrue();
        assertThat(catalog.resolveStationForStage("implementation")).isEmpty();
        assertThat(catalog.isKnownStage("clause_mapping")).isTrue();
        assertThat(catalog.resolveStationForStage("clause_mapping")).isEmpty();
    }

    @Test
    void undeclaredStageIsUnknown() {
        assertThat(catalog.isKnownStage("not_a_real_stage")).isFalse();
        assertThat(catalog.resolveStationForStage("not_a_real_stage")).isEmpty();
        assertThat(catalog.isKnownStage(null)).isFalse();
    }

    @Test
    void adr061PhaseAliasesResolveToCanonicalStationIds() {
        assertThat(catalog.resolveStationForPhase("preflight")).contains("architecture_preflight");
        assertThat(catalog.resolveStationForPhase("completion_gate")).contains("completion_gate");
        assertThat(catalog.resolveStationForPhase("ci")).contains("ci");
    }

    @Test
    void directAndAliasedLifecycleMarkersResolveWithoutBecomingStations() {
        assertThat(catalog.isLifecycleMarkerPhase("plan")).isTrue();
        assertThat(catalog.resolveStationForPhase("plan")).isEmpty();
        assertThat(catalog.isLifecycleMarkerPhase("traceability_reconcile")).isTrue();
        assertThat(catalog.resolveStationForPhase("traceability_reconcile")).isEmpty();
    }
}

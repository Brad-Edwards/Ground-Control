package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskHeatmapResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API DTO for the GC-T008 qualitative risk heat map. Decouples the public JSON
 * contract from the domain service record per the api/grcanalysis precedent.
 */
public record RiskHeatmapResponse(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        UUID methodologyProfileId,
        String methodologyFamily,
        String scale,
        String units,
        Inputs inputs,
        List<HeatmapCell> cells,
        Counts counts,
        List<String> limitations) {

    public static RiskHeatmapResponse from(RiskHeatmapResult result) {
        return new RiskHeatmapResponse(
                result.analysisKind(),
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                result.methodologyProfileId(),
                result.methodologyFamily(),
                result.scale(),
                result.units(),
                Inputs.from(result.inputs()),
                result.cells().stream().map(HeatmapCell::from).toList(),
                Counts.from(result.counts()),
                List.copyOf(result.limitations()));
    }

    public record Inputs(String project, Instant asOf, UUID methodologyProfileId) {

        public static Inputs from(RiskHeatmapResult.Inputs inputs) {
            return new Inputs(inputs.project(), inputs.asOf(), inputs.methodologyProfileId());
        }
    }

    public record HeatmapCell(
            int likelihoodOrdinal,
            String likelihoodBand,
            int impactOrdinal,
            String impactBand,
            int count,
            List<UUID> riskAssessmentResultIds) {

        public static HeatmapCell from(RiskHeatmapResult.HeatmapCell cell) {
            return new HeatmapCell(
                    cell.likelihoodOrdinal(),
                    cell.likelihoodBand(),
                    cell.impactOrdinal(),
                    cell.impactBand(),
                    cell.count(),
                    List.copyOf(cell.riskAssessmentResultIds()));
        }
    }

    public record Counts(
            int totalAssessments,
            int assessmentsPlotted,
            int assessmentsIncompatible,
            Map<String, Integer> byMethodologyFamily) {

        public static Counts from(RiskHeatmapResult.Counts counts) {
            return new Counts(
                    counts.totalAssessments(),
                    counts.assessmentsPlotted(),
                    counts.assessmentsIncompatible(),
                    Map.copyOf(counts.byMethodologyFamily()));
        }
    }
}

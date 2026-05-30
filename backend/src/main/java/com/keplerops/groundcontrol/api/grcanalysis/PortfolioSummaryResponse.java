package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.PortfolioSummaryResult;
import java.time.Instant;
import java.util.List;

/**
 * HTTP DTO for the {@code GET /api/v1/analysis/grc/portfolio} endpoint (GC-Q013).
 *
 * <p>The portfolio result is composed entirely of pure value records (counts, distribution maps, and
 * drill-down id lists) with no entity references or sensitive payloads, so this DTO reuses the domain
 * result's nested records directly rather than duplicating eight boilerplate mirrors — the wire
 * contract is still pinned to a stable API type name.
 */
public record PortfolioSummaryResponse(
        String project,
        Instant asOf,
        String derivationMethod,
        PortfolioSummaryResult.RiskPosture riskPosture,
        PortfolioSummaryResult.ControlHealth controlHealth,
        PortfolioSummaryResult.EvidenceFreshness evidenceFreshness,
        PortfolioSummaryResult.FindingTrends findingTrends,
        PortfolioSummaryResult.AssetCriticality assetCriticality,
        List<PortfolioSummaryResult.MethodologySummary> methodologySummaries,
        List<String> limitations) {

    public static PortfolioSummaryResponse from(PortfolioSummaryResult result) {
        return new PortfolioSummaryResponse(
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                result.riskPosture(),
                result.controlHealth(),
                result.evidenceFreshness(),
                result.findingTrends(),
                result.assetCriticality(),
                result.methodologySummaries(),
                result.limitations());
    }
}

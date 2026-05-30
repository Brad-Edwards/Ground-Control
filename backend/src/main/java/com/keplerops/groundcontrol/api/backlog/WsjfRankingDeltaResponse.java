package com.keplerops.groundcontrol.api.backlog;

import com.keplerops.groundcontrol.domain.backlog.model.WsjfDistribution;
import java.util.List;

/**
 * WSJF re-prioritization analysis result per ADR-035.
 */
public record WsjfRankingDeltaResponse(
        String analysisKind, String scale, String units, String limitations, List<WsjfDistribution.RankDelta> deltas) {

    public static WsjfRankingDeltaResponse from(List<WsjfDistribution.RankDelta> deltas) {
        return new WsjfRankingDeltaResponse(
                "wsjf_ranking_delta",
                "rank",
                "positions",
                "Ranks are by-mean across the same seeded Monte Carlo iteration count; pairs with high"
                        + " probability-of-dominance overlap should be treated as indistinguishable.",
                deltas);
    }
}

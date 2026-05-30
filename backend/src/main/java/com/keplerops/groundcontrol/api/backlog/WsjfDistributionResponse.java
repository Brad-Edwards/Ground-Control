package com.keplerops.groundcontrol.api.backlog;

import com.keplerops.groundcontrol.domain.backlog.model.WsjfDistribution;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WSJF distribution result envelope per ADR-035: carries the analysisKind,
 * scale/units, limitations, and the raw samples + summary statistics so a
 * caller can recompute or visualise.
 */
public record WsjfDistributionResponse(
        String analysisKind,
        String scale,
        String units,
        String limitations,
        UUID backlogItemId,
        long seed,
        int iterations,
        double mean,
        double median,
        double p10,
        double p90,
        List<Double> samples) {

    public static WsjfDistributionResponse from(UUID backlogItemId, WsjfDistribution dist) {
        double[] raw = dist.samples();
        List<Double> boxed = new ArrayList<>(raw.length);
        for (double v : raw) {
            boxed.add(v);
        }
        return new WsjfDistributionResponse(
                "wsjf",
                "dimensionless",
                "value-per-week",
                "WSJF assumes calibrated component estimates; quantiles narrow only as iterations grow "
                        + "and become unstable below ~1000 iterations.",
                backlogItemId,
                dist.seed(),
                dist.iterations(),
                dist.mean(),
                dist.median(),
                dist.p10(),
                dist.p90(),
                boxed);
    }
}

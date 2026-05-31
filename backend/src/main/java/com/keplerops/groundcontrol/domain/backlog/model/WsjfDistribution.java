package com.keplerops.groundcontrol.domain.backlog.model;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.grcanalysis.util.SeededMonteCarlo;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Distributional WSJF score per {@link BacklogItem} computed via seeded Monte
 * Carlo over the four CoD components and the duration component.
 *
 * <p>{@code samples} is the raw draw vector in iteration order; quantile fields
 * are derived statistics that consumers and reports use directly so callers
 * don't recompute them. {@code seed} and {@code iterations} are surfaced so a
 * reviewer can re-run the same computation and confirm a result.
 */
@SuppressWarnings("ArrayRecordComponent") // primitive array intentional: avoids boxing overhead for Monte Carlo samples
public record WsjfDistribution(
        long seed, int iterations, double[] samples, double mean, double median, double p10, double p90) {

    public WsjfDistribution {
        if (iterations <= 0) {
            throw new DomainValidationException("iterations must be positive, got " + iterations);
        }
        if (samples == null || samples.length != iterations) {
            throw new DomainValidationException("samples length must match iterations");
        }
    }

    /**
     * Compute the WSJF distribution for a single backlog item. WSJF =
     * (userBusinessValue + timeCriticality + riskReductionOpportunityEnablement)
     * / jobDuration.
     */
    public static WsjfDistribution compute(
            CostOfDelayComponent userBusinessValue,
            CostOfDelayComponent timeCriticality,
            CostOfDelayComponent riskReductionOpportunityEnablement,
            CostOfDelayComponent jobDuration,
            long seed,
            int iterations) {
        if (userBusinessValue == null
                || timeCriticality == null
                || riskReductionOpportunityEnablement == null
                || jobDuration == null) {
            throw new DomainValidationException("All four CoD components are required to compute WSJF");
        }
        if (iterations <= 0) {
            throw new DomainValidationException("iterations must be positive, got " + iterations);
        }
        var rng = new SeededMonteCarlo(seed);
        double[] samples = new double[iterations];
        for (int i = 0; i < iterations; i++) {
            double ubv = userBusinessValue.draw(rng);
            double tc = timeCriticality.draw(rng);
            double rroe = riskReductionOpportunityEnablement.draw(rng);
            double jd = jobDuration.draw(rng);
            samples[i] = (ubv + tc + rroe) / jd;
        }
        return ofSamples(seed, samples);
    }

    private static WsjfDistribution ofSamples(long seed, double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (double s : samples) {
            sum += s;
        }
        double mean = sum / samples.length;
        double median = quantile(sorted, 0.5);
        double p10 = quantile(sorted, 0.10);
        double p90 = quantile(sorted, 0.90);
        return new WsjfDistribution(seed, samples.length, samples, mean, median, p10, p90);
    }

    private static double quantile(double[] sortedAscending, double q) {
        if (q <= 0) {
            return sortedAscending[0];
        }
        if (q >= 1) {
            return sortedAscending[sortedAscending.length - 1];
        }
        double idx = q * (sortedAscending.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        // When lo == hi the interpolation below reduces to sortedAscending[lo] correctly.
        return sortedAscending[lo] + (sortedAscending[hi] - sortedAscending[lo]) * (idx - lo);
    }

    /**
     * Probability that the WSJF score for the {@code first} item dominates the
     * {@code second} item across paired samples drawn from the same seed. Used
     * by the re-prioritization analysis to flag statistically indistinguishable
     * pairs.
     */
    public static double probabilityFirstDominatesSecond(WsjfDistribution first, WsjfDistribution second) {
        if (first.iterations != second.iterations) {
            throw new DomainValidationException("Distributions must share iteration count to compare; got "
                    + first.iterations + " vs " + second.iterations);
        }
        int wins = 0;
        for (int i = 0; i < first.iterations; i++) {
            if (first.samples[i] > second.samples[i]) {
                wins++;
            }
        }
        return wins / (double) first.iterations;
    }

    /** Defensive copy — array fields in records expose internal state otherwise. */
    @Override
    public double[] samples() {
        return samples.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WsjfDistribution other = (WsjfDistribution) o;
        return seed == other.seed
                && iterations == other.iterations
                && Double.compare(mean, other.mean) == 0
                && Double.compare(median, other.median) == 0
                && Double.compare(p10, other.p10) == 0
                && Double.compare(p90, other.p90) == 0
                && Arrays.equals(samples, other.samples());
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(seed);
        result = 31 * result + Integer.hashCode(iterations);
        result = 31 * result + Arrays.hashCode(samples);
        result = 31 * result + Double.hashCode(mean);
        result = 31 * result + Double.hashCode(median);
        result = 31 * result + Double.hashCode(p10);
        result = 31 * result + Double.hashCode(p90);
        return result;
    }

    @Override
    public String toString() {
        return "WsjfDistribution["
                + "seed=" + seed
                + ", iterations=" + iterations
                + ", samples=" + Arrays.toString(samples)
                + ", mean=" + mean
                + ", median=" + median
                + ", p10=" + p10
                + ", p90=" + p90
                + ']';
    }

    /**
     * Rank-by-mean delta between two WSJF distribution lists sharing the same
     * BacklogItem identifiers. Used by re-prioritization analysis.
     *
     * <p>The {@code idsBefore} and {@code idsAfter} lists must contain the same
     * set of identifiers (order may differ) so a ranking delta is defined for
     * every entry.
     */
    public static List<RankDelta> rankingDelta(
            List<UUID> idsBefore, List<WsjfDistribution> before, List<UUID> idsAfter, List<WsjfDistribution> after) {
        if (idsBefore.size() != before.size() || idsAfter.size() != after.size()) {
            throw new DomainValidationException("id list size must match distribution list size");
        }
        if (idsBefore.size() != idsAfter.size()) {
            throw new DomainValidationException("before and after must cover the same item set");
        }
        var beforeRanked = rankByMeanDescending(idsBefore, before);
        var afterRanked = rankByMeanDescending(idsAfter, after);
        return beforeRanked.entrySet().stream()
                .map(e -> {
                    var id = e.getKey();
                    int beforeRank = e.getValue();
                    Integer afterRank = afterRanked.get(id);
                    if (afterRank == null) {
                        throw new DomainValidationException("missing after-rank for id " + id);
                    }
                    return new RankDelta(id, beforeRank, afterRank, beforeRank - afterRank);
                })
                .sorted((a, b) -> Integer.compare(Math.abs(b.delta()), Math.abs(a.delta())))
                .toList();
    }

    private static java.util.Map<UUID, Integer> rankByMeanDescending(List<UUID> ids, List<WsjfDistribution> dists) {
        record Entry(UUID id, double mean) {}
        var entries = new java.util.ArrayList<Entry>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            entries.add(new Entry(ids.get(i), dists.get(i).mean()));
        }
        entries.sort((a, b) -> Double.compare(b.mean(), a.mean()));
        var ranks = new java.util.LinkedHashMap<UUID, Integer>();
        for (int i = 0; i < entries.size(); i++) {
            ranks.put(entries.get(i).id(), i + 1);
        }
        return ranks;
    }

    /** One row of a ranking delta: positive delta means the item moved up. */
    public record RankDelta(UUID id, int beforeRank, int afterRank, int delta) {}
}

package com.keplerops.groundcontrol.domain.grcanalysis.util;

import java.util.SplittableRandom;
import java.util.function.DoubleSupplier;

/**
 * Deterministic, seeded Monte Carlo sampling helper shared across analyses that
 * must produce reproducible probability-distribution outputs.
 *
 * <p>Centralised per the cluster cross-cutting decision: a single seeded RNG
 * path keeps WSJF (GC-W003) and any future FAIR / LEC sampling auditable
 * against the same seed semantics. Callers MUST pass an explicit seed —
 * non-deterministic sampling is a separate concern handled by call-site
 * {@link java.util.Random} usage and is intentionally not exposed here.
 *
 * <p>{@link SplittableRandom} is preferred over {@link java.util.Random}: it is
 * non-Locking, has better statistical properties for small batches, and
 * supports easy split-off for parallel streams if a future analysis kind needs
 * it. The contract is sequential by default.
 */
public final class SeededMonteCarlo {

    private final SplittableRandom rng;

    public SeededMonteCarlo(long seed) {
        this.rng = new SplittableRandom(seed);
    }

    /**
     * Draw {@code iterations} samples from {@code supplier} and return them in
     * draw order. {@code iterations} must be positive.
     */
    public double[] sample(int iterations, DoubleSupplier supplier) {
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive, got " + iterations);
        }
        if (supplier == null) {
            throw new IllegalArgumentException("supplier must not be null");
        }
        double[] out = new double[iterations];
        for (int i = 0; i < iterations; i++) {
            out[i] = supplier.getAsDouble();
        }
        return out;
    }

    /** Uniform draw in {@code [0, 1)}. */
    public double nextUniform() {
        return rng.nextDouble();
    }

    /**
     * Draw from a triangular distribution with minimum {@code min}, mode
     * {@code mode}, and maximum {@code max}. Requires {@code min <= mode <= max}
     * and {@code min < max}.
     */
    public double nextTriangular(double min, double mode, double max) {
        validateTriangular(min, mode, max);
        double u = rng.nextDouble();
        double f = (mode - min) / (max - min);
        if (u < f) {
            return min + Math.sqrt(u * (max - min) * (mode - min));
        }
        return max - Math.sqrt((1.0 - u) * (max - min) * (max - mode));
    }

    /**
     * Draw from a uniform distribution on {@code [min, max)}. Requires
     * {@code min < max}.
     */
    public double nextUniformRange(double min, double max) {
        if (!(min < max)) {
            throw new IllegalArgumentException("min must be less than max; got min=" + min + ", max=" + max);
        }
        return min + (max - min) * rng.nextDouble();
    }

    private static void validateTriangular(double min, double mode, double max) {
        if (Double.isNaN(min) || Double.isNaN(mode) || Double.isNaN(max)) {
            throw new IllegalArgumentException("triangular distribution parameters must not be NaN");
        }
        if (!(min <= mode && mode <= max)) {
            throw new IllegalArgumentException("triangular distribution requires min <= mode <= max; got min=" + min
                    + ", mode=" + mode + ", max=" + max);
        }
        if (min == max) {
            throw new IllegalArgumentException(
                    "triangular distribution requires min < max; got degenerate min=max=" + min);
        }
    }
}

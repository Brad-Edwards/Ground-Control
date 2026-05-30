package com.keplerops.groundcontrol.domain.backlog.model;

/**
 * Probability distribution shapes accepted by Cost-of-Delay / WSJF component
 * estimates per GC-W003.
 *
 * <p>TRIANGULAR carries {@code (min, mode, max)}; UNIFORM carries
 * {@code (min, max)} (mode is ignored); POINT collapses to a deterministic
 * value (min == mode == max). Calibrated inputs always express POINT as a
 * triangle with width zero is not allowed by the seeded sampler — so POINT
 * short-circuits sampling and returns the point value directly.
 */
public enum DistributionKind {
    POINT,
    UNIFORM,
    TRIANGULAR
}

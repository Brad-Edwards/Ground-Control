package com.keplerops.groundcontrol.domain.backlog.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.grcanalysis.util.SeededMonteCarlo;
import java.util.Map;

/**
 * One probability-distribution-valued input to the Cost-of-Delay calculation
 * (user-business value, time criticality, risk reduction / opportunity
 * enablement) or to the job-duration denominator.
 *
 * <p>Values are positive (CoD components are scoring inputs; job duration is a
 * positive number of weeks). {@code attributedTo} captures who provided the
 * estimate per the ActorHolder contract (ADR-033).
 */
public record CostOfDelayComponent(DistributionKind kind, double min, double mode, double max, String attributedTo) {

    @JsonCreator
    public CostOfDelayComponent(
            @JsonProperty("kind") DistributionKind kind,
            @JsonProperty("min") double min,
            @JsonProperty("mode") double mode,
            @JsonProperty("max") double max,
            @JsonProperty("attributedTo") String attributedTo) {
        if (kind == null) {
            throw new DomainValidationException("CostOfDelayComponent.kind must not be null");
        }
        validate(kind, min, mode, max);
        this.kind = kind;
        this.min = min;
        this.mode = mode;
        this.max = max;
        this.attributedTo = attributedTo;
    }

    public static CostOfDelayComponent point(double value, String attributedTo) {
        return new CostOfDelayComponent(DistributionKind.POINT, value, value, value, attributedTo);
    }

    public static CostOfDelayComponent triangular(double min, double mode, double max, String attributedTo) {
        return new CostOfDelayComponent(DistributionKind.TRIANGULAR, min, mode, max, attributedTo);
    }

    public static CostOfDelayComponent uniform(double min, double max, String attributedTo) {
        return new CostOfDelayComponent(DistributionKind.UNIFORM, min, min, max, attributedTo);
    }

    /**
     * Return a copy with {@code attributedTo} replaced. The wire format carries
     * {@code attributedTo} so callers can read who supplied an estimate, but
     * services overwrite it with the authenticated principal so the Envers
     * audit trail can never persist a client-controlled attribution string per
     * ADR-033's ActorHolder contract.
     */
    public CostOfDelayComponent withAttributedTo(String newAttributedTo) {
        return new CostOfDelayComponent(kind, min, mode, max, newAttributedTo);
    }

    /** Draw one sample from the configured distribution. */
    public double draw(SeededMonteCarlo rng) {
        return switch (kind) {
            case POINT -> min;
            case UNIFORM -> rng.nextUniformRange(min, max);
            case TRIANGULAR -> rng.nextTriangular(min, mode, max);
        };
    }

    private static void validate(DistributionKind kind, double min, double mode, double max) {
        if (Double.isNaN(min) || Double.isNaN(mode) || Double.isNaN(max)) {
            throw new DomainValidationException(
                    "CostOfDelayComponent values must not be NaN",
                    "validation_error",
                    Map.of("min", String.valueOf(min), "mode", String.valueOf(mode), "max", String.valueOf(max)));
        }
        if (min <= 0) {
            throw new DomainValidationException(
                    "CostOfDelayComponent.min must be positive; got " + min,
                    "validation_error",
                    Map.of("field", "min"));
        }
        switch (kind) {
            case POINT -> validatePoint(min, mode, max);
            case UNIFORM -> validateUniform(min, max);
            case TRIANGULAR -> validateTriangular(min, mode, max);
            default -> throw new IllegalStateException("Unhandled DistributionKind: " + kind);
        }
    }

    private static void validatePoint(double min, double mode, double max) {
        if (min != mode || min != max) {
            throw new DomainValidationException(
                    "POINT distribution requires min == mode == max",
                    "validation_error",
                    Map.of("min", String.valueOf(min), "mode", String.valueOf(mode), "max", String.valueOf(max)));
        }
    }

    private static void validateUniform(double min, double max) {
        if (min >= max) {
            throw new DomainValidationException(
                    "UNIFORM distribution requires min < max",
                    "validation_error",
                    Map.of("min", String.valueOf(min), "max", String.valueOf(max)));
        }
    }

    private static void validateTriangular(double min, double mode, double max) {
        if (!(min <= mode && mode <= max) || min == max) {
            throw new DomainValidationException(
                    "TRIANGULAR distribution requires min <= mode <= max and min < max",
                    "validation_error",
                    Map.of("min", String.valueOf(min), "mode", String.valueOf(mode), "max", String.valueOf(max)));
        }
    }
}

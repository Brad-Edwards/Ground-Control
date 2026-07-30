package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds for the project-scoped workflow activity projection (issue #1437).
 *
 * <p>These are non-secret operational settings. The service carries the effective threshold on
 * every open row so a future station-specific override can stay behind this configuration seam
 * without changing the response contract.
 */
@Validated
@ConfigurationProperties(prefix = "groundcontrol.workflow-telemetry.activity")
public class WorkflowActivityProperties {

    @NotNull private Duration stallThreshold = Duration.ofMinutes(30);

    @Min(1) @Max(500) private int maxOpenRuns = 100;

    @Min(1) @Max(50) private int recentRuns = 8;

    @AssertTrue(message = "groundcontrol.workflow-telemetry.activity.stall-threshold must be positive") public boolean isStallThresholdPositive() {
        return stallThreshold != null && !stallThreshold.isZero() && !stallThreshold.isNegative();
    }

    public Duration getStallThreshold() {
        return stallThreshold;
    }

    public void setStallThreshold(Duration stallThreshold) {
        this.stallThreshold = stallThreshold;
    }

    public int getMaxOpenRuns() {
        return maxOpenRuns;
    }

    public void setMaxOpenRuns(int maxOpenRuns) {
        this.maxOpenRuns = maxOpenRuns;
    }

    public int getRecentRuns() {
        return recentRuns;
    }

    public void setRecentRuns(int recentRuns) {
        this.recentRuns = recentRuns;
    }
}

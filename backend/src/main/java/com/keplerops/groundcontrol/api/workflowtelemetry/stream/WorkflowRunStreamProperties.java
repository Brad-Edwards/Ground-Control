package com.keplerops.groundcontrol.api.workflowtelemetry.stream;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds for the workflow-run live stream (issue #1436, ADR-061 #1436 amendment).
 *
 * <p>Every bound the stream enforces is a configuration parameter rather than a constant duplicated
 * across the controller, the hub, and the scheduler. Registered by the application's
 * {@code @ConfigurationPropertiesScan}; the relation checks below run at startup so a misconfigured
 * deployment fails fast instead of serving a stream whose heartbeat can never beat its own timeout.
 *
 * <p>These values are operational, non-secret tuning. They are bound from the environment through
 * {@code application.yml} and must never be passed in process argv.
 */
@Validated
@ConfigurationProperties(prefix = "groundcontrol.workflow-telemetry.stream")
public class WorkflowRunStreamProperties {

    /** Whether the stream endpoint accepts subscriptions. When false it rejects with 503. */
    private boolean enabled = true;

    /** Hard cap on concurrent connections across every project and principal. */
    @Min(1) private int maxConnections = 64;

    /**
     * Cap on concurrent connections held by one authenticated principal, so a single caller cannot
     * consume the global budget by opening tabs.
     */
    @Min(1) private int maxConnectionsPerPrincipal = 8;

    /** Bounded per-connection FIFO depth. Overflow disconnects that connection rather than dropping an event. */
    @Min(1) private int queueCapacity = 64;

    /** Comment heartbeat cadence. Must stay below both {@link #idleTimeout} and any proxy read timeout. */
    @NotNull private Duration heartbeatInterval = Duration.ofSeconds(15);

    /**
     * Finite emitter lifetime. Bounds how long a connection can outlive the authorization decision
     * that admitted it: the client reconnects and is re-authorized.
     */
    @NotNull private Duration idleTimeout = Duration.ofMinutes(15);

    @AssertTrue(
            message = "groundcontrol.workflow-telemetry.stream.heartbeat-interval must be positive and"
                    + " shorter than idle-timeout, otherwise a connection times out before it is ever kept alive")
    public boolean isHeartbeatShorterThanIdleTimeout() {
        return heartbeatInterval != null
                && idleTimeout != null
                && !heartbeatInterval.isZero()
                && !heartbeatInterval.isNegative()
                && heartbeatInterval.compareTo(idleTimeout) < 0;
    }

    @AssertTrue(
            message = "groundcontrol.workflow-telemetry.stream.max-connections-per-principal must not exceed"
                    + " max-connections, otherwise the per-principal cap can never bind")
    public boolean isPerPrincipalCapWithinGlobalCap() {
        return maxConnectionsPerPrincipal <= maxConnections;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMaxConnectionsPerPrincipal() {
        return maxConnectionsPerPrincipal;
    }

    public void setMaxConnectionsPerPrincipal(int maxConnectionsPerPrincipal) {
        this.maxConnectionsPerPrincipal = maxConnectionsPerPrincipal;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }
}

package com.keplerops.groundcontrol.infrastructure.temporal.implement;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/**
 * Deterministic factory for the {@code /implement} activity retry/timeout policy (ADR-028: retry policy
 * belongs at the activity boundary, not in ad hoc workflow sleep loops).
 *
 * <p>Expected domain failures are already thrown as non-retryable {@link io.temporal.failure.ApplicationFailure}s
 * by the activity implementations; these options bound retries of <em>transient</em> infrastructure
 * failures. Long-running side effects (the completion build) get a wider start-to-close timeout.
 */
final class ImplementActivityOptions {

    private ImplementActivityOptions() {}

    private static final RetryOptions TRANSIENT_RETRY = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofSeconds(30))
            .setMaximumAttempts(5)
            .build();

    static ActivityOptions standard() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .setRetryOptions(TRANSIENT_RETRY)
                .build();
    }

    static ActivityOptions longRunning() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(60))
                .setRetryOptions(TRANSIENT_RETRY)
                .build();
    }
}

package com.keplerops.groundcontrol.infrastructure.temporal.implement;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/**
 * Dedicated, explicitly bounded retry/timeout policy for the LLM-backed content activity seam
 * (ADR-028). LLM calls have cost and longer latency than the deterministic {@code /implement}
 * activities, so {@link ImplementActivityOptions#standard()}/{@code longRunning()} are not reused
 * blindly here: the start-to-close timeout, maximum attempts, and retryable-failure catalog are
 * explicit and bounded for this seam specifically.
 *
 * <p>{@link com.keplerops.groundcontrol.domain.exception.DomainValidationException} (route/model/
 * credential rejection, provider 4xx) is marked non-retryable via {@code doNotRetry} — Temporal retry
 * attempts are infrastructure retries, not review cycles, and must not spend further model calls on a
 * controlled rejection. {@link com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException}
 * (timeout, connection failure, 429, eligible provider 5xx) is left off the {@code doNotRetry} list so
 * Temporal's default retryable-failure handling applies.
 */
final class LlmActivityOptions {

    private LlmActivityOptions() {}

    private static final RetryOptions LLM_RETRY = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(2))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofMinutes(2))
            .setMaximumAttempts(4)
            .setDoNotRetry(DomainValidationException.class.getName())
            .build();

    static ActivityOptions standard() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(10))
                .setRetryOptions(LLM_RETRY)
                .build();
    }
}

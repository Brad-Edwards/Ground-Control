package com.keplerops.groundcontrol.infrastructure.temporal.implement;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * LLM calls have cost and longer latency than the deterministic activities (architecture preflight);
 * {@code LlmActivityOptions} is a dedicated, explicitly bounded policy — not
 * {@code ImplementActivityOptions.standard()} reused blindly.
 */
class LlmActivityOptionsTest {

    /**
     * Pins the configured policy exactly. A loose upper bound would still pass if the timeout collapsed to a
     * second or the attempt ceiling dropped to 1 (disabling retries outright) — the very regressions this
     * policy exists to prevent.
     */
    @Test
    void startToCloseTimeoutIsTheConfiguredTenMinutes() {
        var options = LlmActivityOptions.standard();

        assertThat(options.getStartToCloseTimeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void maximumAttemptsIsTheConfiguredFour() {
        var options = LlmActivityOptions.standard();

        assertThat(options.getRetryOptions()).isNotNull();
        assertThat(options.getRetryOptions().getMaximumAttempts()).isEqualTo(4);
    }

    @Test
    void domainValidationExceptionIsMarkedNonRetryable() {
        var options = LlmActivityOptions.standard();

        assertThat(options.getRetryOptions().getDoNotRetry()).contains(DomainValidationException.class.getName());
    }

    @Test
    void serviceUnavailableExceptionIsNotMarkedNonRetryable() {
        var options = LlmActivityOptions.standard();

        assertThat(options.getRetryOptions().getDoNotRetry())
                .doesNotContain("com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException");
    }
}

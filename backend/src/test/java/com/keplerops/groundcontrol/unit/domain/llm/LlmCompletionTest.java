package com.keplerops.groundcontrol.unit.domain.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.llm.LlmCompletion;
import org.junit.jupiter.api.Test;

/**
 * A {@link LlmCompletion} carries the raw provider completion. Like {@link LlmCompletionRequest}, its
 * {@code toString()} must never print the completion text — only safe length/count metadata.
 */
class LlmCompletionTest {

    private static final String SENTINEL_COMPLETION = "sentinel-completion-CANARY-71a9be";

    @Test
    void toStringNeverContainsTheCompletionText() {
        var completion = new LlmCompletion(SENTINEL_COMPLETION, 42, 128);

        assertThat(completion.toString()).doesNotContain(SENTINEL_COMPLETION);
    }

    @Test
    void toStringReportsSafeMetadataOnly() {
        var completion = new LlmCompletion(SENTINEL_COMPLETION, 42, 128);

        assertThat(completion.toString())
                .contains(Integer.toString(SENTINEL_COMPLETION.length()))
                .contains("42")
                .contains("128");
    }

    @Test
    void rejectsNullText() {
        assertThatThrownBy(() -> new LlmCompletion(null, 1, 1)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsNegativeTokenCounts() {
        assertThatThrownBy(() -> new LlmCompletion("text", -1, 1)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new LlmCompletion("text", 1, -1)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void accessorsReturnConstructedValues() {
        var completion = new LlmCompletion(SENTINEL_COMPLETION, 42, 128);

        assertThat(completion.text()).isEqualTo(SENTINEL_COMPLETION);
        assertThat(completion.inputTokenCount()).isEqualTo(42);
        assertThat(completion.outputTokenCount()).isEqualTo(128);
    }
}

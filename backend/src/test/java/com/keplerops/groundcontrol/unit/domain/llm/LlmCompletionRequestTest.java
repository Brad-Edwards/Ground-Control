package com.keplerops.groundcontrol.unit.domain.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.llm.LlmCompletionRequest;
import org.junit.jupiter.api.Test;

/**
 * A {@link LlmCompletionRequest} carries the prompt sent to a provider. It must never be stringified
 * verbatim (ADR-028 redaction rule) — the generated record {@code toString()} would print the raw
 * prompt into any log line, exception message, or debugger dump that touches this object.
 */
class LlmCompletionRequestTest {

    private static final String SENTINEL_PROMPT = "sentinel-prompt-CANARY-8f3d2c";

    @Test
    void toStringNeverContainsThePrompt() {
        var request = new LlmCompletionRequest("claude-sonnet-5", SENTINEL_PROMPT, 1024);

        assertThat(request.toString()).doesNotContain(SENTINEL_PROMPT);
    }

    @Test
    void toStringReportsSafeMetadataOnly() {
        var request = new LlmCompletionRequest("claude-sonnet-5", SENTINEL_PROMPT, 1024);

        assertThat(request.toString())
                .contains("claude-sonnet-5")
                .contains(Integer.toString(SENTINEL_PROMPT.length()))
                .contains("1024");
    }

    @Test
    void rejectsBlankModelId() {
        assertThatThrownBy(() -> new LlmCompletionRequest(" ", "prompt", 100))
                .isInstanceOf(DomainValidationException.class);
    }

    /** The null half of the {@code modelId == null || modelId.isBlank()} guard, which blank input never reaches. */
    @Test
    void rejectsNullModelId() {
        assertThatThrownBy(() -> new LlmCompletionRequest(null, "prompt", 100))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsNullPrompt() {
        assertThatThrownBy(() -> new LlmCompletionRequest("claude-sonnet-5", null, 100))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsNonPositiveMaxOutputTokens() {
        assertThatThrownBy(() -> new LlmCompletionRequest("claude-sonnet-5", "prompt", 0))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void accessorsReturnConstructedValues() {
        var request = new LlmCompletionRequest("claude-sonnet-5", SENTINEL_PROMPT, 512);

        assertThat(request.modelId()).isEqualTo("claude-sonnet-5");
        assertThat(request.prompt()).isEqualTo(SENTINEL_PROMPT);
        assertThat(request.maxOutputTokens()).isEqualTo(512);
    }
}

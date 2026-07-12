package com.keplerops.groundcontrol.unit.infrastructure.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.infrastructure.llm.anthropic.AnthropicProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AnthropicPropertiesTest {

    private static final String SENTINEL_KEY = "sentinel-api-key-CANARY-3d81ff";

    @Test
    void toStringNeverContainsTheApiKey() {
        var props = new AnthropicProperties(
                true,
                "https://api.anthropic.com",
                SENTINEL_KEY,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                4096,
                1_000_000L);

        assertThat(props.toString()).doesNotContain(SENTINEL_KEY).contains("<redacted>");
    }

    @Test
    void toStringReportsUnsetWhenApiKeyIsBlank() {
        var props = new AnthropicProperties(
                false,
                "https://api.anthropic.com",
                "",
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                4096,
                1_000_000L);

        assertThat(props.toString()).contains("<unset>").doesNotContain("<redacted>");
    }

    @Test
    void defaultsBaseUrlWhenBlank() {
        var props = new AnthropicProperties(true, null, "key", null, null, 0, 0);

        assertThat(props.baseUrl()).isEqualTo("https://api.anthropic.com");
    }

    @Test
    void rejectsANonHttpsBaseUrl() {
        assertThatThrownBy(() -> new AnthropicProperties(true, "http://api.anthropic.com", "key", null, null, 0, 0))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void defaultsTimeoutsAndBoundsWhenUnset() {
        var props = new AnthropicProperties(true, null, "key", null, null, 0, 0);

        assertThat(props.connectTimeout()).isPositive();
        assertThat(props.readTimeout()).isPositive();
        assertThat(props.maxOutputTokens()).isPositive();
        assertThat(props.maxResponseBytes()).isPositive();
    }

    @Test
    void accessorsReturnConstructedValues() {
        var props = new AnthropicProperties(
                true,
                "https://api.anthropic.com",
                SENTINEL_KEY,
                Duration.ofSeconds(7),
                Duration.ofSeconds(42),
                2048,
                500_000L);

        assertThat(props.enabled()).isTrue();
        assertThat(props.baseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(props.apiKey()).isEqualTo(SENTINEL_KEY);
        assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(42));
        assertThat(props.maxOutputTokens()).isEqualTo(2048);
        assertThat(props.maxResponseBytes()).isEqualTo(500_000L);
    }
}

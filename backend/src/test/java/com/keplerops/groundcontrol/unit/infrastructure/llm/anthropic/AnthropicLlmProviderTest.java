package com.keplerops.groundcontrol.unit.infrastructure.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.llm.LlmCompletionRequest;
import com.keplerops.groundcontrol.infrastructure.llm.anthropic.AnthropicLlmProvider;
import com.keplerops.groundcontrol.infrastructure.llm.anthropic.AnthropicProperties;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Sentinel-based adapter test (preflight "Required Structural And Non-Leak Evidence"): asserts the
 * correct header/model request shape while proving sentinel prompt/completion/key values never appear
 * in thrown messages, the safe response object, or captured logs.
 */
class AnthropicLlmProviderTest {

    private static final String SENTINEL_PROMPT = "sentinel-prompt-CANARY-8f3d2c";
    private static final String SENTINEL_COMPLETION = "sentinel-completion-CANARY-71a9be";
    private static final String SENTINEL_KEY = "sentinel-api-key-CANARY-3d81ff";
    private static final String SENTINEL_ERROR_BODY = "sentinel-error-body-CANARY-b4e219";

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder().baseUrl("https://api.anthropic.com");
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(AnthropicLlmProvider.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(AnthropicLlmProvider.class)).detachAppender(logAppender);
    }

    /**
     * Build the {@link RestClient} the same way {@code AnthropicLlmConfiguration} does — MockRestServiceServer
     * binds its mock request factory in {@link #setUp()}, and nothing after that call touches
     * {@code requestFactory} again, so the mock stays wired all the way to {@code build()}.
     */
    private AnthropicLlmProvider provider() {
        return providerWithProperties(new AnthropicProperties(
                true,
                "https://api.anthropic.com",
                SENTINEL_KEY,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                4096,
                1_000_000L));
    }

    private AnthropicLlmProvider providerWithProperties(AnthropicProperties properties) {
        var restClient = restClientBuilder
                .defaultHeader("x-api-key", properties.apiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        return new AnthropicLlmProvider(properties, restClient);
    }

    @Test
    void providerIdIsCanonicalAnthropic() {
        assertThat(provider().providerId()).isEqualTo("anthropic");
    }

    @Test
    void completeSendsTheExpectedHeadersAndModelRequestShape() {
        var provider = provider();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", SENTINEL_KEY))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"))
                .andExpect(jsonPath("$.max_tokens").value(512))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value(SENTINEL_PROMPT))
                .andRespond(withSuccess(
                        """
                        {
                          "content": [{"type": "text", "text": "%s"}],
                          "stop_reason": "end_turn",
                          "usage": {"input_tokens": 12, "output_tokens": 34}
                        }
                        """
                                .formatted(SENTINEL_COMPLETION),
                        MediaType.APPLICATION_JSON));

        var completion = provider.complete(new LlmCompletionRequest("claude-sonnet-5", SENTINEL_PROMPT, 512));

        assertThat(completion.text()).isEqualTo(SENTINEL_COMPLETION);
        assertThat(completion.inputTokenCount()).isEqualTo(12);
        assertThat(completion.outputTokenCount()).isEqualTo(34);
        server.verify();
    }

    @Test
    void completeBoundsTheRequestedMaxOutputTokensToTheConfiguredCeiling() {
        var properties = new AnthropicProperties(
                true,
                "https://api.anthropic.com",
                SENTINEL_KEY,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                100,
                1_000_000L);
        var provider = providerWithProperties(properties);
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(jsonPath("$.max_tokens").value(100))
                .andRespond(withSuccess(
                        """
                        {"content": [{"type": "text", "text": "ok"}], "usage": {"input_tokens": 1, "output_tokens": 1}}
                        """,
                        MediaType.APPLICATION_JSON));

        provider.complete(new LlmCompletionRequest("claude-sonnet-5", "prompt", 5000));

        server.verify();
    }

    /**
     * The ceiling must bind the raw byte stream, not the decoded text. Enforcing it only after the body has
     * been buffered and deserialized means an oversized or hostile response has already consumed the memory
     * by the time the check runs, and a body whose bulk sits in ignored properties or non-text blocks
     * bypasses it entirely. This response is tiny in decoded text but far over the byte ceiling, so it only
     * fails if the bound is applied while reading (codex review, cycle 2).
     */
    @Test
    void completeEnforcesTheByteCeilingOnTheRawResponseBeforeDeserializing() {
        var properties = new AnthropicProperties(
                true,
                "https://api.anthropic.com",
                SENTINEL_KEY,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                4096,
                256L);
        var provider = providerWithProperties(properties);
        var padding = "x".repeat(4000);
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess(
                        """
                        {"ignored_bulk": "%s", "content": [{"type": "text", "text": "ok"}]}
                        """
                                .formatted(padding),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.complete(new LlmCompletionRequest("claude-sonnet-5", SENTINEL_PROMPT, 512)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("size bound");
    }

    /** An empty content array must fail closed, not yield an empty completion the caller would publish. */
    @Test
    void anEmptyContentArrayFailsClosed() {
        var provider = provider();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess("{\"content\": []}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.complete(new LlmCompletionRequest("claude-sonnet-5", SENTINEL_PROMPT, 512)))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    /** A response carrying only non-text blocks decodes to no text: fail closed rather than publish nothing. */
    @Test
    void aResponseWithOnlyNonTextContentBlocksFailsClosed() {
        var provider = provider();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess(
                        "{\"content\": [{\"type\": \"tool_use\", \"text\": null}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.complete(new LlmCompletionRequest("claude-sonnet-5", SENTINEL_PROMPT, 512)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void a4xxResponseMapsToDomainValidationExceptionWithoutLeakingTheBody() {
        var provider = provider();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body(SENTINEL_ERROR_BODY)
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.complete(new LlmCompletionRequest("claude-sonnet-5", SENTINEL_PROMPT, 512)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageNotContaining(SENTINEL_ERROR_BODY)
                .hasMessageNotContaining(SENTINEL_KEY);
        assertNoSentinelInLogs();
    }

    @Test
    void a429ResponseMapsToServiceUnavailableExceptionAsRetryable() {
        var provider = provider();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body(SENTINEL_ERROR_BODY)
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.complete(new LlmCompletionRequest("claude-sonnet-5", SENTINEL_PROMPT, 512)))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageNotContaining(SENTINEL_ERROR_BODY);
        assertNoSentinelInLogs();
    }

    @Test
    void a5xxResponseMapsToServiceUnavailableExceptionWithoutLeakingTheBody() {
        var provider = provider();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(SENTINEL_ERROR_BODY)
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.complete(new LlmCompletionRequest("claude-sonnet-5", SENTINEL_PROMPT, 512)))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageNotContaining(SENTINEL_ERROR_BODY);
        assertNoSentinelInLogs();
    }

    @Test
    void constructorRejectsAMissingCredentialWhenEnabled() {
        var properties = new AnthropicProperties(
                true, "https://api.anthropic.com", "", Duration.ofSeconds(5), Duration.ofSeconds(30), 4096, 1_000_000L);
        var restClient = restClientBuilder.build();

        assertThatThrownBy(() -> new AnthropicLlmProvider(properties, restClient))
                .isInstanceOf(DomainValidationException.class);
    }

    /** The null half of the credential guard: a missing key must fail closed, not NPE out of isBlank(). */
    @Test
    void constructorRejectsANullCredentialWhenEnabled() {
        var properties = new AnthropicProperties(
                true,
                "https://api.anthropic.com",
                null,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                4096,
                1_000_000L);
        var restClient = restClientBuilder.build();

        assertThatThrownBy(() -> new AnthropicLlmProvider(properties, restClient))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void completeNeverLogsThePromptOrCompletion() {
        var provider = provider();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess(
                        """
                        {"content": [{"type": "text", "text": "%s"}], "usage": {"input_tokens": 1, "output_tokens": 1}}
                        """
                                .formatted(SENTINEL_COMPLETION),
                        MediaType.APPLICATION_JSON));

        provider.complete(new LlmCompletionRequest("claude-sonnet-5", SENTINEL_PROMPT, 512));

        assertNoSentinelInLogs();
    }

    private void assertNoSentinelInLogs() {
        var messages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(messages)
                .noneMatch(m -> m.contains(SENTINEL_PROMPT)
                        || m.contains(SENTINEL_COMPLETION)
                        || m.contains(SENTINEL_KEY)
                        || m.contains(SENTINEL_ERROR_BODY));
    }
}

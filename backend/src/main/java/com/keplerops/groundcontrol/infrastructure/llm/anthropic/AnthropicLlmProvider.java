package com.keplerops.groundcontrol.infrastructure.llm.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.llm.LlmCompletion;
import com.keplerops.groundcontrol.domain.llm.LlmCompletionRequest;
import com.keplerops.groundcontrol.domain.llm.LlmProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Infrastructure adapter for the {@code anthropic} canonical provider (ADR-028 LLM provider
 * boundary). Calls Anthropic's Messages API over a pre-configured Spring {@link RestClient} (built by
 * {@link AnthropicLlmConfiguration} against an operator-configured HTTPS endpoint, with the
 * authorization header, bounded connect/read timeouts already applied) plus a bounded output-token /
 * response-size ceiling enforced here. Never logs or propagates raw {@link RestClientResponseException}
 * messages, provider error bodies, headers, or response objects — every failure maps to a stable,
 * redacted product error via the existing {@code DomainValidationException} /
 * {@code ServiceUnavailableException} categories (no parallel exception hierarchy).
 *
 * <p>Reuses {@link com.keplerops.groundcontrol.domain.requirements.service.EmbeddingProvider}'s
 * port/adapter direction only, not its shape: no {@code isAvailable()} fail-open probe, no
 * un-redacted secret-bearing properties. Taking an already-built {@link RestClient} (rather than
 * assembling one from a builder inside this class) keeps the adapter a plain, directly testable POJO —
 * a test can bind a mock request factory to the builder before {@code build()} without this class ever
 * touching (and so never able to silently overwrite) that wiring.
 */
public final class AnthropicLlmProvider implements LlmProvider {

    public static final String PROVIDER_ID = "anthropic";
    private static final String MESSAGES_PATH = "/v1/messages";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AnthropicProperties properties;
    private final RestClient restClient;

    public AnthropicLlmProvider(AnthropicProperties properties, RestClient restClient) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new DomainValidationException("Anthropic API key is not configured");
        }
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public LlmCompletion complete(LlmCompletionRequest request) {
        var boundedMaxTokens = Math.min(request.maxOutputTokens(), properties.maxOutputTokens());
        var body = new AnthropicRequest(
                request.modelId(), boundedMaxTokens, List.of(new AnthropicMessage("user", request.prompt())));

        AnthropicResponse response;
        try {
            // exchange(), not retrieve(): retrieve() buffers and deserializes the whole body before any
            // ceiling could be applied, so a hostile or malfunctioning provider could exhaust memory
            // regardless of maxResponseBytes. Bound the byte stream as it is read, THEN deserialize.
            response = restClient
                    .post()
                    .uri(MESSAGES_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, res) -> {
                        var status = res.getStatusCode();
                        if (status.isError()) {
                            // The error body is never read: provider error bodies routinely echo the
                            // request (and therefore the prompt) back.
                            throw mapStatus(status.value());
                        }
                        var bounded = readBounded(res.getBody(), properties.maxResponseBytes());
                        return OBJECT_MAPPER.readValue(bounded, AnthropicResponse.class);
                    });
        } catch (RestClientResponseException e) {
            throw mapStatus(e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            // Timeout or connection failure: retryable infrastructure failure. The underlying
            // exception (which may echo request/response fragments) is never propagated as the cause.
            throw new ServiceUnavailableException("Anthropic provider request failed: connection error");
        } catch (UncheckedIOException e) {
            throw new ServiceUnavailableException("Anthropic provider request failed: malformed response");
        }
        return toCompletion(response);
    }

    /**
     * Enforces the configured ceiling while reading, so an oversized body is abandoned mid-stream rather
     * than after it has already been buffered and deserialized.
     */
    private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        var buffer = new ByteArrayOutputStream();
        var chunk = new byte[8192];
        var total = 0L;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new DomainValidationException("Anthropic provider response exceeded the configured size bound");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * Maps a provider status to a stable, redacted product error. 429 is a rate limit: retryable despite
     * being a 4xx. Every other 4xx is a controlled, non-retryable rejection (bad credential, invalid model,
     * malformed request). The response body, headers, and exception message are never read into the product
     * error — they routinely echo the request, and therefore the prompt.
     */
    private static RuntimeException mapStatus(int status) {
        if (status == 429) {
            return new ServiceUnavailableException("Anthropic provider rate-limited the request");
        }
        if (status >= 400 && status < 500) {
            return new DomainValidationException("Anthropic provider rejected the request (status " + status + ")");
        }
        return new ServiceUnavailableException("Anthropic provider request failed (status " + status + ")");
    }

    private LlmCompletion toCompletion(AnthropicResponse response) {
        if (response == null || response.content() == null || response.content().isEmpty()) {
            throw new ServiceUnavailableException("Anthropic provider returned an empty response");
        }
        var text = response.content().stream()
                .filter(block -> "text".equals(block.type()))
                .map(AnthropicContentBlock::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
        if (text.isBlank()) {
            throw new DomainValidationException("Anthropic provider returned no text content");
        }
        // No second size check here: readBounded() already caps the raw response, and the decoded text is
        // always <= those bytes (JSON escaping only ever expands the wire form), so a post-decode ceiling
        // would be unreachable.
        var usage = response.usage();
        var inputTokens = usage == null ? 0 : usage.inputTokens();
        var outputTokens = usage == null ? 0 : usage.outputTokens();
        return new LlmCompletion(text, inputTokens, outputTokens);
    }

    private record AnthropicRequest(
            String model, @JsonProperty("max_tokens") int maxTokens, List<AnthropicMessage> messages) {}

    private record AnthropicMessage(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AnthropicResponse(
            List<AnthropicContentBlock> content,
            @JsonProperty("stop_reason") String stopReason,
            AnthropicUsage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AnthropicContentBlock(String type, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AnthropicUsage(
            @JsonProperty("input_tokens") int inputTokens, @JsonProperty("output_tokens") int outputTokens) {}
}

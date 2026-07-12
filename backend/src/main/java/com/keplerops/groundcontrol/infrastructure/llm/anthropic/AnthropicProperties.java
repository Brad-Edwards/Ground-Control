package com.keplerops.groundcontrol.infrastructure.llm.anthropic;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational adapter configuration for the Anthropic provider (ADR-028): base URL, credential,
 * connect/read timeouts, and response-size limits. This is operator-configured deployment capability,
 * deliberately separate from project routing policy (which stage/tier selects which provider/model,
 * owned by {@code .ground-control.yaml} via {@code TrustedRouteResolver}) — it grows no per-project
 * stage/model table.
 *
 * <p>Strict binding ({@code ignoreUnknownFields = false}), matching the codebase's
 * {@code @ConfigurationProperties} convention. Unlike {@code EmbeddingProperties} (a plain record with
 * no redaction), this record's {@link #toString()} is explicitly redacted so the API key can never
 * leak through a bind-failure diagnostic, a log line, or a debugger dump (ADR-028 redaction rule).
 */
@ConfigurationProperties(prefix = "groundcontrol.llm.anthropic", ignoreUnknownFields = false)
public record AnthropicProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout,
        int maxOutputTokens,
        long maxResponseBytes) {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(120);
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 1_000_000L;

    public AnthropicProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        if (!baseUrl.startsWith("https://")) {
            throw new DomainValidationException("groundcontrol.llm.anthropic.base-url must be an HTTPS endpoint");
        }
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        readTimeout = readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout;
        maxOutputTokens = maxOutputTokens <= 0 ? DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens;
        maxResponseBytes = maxResponseBytes <= 0 ? DEFAULT_MAX_RESPONSE_BYTES : maxResponseBytes;
    }

    @Override
    public String toString() {
        return "AnthropicProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", apiKey="
                + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<redacted>") + ", connectTimeout="
                + connectTimeout + ", readTimeout=" + readTimeout + ", maxOutputTokens=" + maxOutputTokens
                + ", maxResponseBytes=" + maxResponseBytes + "]";
    }
}

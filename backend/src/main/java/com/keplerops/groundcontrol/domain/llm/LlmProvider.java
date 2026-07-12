package com.keplerops.groundcontrol.domain.llm;

/**
 * Domain port for an LLM provider adapter (ADR-028 LLM provider boundary). Mirrors the direction of
 * {@link com.keplerops.groundcontrol.domain.requirements.service.EmbeddingProvider} (a narrow port with
 * one infrastructure adapter per provider) without copying its shape: no {@code isAvailable()}
 * fail-open probe, no model getter, no secret-bearing properties record crossing the port.
 *
 * <p>Implementations own credentials, endpoints, and the provider wire protocol; they never receive
 * them through this interface. A production implementation maps provider failures onto
 * {@link com.keplerops.groundcontrol.domain.exception.DomainValidationException} (non-retryable:
 * unknown provider, invalid model, missing credential, provider 4xx) or
 * {@link com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException} (retryable: timeout,
 * connection failure, 429, eligible 5xx) — never a raw provider exception or response body.
 */
public interface LlmProvider {

    /** Invoke the provider with a bounded, provider-neutral request and return its completion. */
    LlmCompletion complete(LlmCompletionRequest request);

    /** The stable canonical provider id this instance serves (for example {@code "anthropic"}). */
    String providerId();
}

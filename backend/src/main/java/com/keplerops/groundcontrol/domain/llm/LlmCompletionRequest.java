package com.keplerops.groundcontrol.domain.llm;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;

/**
 * Provider-neutral, in-memory-only request to {@link LlmProvider#complete(LlmCompletionRequest)}
 * (ADR-028 LLM provider boundary). Carries only the canonical model id for this invocation, the
 * activity-owned prompt content, and a bounded generation policy (maximum output tokens) — never
 * credentials, endpoints, Temporal context, project routing rules, provider-native request objects, or
 * an open option map.
 *
 * <p>This type is never a JPA entity, a Temporal/REST DTO, or Jackson-serialized into any durable
 * surface. Its {@link #toString()} is deliberately redacted: a generated record {@code toString()}
 * would print the raw prompt into any log line, exception message, or debugger dump that touches this
 * object, which is exactly the leak ADR-028's redaction rule forbids.
 */
public record LlmCompletionRequest(String modelId, String prompt, int maxOutputTokens) {

    public LlmCompletionRequest {
        if (modelId == null || modelId.isBlank()) {
            throw new DomainValidationException("modelId is required");
        }
        if (prompt == null) {
            throw new DomainValidationException("prompt is required");
        }
        if (maxOutputTokens <= 0) {
            throw new DomainValidationException("maxOutputTokens must be positive");
        }
    }

    @Override
    public String toString() {
        return "LlmCompletionRequest[modelId=" + modelId + ", promptLength=" + prompt.length() + ", maxOutputTokens="
                + maxOutputTokens + "]";
    }
}

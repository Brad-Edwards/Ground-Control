package com.keplerops.groundcontrol.domain.llm;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;

/**
 * Provider-neutral, in-memory-only result of {@link LlmProvider#complete(LlmCompletionRequest)}
 * (ADR-028 LLM provider boundary). Carries the completion text and bounded token-count economics —
 * never a raw provider response object, headers, or bearer material.
 *
 * <p>Kept in process by the activity that requested it and handed to {@link PlanPublicationPort};
 * never a Temporal input/output, JPA entity, or REST/MCP response field. Its {@link #toString()} is
 * deliberately redacted so the completion text can never leak through a log line, exception message,
 * or debugger dump (ADR-028 redaction rule).
 */
public record LlmCompletion(String text, int inputTokenCount, int outputTokenCount) {

    public LlmCompletion {
        if (text == null) {
            throw new DomainValidationException("text is required");
        }
        if (inputTokenCount < 0 || outputTokenCount < 0) {
            throw new DomainValidationException("token counts must not be negative");
        }
    }

    @Override
    public String toString() {
        return "LlmCompletion[length=" + text.length() + ", inputTokenCount=" + inputTokenCount + ", outputTokenCount="
                + outputTokenCount + "]";
    }
}

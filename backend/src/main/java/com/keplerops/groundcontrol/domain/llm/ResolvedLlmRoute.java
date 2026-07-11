package com.keplerops.groundcontrol.domain.llm;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;

/**
 * The closed, safe scalar set produced by {@link TrustedRouteResolver} (ADR-028 LLM provider
 * boundary). This is the only LLM-related shape allowed to cross a durable boundary (Temporal history,
 * logs): contract version, project, stage, tier, canonical provider id, canonical model id, and a
 * configuration digest. It carries no endpoint, credential reference/value, prompt template/body,
 * completion, repository content, or provider-native options — so, unlike {@link LlmCompletionRequest}
 * and {@link LlmCompletion}, its generated {@code toString()} is safe to use unmodified.
 */
public record ResolvedLlmRoute(
        String contractVersion,
        String project,
        String stage,
        String tier,
        String providerId,
        String modelId,
        String configDigest) {

    public ResolvedLlmRoute {
        if (isBlank(contractVersion)) {
            throw new DomainValidationException("contractVersion is required");
        }
        if (isBlank(project)) {
            throw new DomainValidationException("project is required");
        }
        if (isBlank(stage)) {
            throw new DomainValidationException("stage is required");
        }
        if (isBlank(tier)) {
            throw new DomainValidationException("tier is required");
        }
        if (isBlank(providerId)) {
            throw new DomainValidationException("providerId is required");
        }
        if (isBlank(modelId)) {
            throw new DomainValidationException("modelId is required");
        }
        if (isBlank(configDigest)) {
            throw new DomainValidationException("configDigest is required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

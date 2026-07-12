package com.keplerops.groundcontrol.infrastructure.llm;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.llm.LlmProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Small classpath registry of {@link LlmProvider} beans keyed by canonical provider id, modeled on
 * {@code PackTypeHandlerRegistry} (ADR-023/ADR-028): every {@link LlmProvider} bean on the classpath
 * is collected by Spring and registered; a duplicate provider id fails fast at construction; an
 * unknown provider id fails closed at lookup with {@link DomainValidationException}. This is not a
 * {@code PluginRegistry} entry, a dynamic code loader, or a generic {@code execute(Map)} framework.
 *
 * <p>A plain {@code @Component} (not {@code @Service}) so it stays in {@code infrastructure/llm/} per
 * the ADR-028 boundary rather than being pulled into a {@code service} subpackage.
 */
@Component
public final class LlmProviderRegistry {

    private final Map<String, LlmProvider> providersById;

    public LlmProviderRegistry(List<LlmProvider> providers) {
        this.providersById = new LinkedHashMap<>();
        for (var provider : providers) {
            var existing = providersById.put(provider.providerId(), provider);
            if (existing != null) {
                throw new IllegalStateException("Duplicate LLM provider registered for id " + provider.providerId());
            }
        }
    }

    /** Look up the provider for {@code providerId}; fails closed on an unknown id. */
    public LlmProvider get(String providerId) {
        var provider = providersById.get(providerId);
        if (provider == null) {
            throw new DomainValidationException("No LLM provider registered for id " + providerId);
        }
        return provider;
    }
}

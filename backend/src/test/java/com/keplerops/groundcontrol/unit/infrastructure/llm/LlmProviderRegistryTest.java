package com.keplerops.groundcontrol.unit.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.llm.LlmCompletion;
import com.keplerops.groundcontrol.domain.llm.LlmCompletionRequest;
import com.keplerops.groundcontrol.domain.llm.LlmProvider;
import com.keplerops.groundcontrol.infrastructure.llm.LlmProviderRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Modeled on {@code PackTypeHandlerRegistryTest}'s duplicate-registration and fail-closed-lookup
 * discipline: a classpath registry keyed by canonical provider id.
 */
class LlmProviderRegistryTest {

    /**
     * Two providers, not one. With a single entry, an implementation that ignored the id and returned the
     * sole registered provider would pass — and would then silently route every request to the wrong
     * provider the moment a second adapter (OpenAI, Ollama) is registered. Keying is the registry's entire
     * job (ADR-028), so the test has to be able to observe a mis-key.
     */
    @Test
    void getSelectsTheProviderMatchingTheRequestedIdAndNotSomeOtherRegisteredProvider() {
        var anthropic = new FakeProvider("anthropic");
        var other = new FakeProvider("other");
        var registry = new LlmProviderRegistry(List.of(anthropic, other));

        assertThat(registry.get("anthropic")).isSameAs(anthropic).isNotSameAs(other);
        assertThat(registry.get("other")).isSameAs(other).isNotSameAs(anthropic);
    }

    @Test
    void getFailsClosedForAnUnknownProviderId() {
        var registry = new LlmProviderRegistry(List.of(new FakeProvider("anthropic")));

        assertThatThrownBy(() -> registry.get("openai")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void constructionFailsFastOnDuplicateProviderRegistration() {
        var first = new FakeProvider("anthropic");
        var second = new FakeProvider("anthropic");

        assertThatThrownBy(() -> new LlmProviderRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anthropic");
    }

    @Test
    void emptyRegistryFailsClosedForAnyLookup() {
        var registry = new LlmProviderRegistry(List.of());

        assertThatThrownBy(() -> registry.get("anthropic")).isInstanceOf(DomainValidationException.class);
    }

    private static final class FakeProvider implements LlmProvider {
        private final String providerId;

        FakeProvider(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public LlmCompletion complete(LlmCompletionRequest request) {
            return new LlmCompletion("fake", 1, 1);
        }

        @Override
        public String providerId() {
            return providerId;
        }
    }
}

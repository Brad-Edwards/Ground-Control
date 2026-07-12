package com.keplerops.groundcontrol.infrastructure.llm.anthropic;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@code anthropic} provider adapter: builds the operator-configured {@link RestClient}
 * (HTTPS base URL, {@code x-api-key}/{@code anthropic-version} headers, bounded connect/read
 * timeouts) and registers {@link AnthropicLlmProvider} as a bean so
 * {@code LlmProviderRegistry}'s classpath collection picks it up. Only active when
 * {@code groundcontrol.llm.anthropic.enabled=true}, so a deployment without the credential boots
 * without the bean — {@code LlmProviderRegistry} then fails closed on lookup rather than the
 * application failing to start.
 */
@Configuration
@EnableConfigurationProperties(AnthropicProperties.class)
@ConditionalOnProperty(name = "groundcontrol.llm.anthropic.enabled", havingValue = "true")
public class AnthropicLlmConfiguration {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Bean
    AnthropicLlmProvider anthropicLlmProvider(AnthropicProperties properties, RestClient.Builder restClientBuilder) {
        var requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(properties.connectTimeout())
                        .withReadTimeout(properties.readTimeout()));
        var restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("x-api-key", properties.apiKey())
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .build();
        return new AnthropicLlmProvider(properties, restClient);
    }
}

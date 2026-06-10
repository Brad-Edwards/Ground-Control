package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionAdapter;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionOutputSchema;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionRateLimit;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionRequest;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionResult;
import com.keplerops.groundcontrol.domain.evidence.service.EvidenceCollectionAdapterRegistry;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.plugins.service.PluginDescriptor;
import com.keplerops.groundcontrol.domain.plugins.service.PluginInfo;
import com.keplerops.groundcontrol.domain.plugins.service.PluginRegistry;
import com.keplerops.groundcontrol.domain.plugins.state.PluginLifecycleState;
import com.keplerops.groundcontrol.domain.plugins.state.PluginType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceCollectionAdapterRegistryTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000210");
    private static final Instant NOW = Instant.parse("2026-06-07T08:00:00Z");

    @Mock
    private PluginRegistry pluginRegistry;

    private StubAdapter builtinAdapter;
    private EvidenceCollectionAdapterRegistry registry;

    @BeforeEach
    void setUp() {
        builtinAdapter = new StubAdapter("aws-iam-evidence", PluginType.EVIDENCE_COLLECTOR, true);
        registry = new EvidenceCollectionAdapterRegistry(List.of(builtinAdapter), pluginRegistry);
    }

    @Nested
    class Listing {

        @Test
        void listsClasspathAndDynamicEvidenceCollectors() {
            var dynamic = pluginInfo("dynamic-cmdb-evidence", PluginType.EVIDENCE_COLLECTOR, false);
            when(pluginRegistry.listByType(PluginType.EVIDENCE_COLLECTOR)).thenReturn(List.of(dynamic));

            var adapters = registry.listAdapters();

            assertThat(adapters)
                    .extracting(PluginInfo::name)
                    .containsExactly("aws-iam-evidence", "dynamic-cmdb-evidence");
            assertThat(adapters).extracting(PluginInfo::type).containsOnly(PluginType.EVIDENCE_COLLECTOR);
        }

        @Test
        void projectListingFiltersDynamicMetadataByEvidenceCollectorType() {
            var evidence = pluginInfo("project-iam-evidence", PluginType.EVIDENCE_COLLECTOR, false);
            var validator = pluginInfo("validator-plugin", PluginType.VALIDATOR, false);
            when(pluginRegistry.listPlugins(PROJECT_ID)).thenReturn(List.of(evidence, validator));

            var adapters = registry.listAdapters(PROJECT_ID);

            assertThat(adapters)
                    .extracting(PluginInfo::name)
                    .containsExactly("aws-iam-evidence", "project-iam-evidence");
            assertThat(adapters).noneMatch(info -> info.name().equals("validator-plugin"));
        }

        @Test
        void marksUnavailableClasspathAdaptersAsFailed() {
            var unavailable = new StubAdapter("unavailable-iam-evidence", PluginType.EVIDENCE_COLLECTOR, false);
            registry = new EvidenceCollectionAdapterRegistry(List.of(unavailable), pluginRegistry);
            when(pluginRegistry.listByType(PluginType.EVIDENCE_COLLECTOR)).thenReturn(List.of());

            var adapters = registry.listAdapters();

            assertThat(adapters).singleElement().satisfies(info -> {
                assertThat(info.name()).isEqualTo("unavailable-iam-evidence");
                assertThat(info.available()).isFalse();
                assertThat(info.state()).isEqualTo(PluginLifecycleState.FAILED);
            });
        }
    }

    @Nested
    class InvocationLookup {

        @Test
        void returnsClasspathAdapterForInvocation() {
            var adapter = registry.getAdapter("aws-iam-evidence");

            assertThat(adapter).isSameAs(builtinAdapter);
            assertThat(adapter.descriptor().type()).isEqualTo(PluginType.EVIDENCE_COLLECTOR);
        }

        @Test
        void rejectsMissingInvocationAdapter() {
            assertThatThrownBy(() -> registry.getAdapter("dynamic-only"))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Evidence collection adapter not found");
        }
    }

    private static PluginInfo pluginInfo(String name, PluginType type, boolean builtin) {
        return new PluginInfo(
                name,
                "1.0.0",
                "Test plugin",
                type,
                Set.of("evidence"),
                Map.of(),
                PluginLifecycleState.STARTED,
                true,
                builtin);
    }

    static class StubAdapter implements EvidenceCollectionAdapter {

        private final String name;
        private final PluginType type;
        private final boolean available;

        StubAdapter(String name, PluginType type, boolean available) {
            this.name = name;
            this.type = type;
            this.available = available;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(name, "1.0.0", "Stub adapter", type, Set.of("evidence"), Map.of());
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public EvidenceCollectionOutputSchema outputSchema() {
            return new EvidenceCollectionOutputSchema(
                    "stub-schema", "1.0.0", EvidenceType.OBSERVATION_SUMMARY, Map.of());
        }

        @Override
        public EvidenceCollectionRateLimit rateLimitPolicy() {
            return new EvidenceCollectionRateLimit(10, Duration.ofMinutes(1), 10, NOW.plusSeconds(60));
        }

        @Override
        public EvidenceCollectionResult collect(EvidenceCollectionRequest request) {
            throw new UnsupportedOperationException("not needed for registry test");
        }
    }
}

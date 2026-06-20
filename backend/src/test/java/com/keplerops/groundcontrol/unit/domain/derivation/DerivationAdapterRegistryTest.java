package com.keplerops.groundcontrol.unit.domain.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapter;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterDescriptor;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRegistry;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterResult;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationScope;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DerivationAdapterRegistryTest {

    private static final Instant NOW = Instant.parse("2026-06-13T10:00:00Z");
    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";

    @Test
    void routesApplicableAvailableAdapterAndCapturesUnsupportedPairs() {
        var registry = new DerivationAdapterRegistry(
                List.of(new StubAdapter("stub-java-app", true, Set.of("java"), Set.of("application"))));
        var scope = new DerivationScope(
                DerivationScopeMode.FULL_REPO,
                COMMIT,
                null,
                List.of(),
                Set.of("java", "terraform"),
                Set.of("application", "iac"));

        var plan = registry.route(scope, NOW);

        assertThat(plan.adapters())
                .extracting(adapter -> adapter.descriptor().adapterId())
                .containsExactly("stub-java-app");
        assertThat(plan.captureLimits()).hasSize(3);
        assertThat(plan.captureLimits()).extracting("reason").contains(CaptureLimitReason.UNSUPPORTED_LANGUAGE);
        assertThat(plan.captureLimits()).anySatisfy(limit -> {
            assertThat(limit.reason()).isEqualTo(CaptureLimitReason.UNSUPPORTED_SURFACE);
            assertThat(limit.language()).isEqualTo("java");
            assertThat(limit.surface()).isEqualTo("iac");
            assertThat(limit.commitSha()).isEqualTo(COMMIT);
        });
    }

    @Test
    void recordsToolUnavailableWhenOnlyMatchingAdapterIsUnavailable() {
        var registry = new DerivationAdapterRegistry(
                List.of(new StubAdapter("stub-java-app", false, Set.of("java"), Set.of("application"))));
        var scope = new DerivationScope(
                DerivationScopeMode.FULL_REPO, COMMIT, null, List.of(), Set.of("java"), Set.of("application"));

        var plan = registry.route(scope, NOW);

        assertThat(plan.adapters()).isEmpty();
        assertThat(plan.captureLimits()).singleElement().satisfies(limit -> {
            assertThat(limit.adapterId()).isEqualTo("stub-java-app");
            assertThat(limit.reason()).isEqualTo(CaptureLimitReason.TOOL_UNAVAILABLE);
            assertThat(limit.capturedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void recordsScopeUnsupportedWhenPairExistsButModeDoesNot() {
        var registry = new DerivationAdapterRegistry(List.of(new StubAdapter(
                "stub-java-app", true, Set.of("java"), Set.of("application"), Set.of(DerivationScopeMode.FULL_REPO))));
        var scope = new DerivationScope(
                DerivationScopeMode.PATH_SET,
                COMMIT,
                null,
                List.of("src/App.java"),
                Set.of("java"),
                Set.of("application"));

        var plan = registry.route(scope, NOW);

        assertThat(plan.adapters()).isEmpty();
        assertThat(plan.captureLimits()).singleElement().satisfies(limit -> {
            assertThat(limit.reason()).isEqualTo(CaptureLimitReason.SCOPE_UNSUPPORTED);
            assertThat(limit.language()).isEqualTo("java");
            assertThat(limit.surface()).isEqualTo("application");
        });
    }

    static class StubAdapter implements DerivationAdapter {

        private final DerivationAdapterDescriptor descriptor;
        private final boolean available;

        StubAdapter(String adapterId, boolean available, Set<String> languages, Set<String> surfaces) {
            this(adapterId, available, languages, surfaces, Set.of(DerivationScopeMode.FULL_REPO));
        }

        StubAdapter(
                String adapterId,
                boolean available,
                Set<String> languages,
                Set<String> surfaces,
                Set<DerivationScopeMode> scopeModes) {
            this.descriptor = new DerivationAdapterDescriptor(
                    adapterId,
                    "stub-tool",
                    "1.0.0",
                    "stub-rules",
                    "2026.06",
                    languages,
                    surfaces,
                    scopeModes,
                    Set.of(SystemModelFactKind.COMPONENT));
            this.available = available;
        }

        @Override
        public DerivationAdapterDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public DerivationAdapterResult derive(DerivationAdapterRequest request) {
            return new DerivationAdapterResult(List.of(), List.of());
        }
    }
}

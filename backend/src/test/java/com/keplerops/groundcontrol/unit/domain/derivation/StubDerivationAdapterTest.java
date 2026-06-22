package com.keplerops.groundcontrol.unit.domain.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationScope;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import com.keplerops.groundcontrol.infrastructure.derivation.StubDerivationAdapter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubDerivationAdapterTest {

    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";

    @Test
    void descriptorDeclaresTheSupportedTestDerivationShape() {
        var descriptor = new StubDerivationAdapter().descriptor();

        assertThat(descriptor.adapterId()).isEqualTo("stub-static-derivation");
        assertThat(descriptor.toolName()).isEqualTo("stub-deriver");
        assertThat(descriptor.languages()).containsExactly("java");
        assertThat(descriptor.surfaces()).containsExactly("application");
        assertThat(descriptor.scopeModes())
                .containsExactlyInAnyOrder(
                        DerivationScopeMode.FULL_REPO, DerivationScopeMode.DIFF, DerivationScopeMode.PATH_SET);
        assertThat(descriptor.factKinds())
                .containsExactlyInAnyOrder(SystemModelFactKind.COMPONENT, SystemModelFactKind.ENTRY_POINT);
    }

    @Test
    void deriveReturnsMetadataOnlyFactsForRequestedScope() {
        var adapter = new StubDerivationAdapter();
        var scope = new DerivationScope(
                DerivationScopeMode.PATH_SET,
                COMMIT,
                null,
                List.of("backend/src/main/java/App.java"),
                Set.of("java"),
                Set.of("application"));

        var result = adapter.derive(new DerivationAdapterRequest(UUID.randomUUID(), "gc-test", scope));

        assertThat(adapter.isAvailable()).isTrue();
        assertThat(result.captureLimits()).isEmpty();
        assertThat(result.facts()).hasSize(2);
        assertThat(result.facts())
                .extracting("factKind")
                .containsExactly(SystemModelFactKind.COMPONENT, SystemModelFactKind.ENTRY_POINT);
        assertThat(result.facts()).allSatisfy(fact -> {
            assertThat(fact.factKey()).contains("gc-test");
            assertThat(fact.sourcePath()).isEqualTo("backend/src/main/java/App.java");
            assertThat(fact.payload()).doesNotContainKeys("raw_output", "stderr", "source_content");
            assertThat(fact.provenance().adapterId()).isEqualTo("stub-static-derivation");
            assertThat(fact.provenance().commitSha()).isEqualTo(COMMIT);
            assertThat(fact.provenance().derivedAt()).isNotNull();
        });
    }

    @Test
    void deriveLeavesSourcePathEmptyForFullRepositoryScope() {
        var scope = new DerivationScope(
                DerivationScopeMode.FULL_REPO, COMMIT, null, List.of(), Set.of("java"), Set.of("application"));

        var result =
                new StubDerivationAdapter().derive(new DerivationAdapterRequest(UUID.randomUUID(), "gc-full", scope));

        assertThat(result.facts()).hasSize(2).allSatisfy(fact -> assertThat(fact.sourcePath())
                .isNull());
    }
}

package com.keplerops.groundcontrol.domain.packregistry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.packregistry.model.PackDependency;
import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.repository.PackRegistryEntryRepository;
import com.keplerops.groundcontrol.domain.packregistry.state.CatalogStatus;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PackResolverSemverTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final PackRegistryEntryRepository registryRepository = mock(PackRegistryEntryRepository.class);
    private final PackResolver resolver = new PackResolver(registryRepository, "0.109.0");

    @Test
    void resolveLatestCompatibleSelectsNewestCompatibleVersion() {
        var incompatible = entry("pack", "3.0.0");
        incompatible.setCompatibility(Map.of("minVersion", "99.0.0"));
        var compatible = entry("pack", "2.0.0");
        compatible.setCompatibility(Map.of("minVersion", "0.1.0", "maxVersion", "1.0.0"));

        when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                        PROJECT_ID, "pack", CatalogStatus.AVAILABLE))
                .thenReturn(List.of(incompatible, compatible));

        var resolved = resolver.resolveLatestCompatible(PROJECT_ID, "pack");

        assertThat(resolved.entry()).isSameAs(compatible);
        assertThat(resolved.resolvedVersion()).isEqualTo("2.0.0");
    }

    @Test
    void resolveLatestCompatibleRejectsMissingAndIncompatibleSets() {
        when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                        PROJECT_ID, "missing", CatalogStatus.AVAILABLE))
                .thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveLatestCompatible(PROJECT_ID, "missing"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("No available versions");

        var incompatible = entry("pack", "3.0.0");
        incompatible.setCompatibility(Map.of("minVersion", "99.0.0"));
        when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                        PROJECT_ID, "pack", CatalogStatus.AVAILABLE))
                .thenReturn(List.of(incompatible));

        assertThatThrownBy(() -> resolver.resolveLatestCompatible(PROJECT_ID, "pack"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("No compatible versions");
    }

    @Test
    void compareSemverHonorsStableAndPrereleaseOrderingRules() {
        assertThat(PackResolver.compareSemver("1.0.0", "1.0.0-rc.1")).isPositive();
        assertThat(PackResolver.compareSemver("1.0.0-alpha.2", "1.0.0-alpha.10"))
                .isNegative();
        assertThat(PackResolver.compareSemver("1.0.0-alpha.1", "1.0.0-alpha.beta"))
                .isNegative();
        assertThat(PackResolver.compareSemver("1.0.0-alpha.beta", "1.0.0-alpha.1"))
                .isPositive();
        assertThat(PackResolver.compareSemver("1.0.0-alpha", "1.0.0-alpha.1")).isNegative();
    }

    @Test
    void matchesConstraintSupportsExactRangeCaretAndTilde() {
        assertThat(PackResolver.matchesConstraint("1.2.3", "1.2.3")).isTrue();
        assertThat(PackResolver.matchesConstraint("1.2.3", ">=1.2.0")).isTrue();
        assertThat(PackResolver.matchesConstraint("1.2.3", "<=1.2.0")).isFalse();
        assertThat(PackResolver.matchesConstraint("1.2.3", "^1.0.0")).isTrue();
        assertThat(PackResolver.matchesConstraint("2.0.0", "^1.0.0")).isFalse();
        assertThat(PackResolver.matchesConstraint("1.2.3", "~1.2.0")).isTrue();
        assertThat(PackResolver.matchesConstraint("1.3.0", "~1.2.0")).isFalse();
    }

    @Test
    void parseSemverRejectsInvalidVersions() {
        assertThatThrownBy(() -> PackResolver.parseSemver("not-a-version"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Invalid semantic version");
    }

    @Test
    void resolveDependenciesRejectsInvalidDependencyShapesAndDepth() {
        var missingPackId = entry("root", "1.0.0");
        missingPackId.setDependencies(List.of(new PackDependency(" ", null)));

        assertThatThrownBy(
                        () -> resolver.resolveDependencies(PROJECT_ID, missingPackId, new HashSet<>(Set.of("root")), 0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("missing packId");

        var tooDeep = entry("root", "1.0.0");
        tooDeep.setDependencies(List.of(new PackDependency("dep", null)));

        assertThatThrownBy(() -> resolver.resolveDependencies(PROJECT_ID, tooDeep, new HashSet<>(Set.of("root")), 11))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Dependency depth exceeds maximum");
    }

    @Test
    void resolveDependenciesRejectsMissingDependencyVersionsAndUnmatchedConstraints() {
        var root = entry("root", "1.0.0");
        root.setDependencies(List.of(new PackDependency("dep", null)));

        when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                        PROJECT_ID, "dep", CatalogStatus.AVAILABLE))
                .thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveDependencies(PROJECT_ID, root, new HashSet<>(Set.of("root")), 0))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Dependency pack 'dep' not found");

        root.setDependencies(List.of(new PackDependency("dep", "^2.0.0")));
        when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                        PROJECT_ID, "dep", CatalogStatus.AVAILABLE))
                .thenReturn(List.of(entry("dep", "1.0.0")));

        assertThatThrownBy(() -> resolver.resolveDependencies(PROJECT_ID, root, new HashSet<>(Set.of("root")), 0))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("No version matching '^2.0.0'");
    }

    @Test
    void checkCompatibilityAcceptsNullDependencyList() {
        var resolved = new ResolvedPack(entry("pack", "1.0.0"), "1.0.0", null, null, null);

        assertThat(resolver.checkCompatibility(resolved)).isTrue();
    }

    private PackRegistryEntry entry(String packId, String version) {
        return new PackRegistryEntry(new Project("ground-control", "Ground Control"), packId, PackType.CUSTOM, version);
    }
}

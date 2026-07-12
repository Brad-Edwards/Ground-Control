package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.packregistry.model.PackDependency;
import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.repository.PackRegistryEntryRepository;
import com.keplerops.groundcontrol.domain.packregistry.service.PackResolver;
import com.keplerops.groundcontrol.domain.packregistry.state.CatalogStatus;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackResolverTest {

    @Mock
    private PackRegistryEntryRepository registryRepository;

    private PackResolver resolver;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Project makeProject() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        return project;
    }

    private PackDependency dependency(String packId) {
        return new PackDependency(packId, null);
    }

    private PackDependency dependency(String packId, String versionConstraint) {
        return new PackDependency(packId, versionConstraint);
    }

    @BeforeEach
    void setUp() {
        resolver = new PackResolver(registryRepository, "0.109.0");
    }

    @Nested
    class Resolve {

        @Test
        void selectsExactVersion() {
            var project = makeProject();
            var entry = new PackRegistryEntry(project, "nist-800-53", PackType.CUSTOM, "1.0.0");
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "nist-800-53", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(entry));

            var result = resolver.resolve(PROJECT_ID, "nist-800-53", "1.0.0");
            assertThat(result.resolvedVersion()).isEqualTo("1.0.0");
            assertThat(result.entry()).isSameAs(entry);
        }

        @Test
        void selectsLatestWhenNoConstraint() {
            var project = makeProject();
            var v1 = new PackRegistryEntry(project, "nist-800-53", PackType.CUSTOM, "1.0.0");
            var v2 = new PackRegistryEntry(project, "nist-800-53", PackType.CUSTOM, "2.0.0");
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "nist-800-53", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(v2, v1));

            var result = resolver.resolve(PROJECT_ID, "nist-800-53", null);
            assertThat(result.resolvedVersion()).isEqualTo("2.0.0");
        }

        @Test
        void throwsNotFoundWhenNoAvailableVersions() {
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "missing", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> resolver.resolve(PROJECT_ID, "missing", null))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundWhenNoVersionMatchesConstraint() {
            var project = makeProject();
            var entry = new PackRegistryEntry(project, "nist-800-53", PackType.CUSTOM, "1.0.0");
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "nist-800-53", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(entry));

            assertThatThrownBy(() -> resolver.resolve(PROJECT_ID, "nist-800-53", "2.0.0"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void resolvesWithCaretConstraint() {
            var project = makeProject();
            var v100 = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.0.0");
            var v110 = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.1.0");
            var v200 = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "2.0.0");
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "pack", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(v200, v110, v100));

            var result = resolver.resolve(PROJECT_ID, "pack", "^1.0.0");
            assertThat(result.resolvedVersion()).isEqualTo("1.1.0");
        }

        @Test
        void resolvesWithTildeConstraint() {
            var project = makeProject();
            var v100 = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.0.0");
            var v101 = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.0.1");
            var v110 = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.1.0");
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "pack", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(v110, v101, v100));

            var result = resolver.resolve(PROJECT_ID, "pack", "~1.0.0");
            assertThat(result.resolvedVersion()).isEqualTo("1.0.1");
        }
    }

    @Nested
    class Compatibility {

        @Test
        void returnsTrueWhenNoConstraints() {
            var project = makeProject();
            var entry = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.0.0");
            var resolved = new com.keplerops.groundcontrol.domain.packregistry.service.ResolvedPack(
                    entry, "1.0.0", null, null, List.of());

            assertThat(resolver.checkCompatibility(resolved)).isTrue();
        }

        @Test
        void returnsTrueWhenCompatible() {
            var project = makeProject();
            var entry = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.0.0");
            entry.setCompatibility(Map.of("minVersion", "0.0.1", "maxVersion", "99.0.0"));
            var resolved = new com.keplerops.groundcontrol.domain.packregistry.service.ResolvedPack(
                    entry, "1.0.0", null, null, List.of());

            assertThat(resolver.checkCompatibility(resolved)).isTrue();
        }

        @Test
        void returnsFalseWhenIncompatible() {
            var project = makeProject();
            var entry = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.0.0");
            entry.setCompatibility(Map.of("minVersion", "99.0.0"));
            var resolved = new com.keplerops.groundcontrol.domain.packregistry.service.ResolvedPack(
                    entry, "1.0.0", null, null, List.of());

            assertThat(resolver.checkCompatibility(resolved)).isFalse();
        }

        @Test
        void returnsFalseWhenDependencyIsIncompatible() {
            var project = makeProject();
            var entry = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.0.0");
            var dependency = new PackRegistryEntry(project, "dep", PackType.CUSTOM, "1.0.0");
            dependency.setCompatibility(Map.of("minVersion", "99.0.0"));
            var resolvedDependency = new com.keplerops.groundcontrol.domain.packregistry.service.ResolvedPack(
                    dependency, "1.0.0", null, null, List.of());
            var resolved = new com.keplerops.groundcontrol.domain.packregistry.service.ResolvedPack(
                    entry, "1.0.0", null, null, List.of(resolvedDependency));

            assertThat(resolver.checkCompatibility(resolved)).isFalse();
        }
    }

    @Nested
    class VersionConstraints {

        @Test
        void resolvesWithGreaterThanOrEqualConstraint() {
            var project = makeProject();
            var v100 = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.0.0");
            var v200 = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "2.0.0");
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "pack", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(v200, v100));

            var result = resolver.resolve(PROJECT_ID, "pack", ">=1.0.0");
            assertThat(result.resolvedVersion()).isEqualTo("2.0.0");
        }

        @Test
        void resolvesWithLessThanOrEqualConstraint() {
            var project = makeProject();
            var v100 = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.0.0");
            var v200 = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "2.0.0");
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "pack", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(v200, v100));

            var result = resolver.resolve(PROJECT_ID, "pack", "<=1.0.0");
            assertThat(result.resolvedVersion()).isEqualTo("1.0.0");
        }
    }

    @Nested
    class Dependencies {

        @Test
        void resolvesDependencies() {
            var project = makeProject();
            var main = new PackRegistryEntry(project, "main-pack", PackType.CUSTOM, "1.0.0");
            main.setDependencies(List.of(dependency("dep-pack", "1.0.0")));
            var dep = new PackRegistryEntry(project, "dep-pack", PackType.CUSTOM, "1.0.0");

            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "main-pack", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(main));
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "dep-pack", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(dep));

            var result = resolver.resolve(PROJECT_ID, "main-pack", null);
            assertThat(result.resolvedDependencies()).hasSize(1);
            assertThat(result.resolvedDependencies().getFirst().resolvedVersion())
                    .isEqualTo("1.0.0");
        }

        @Test
        void detectsCircularDependency() {
            var project = makeProject();
            var packA = new PackRegistryEntry(project, "pack-a", PackType.CUSTOM, "1.0.0");
            packA.setDependencies(List.of(dependency("pack-b")));
            var packB = new PackRegistryEntry(project, "pack-b", PackType.CUSTOM, "1.0.0");
            packB.setDependencies(List.of(dependency("pack-a")));

            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "pack-a", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(packA));
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "pack-b", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(packB));

            assertThatThrownBy(() -> resolver.resolve(PROJECT_ID, "pack-a", null))
                    .isInstanceOf(com.keplerops.groundcontrol.domain.exception.DomainValidationException.class)
                    .hasMessageContaining("Circular dependency");
        }

        @Test
        void noDependenciesReturnsEmpty() {
            var project = makeProject();
            var entry = new PackRegistryEntry(project, "no-deps", PackType.CUSTOM, "1.0.0");

            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "no-deps", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(entry));

            var result = resolver.resolve(PROJECT_ID, "no-deps", null);
            assertThat(result.resolvedDependencies()).isEmpty();
        }

        @Test
        void allowsSharedTransitiveDependenciesAcrossSiblingBranches() {
            var project = makeProject();
            var packA = new PackRegistryEntry(project, "pack-a", PackType.CUSTOM, "1.0.0");
            packA.setDependencies(List.of(dependency("pack-b"), dependency("pack-c")));
            var packB = new PackRegistryEntry(project, "pack-b", PackType.CUSTOM, "1.0.0");
            packB.setDependencies(List.of(dependency("pack-d")));
            var packC = new PackRegistryEntry(project, "pack-c", PackType.CUSTOM, "1.0.0");
            packC.setDependencies(List.of(dependency("pack-d")));
            var packD = new PackRegistryEntry(project, "pack-d", PackType.CUSTOM, "1.0.0");

            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "pack-a", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(packA));
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "pack-b", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(packB));
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "pack-c", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(packC));
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "pack-d", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(packD));

            var result = resolver.resolve(PROJECT_ID, "pack-a", null);

            assertThat(result.resolvedDependencies()).hasSize(2);
            assertThat(result.resolvedDependencies().get(0).resolvedDependencies())
                    .hasSize(1);
            assertThat(result.resolvedDependencies().get(1).resolvedDependencies())
                    .hasSize(1);
        }
    }

    @Nested
    class SemanticVersionPrecedence {

        @Test
        void prefersStableReleaseOverPrerelease() {
            var project = makeProject();
            var prerelease = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.0.0-rc.1");
            var stable = new PackRegistryEntry(project, "pack", PackType.CUSTOM, "1.0.0");
            when(registryRepository.findByProjectIdAndPackIdAndCatalogStatusOrderByRegisteredAtDesc(
                            PROJECT_ID, "pack", CatalogStatus.AVAILABLE))
                    .thenReturn(List.of(prerelease, stable));

            var result = resolver.resolve(PROJECT_ID, "pack", null);

            assertThat(result.resolvedVersion()).isEqualTo("1.0.0");
        }
    }
}

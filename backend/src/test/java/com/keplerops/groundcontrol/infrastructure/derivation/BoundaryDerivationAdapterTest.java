package com.keplerops.groundcontrol.infrastructure.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationScope;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundaryDerivationAdapterTest {

    private static final String COMMIT = "abc123def456abc123def456abc123def456abc1";

    @TempDir
    Path repoRoot;

    @Test
    void fullRepoModeDerivesCanonicalRepoBoundaries() throws Exception {
        createRepoShape();
        var adapter = adapter();

        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of()));

        assertThat(result.captureLimits()).isEmpty();
        assertThat(result.facts())
                .filteredOn(f -> f.factKind() == SystemModelFactKind.TRUST_BOUNDARY)
                .extracting(f -> f.payload().get("boundaryKey"))
                .contains("backend-api", "backend-domain", "backend-infrastructure", "mcp", "frontend", "pipelines");
    }

    @Test
    void pathSetModeOnlyEmitsBoundariesTouchedByRequestedPaths() throws Exception {
        createRepoShape();
        var adapter = adapter();

        var result = adapter.derive(
                request(DerivationScopeMode.PATH_SET, List.of("frontend/src/App.tsx", ".github/workflows/ci.yml")));

        assertThat(result.facts())
                .extracting(f -> f.payload().get("boundaryKey"))
                .containsExactlyInAnyOrder("frontend", "pipelines");
    }

    @Test
    void descriptorAdvertisesArchitectureBoundarySurface() {
        var descriptor = adapter().descriptor();

        assertThat(descriptor.adapterId()).isEqualTo("boundary-model-derivation");
        assertThat(descriptor.surfaces()).contains("architecture", "application", "frontend", "mcp");
        assertThat(descriptor.factKinds()).containsExactly(SystemModelFactKind.TRUST_BOUNDARY);
    }

    private BoundaryDerivationAdapter adapter() {
        var properties = new BoundaryDerivationProperties();
        properties.setRepositoryRoot(repoRoot);
        return new BoundaryDerivationAdapter(properties);
    }

    private DerivationAdapterRequest request(DerivationScopeMode mode, List<String> paths) {
        return new DerivationAdapterRequest(
                UUID.randomUUID(),
                "ground-control",
                new DerivationScope(
                        mode,
                        COMMIT,
                        null,
                        paths,
                        Set.of("java", "typescript", "javascript", "yaml", "dockerfile", "metadata"),
                        Set.of("application", "frontend", "mcp", "github-actions", "dockerfile", "architecture")));
    }

    private void createRepoShape() throws Exception {
        Files.createDirectories(repoRoot.resolve("backend/src/main/java/com/keplerops/groundcontrol/api"));
        Files.createDirectories(repoRoot.resolve("backend/src/main/java/com/keplerops/groundcontrol/domain"));
        Files.createDirectories(repoRoot.resolve("backend/src/main/java/com/keplerops/groundcontrol/infrastructure"));
        Files.createDirectories(repoRoot.resolve("mcp/ground-control"));
        Files.createDirectories(repoRoot.resolve("frontend/src"));
        Files.createDirectories(repoRoot.resolve(".github/workflows"));
        Files.writeString(repoRoot.resolve(".github/workflows/ci.yml"), "name: ci\n");
        Files.writeString(repoRoot.resolve("backend/Dockerfile"), "FROM eclipse-temurin:21\n");
    }
}

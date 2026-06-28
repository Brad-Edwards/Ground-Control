package com.keplerops.groundcontrol.infrastructure.derivation;

import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapter;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterDescriptor;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterResult;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationFactProvenance;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationScope;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class BoundaryDerivationAdapter implements DerivationAdapter {

    private static final String ADAPTER_ID = "boundary-model-derivation";
    private static final Set<String> LANGUAGES =
            Set.of("java", "javascript", "typescript", "python", "yaml", "dockerfile", "hcl", "metadata");
    private static final Set<String> SURFACES = Set.of(
            "application",
            "frontend",
            "mcp",
            "architecture",
            IacFactKeys.SURFACE_GITHUB_ACTIONS,
            IacFactKeys.SURFACE_DOCKERFILE,
            IacFactKeys.SURFACE_DOCKER_COMPOSE,
            IacFactKeys.SURFACE_TERRAFORM);
    private static final Set<DerivationScopeMode> SCOPE_MODES =
            Set.of(DerivationScopeMode.FULL_REPO, DerivationScopeMode.DIFF, DerivationScopeMode.PATH_SET);

    private final BoundaryDerivationProperties properties;

    public BoundaryDerivationAdapter(BoundaryDerivationProperties properties) {
        this.properties = properties;
    }

    @Override
    public DerivationAdapterDescriptor descriptor() {
        return new DerivationAdapterDescriptor(
                ADAPTER_ID,
                "boundary-model",
                properties.getRulesetVersion(),
                "canonical-boundary-model",
                properties.getRulesetVersion(),
                LANGUAGES,
                SURFACES,
                SCOPE_MODES,
                Set.of(SystemModelFactKind.TRUST_BOUNDARY));
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled() && Files.isDirectory(repositoryRoot());
    }

    @Override
    public DerivationAdapterResult derive(DerivationAdapterRequest request) {
        var derivedAt = Instant.now();
        var provenance = new DerivationFactProvenance(
                ADAPTER_ID,
                "boundary-model",
                properties.getRulesetVersion(),
                "canonical-boundary-model",
                properties.getRulesetVersion(),
                request.scope().commitSha(),
                derivedAt);
        var facts = new ArrayList<DerivedSystemModelFact>();
        for (BoundarySeed seed : seeds()) {
            if (shouldEmit(seed, request.scope())) {
                facts.add(seed.toFact(provenance));
            }
        }
        return DerivationAdapterResult.facts(facts);
    }

    private boolean shouldEmit(BoundarySeed seed, DerivationScope scope) {
        if (scope.surfaces().stream().noneMatch(seed.surfaces()::contains)) {
            return false;
        }
        if (scope.mode() == DerivationScopeMode.FULL_REPO) {
            return seed.exists(repositoryRoot());
        }
        return scope.paths().stream().anyMatch(path -> seed.matches(path));
    }

    private Path repositoryRoot() {
        return properties.getRepositoryRoot().toAbsolutePath().normalize();
    }

    private List<BoundarySeed> seeds() {
        return List.of(
                new BoundarySeed(
                        "backend-api",
                        "Backend API",
                        "Spring MVC controllers and API DTOs.",
                        List.of("backend/src/main/java/com/keplerops/groundcontrol/api/**"),
                        List.of("application")),
                new BoundarySeed(
                        "backend-domain",
                        "Backend Domain",
                        "Service and aggregate domain model.",
                        List.of("backend/src/main/java/com/keplerops/groundcontrol/domain/**"),
                        List.of("application")),
                new BoundarySeed(
                        "backend-infrastructure",
                        "Backend Infrastructure",
                        "External adapter implementations and infrastructure integrations.",
                        List.of("backend/src/main/java/com/keplerops/groundcontrol/infrastructure/**"),
                        List.of("application")),
                new BoundarySeed(
                        "mcp",
                        "MCP Tools",
                        "Node and Python MCP server/tool implementations.",
                        List.of("mcp/ground-control/**", "mcp/citation/**"),
                        List.of("mcp", "architecture")),
                new BoundarySeed(
                        "frontend",
                        "Frontend SPA",
                        "React application and browser-side UI code.",
                        List.of("frontend/src/**"),
                        List.of("frontend")),
                new BoundarySeed(
                        "pipelines",
                        "Build and Deployment Pipelines",
                        "CI/CD, container, and deployment configuration surfaces.",
                        List.of(
                                ".github/workflows/**",
                                "Dockerfile",
                                "backend/Dockerfile",
                                "docker-compose.yml",
                                "deploy/docker/**"),
                        List.of(
                                IacFactKeys.SURFACE_GITHUB_ACTIONS,
                                IacFactKeys.SURFACE_DOCKERFILE,
                                IacFactKeys.SURFACE_DOCKER_COMPOSE,
                                IacFactKeys.SURFACE_TERRAFORM,
                                "architecture")));
    }

    private record BoundarySeed(
            String key, String name, String description, List<String> selectors, List<String> surfaces) {

        boolean exists(Path repoRoot) {
            return selectors.stream()
                    .map(this::concretePrefix)
                    .map(repoRoot::resolve)
                    .anyMatch(Files::exists);
        }

        boolean matches(String path) {
            return selectors.stream().anyMatch(selector -> matchesSelector(path, selector));
        }

        DerivedSystemModelFact toFact(DerivationFactProvenance provenance) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("boundaryKey", key);
            payload.put("boundaryName", name);
            payload.put("description", description);
            payload.put("boundarySource", "derived");
            payload.put("pathSelectors", selectors);
            payload.put("surfaces", surfaces);
            payload.put("schemaVersion", "boundary-input/v1");
            return new DerivedSystemModelFact(
                    SystemModelFactKind.TRUST_BOUNDARY,
                    "boundary:" + key,
                    name,
                    description,
                    null,
                    payload,
                    provenance);
        }

        private String concretePrefix(String selector) {
            if (selector.endsWith("/**")) {
                return selector.substring(0, selector.length() - 3);
            }
            return selector;
        }
    }

    private static boolean matchesSelector(String path, String selector) {
        var normalizedPath = normalize(path);
        var normalizedSelector = normalize(selector);
        if (normalizedSelector.endsWith("/**")) {
            var prefix = normalizedSelector.substring(0, normalizedSelector.length() - 3);
            return normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/");
        }
        return normalizedPath.equals(normalizedSelector) || normalizedPath.startsWith(normalizedSelector + "/");
    }

    private static String normalize(String value) {
        var normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}

package com.keplerops.groundcontrol.infrastructure.derivation;

import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapter;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterDescriptor;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterResult;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationCaptureLimitDraft;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class IacPipelineDerivationAdapter implements DerivationAdapter {

    private static final String ADAPTER_ID = "iac-pipeline-derivation";
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("yaml", "dockerfile", "hcl");
    private static final Set<String> SUPPORTED_SURFACES =
            Set.of("github-actions", "dockerfile", "docker-compose", "terraform");
    private static final Set<DerivationScopeMode> SUPPORTED_SCOPE_MODES =
            Set.of(DerivationScopeMode.FULL_REPO, DerivationScopeMode.DIFF, DerivationScopeMode.PATH_SET);
    private static final Set<SystemModelFactKind> FACT_KINDS = Set.of(
            SystemModelFactKind.COMPONENT,
            SystemModelFactKind.TRUST_BOUNDARY,
            SystemModelFactKind.DATA_FLOW,
            SystemModelFactKind.ENTRY_POINT,
            SystemModelFactKind.SECRET_USAGE,
            SystemModelFactKind.EXTERNAL_INTERACTION,
            SystemModelFactKind.DATA_CLASSIFICATION_HINT);

    private final IacPipelineDerivationProperties properties;
    private final GitHubActionsNormalizer ghActionsNormalizer;
    private final DockerfileNormalizer dockerfileNormalizer;
    private final DockerComposeNormalizer dockerComposeNormalizer;
    private final TerraformNormalizer terraformNormalizer;

    @Autowired
    public IacPipelineDerivationAdapter(IacPipelineDerivationProperties properties) {
        this(
                properties,
                new GitHubActionsNormalizer(),
                new DockerfileNormalizer(),
                new DockerComposeNormalizer(),
                new TerraformNormalizer());
    }

    IacPipelineDerivationAdapter(
            IacPipelineDerivationProperties properties,
            GitHubActionsNormalizer ghActionsNormalizer,
            DockerfileNormalizer dockerfileNormalizer,
            DockerComposeNormalizer dockerComposeNormalizer,
            TerraformNormalizer terraformNormalizer) {
        this.properties = properties;
        this.ghActionsNormalizer = ghActionsNormalizer;
        this.dockerfileNormalizer = dockerfileNormalizer;
        this.dockerComposeNormalizer = dockerComposeNormalizer;
        this.terraformNormalizer = terraformNormalizer;
    }

    @Override
    public DerivationAdapterDescriptor descriptor() {
        return new DerivationAdapterDescriptor(
                ADAPTER_ID,
                "iac-pipeline",
                properties.getRulesetVersion(),
                "iac-pipeline-rules",
                properties.getRulesetVersion(),
                SUPPORTED_LANGUAGES,
                SUPPORTED_SURFACES,
                SUPPORTED_SCOPE_MODES,
                FACT_KINDS);
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled() && Files.isDirectory(properties.getRepositoryRoot());
    }

    @Override
    public DerivationAdapterResult derive(DerivationAdapterRequest request) {
        var derivedAt = Instant.now();
        var scope = request.scope();
        var facts = new ArrayList<DerivedSystemModelFact>();
        var captureLimits = new ArrayList<DerivationCaptureLimitDraft>();

        // Emit UNSUPPORTED_SURFACE for any surface requested by the caller that we don't support
        var requestedSurfaces = scope.surfaces();
        if (requestedSurfaces != null && !requestedSurfaces.isEmpty()) {
            var enabledSurfaces = properties.getEnabledSurfaces();
            var emittedUnsupported = new LinkedHashSet<String>();
            for (String surface : requestedSurfaces) {
                if (!enabledSurfaces.contains(surface) && emittedUnsupported.add(surface)) {
                    captureLimits.add(new DerivationCaptureLimitDraft(
                            ADAPTER_ID,
                            CaptureLimitReason.UNSUPPORTED_SURFACE,
                            null,
                            surface,
                            "Surface '" + surface + "' is not supported by the IaC/pipeline adapter",
                            scope.commitSha(),
                            derivedAt));
                }
            }
        }

        var repoRoot = properties.getRepositoryRoot().toAbsolutePath().normalize();
        int[] fileCount = {0};

        try (Stream<Path> walker = Files.walk(repoRoot)) {
            var fileIterator = walker.iterator();
            while (fileIterator.hasNext()) {
                var absolutePath = fileIterator.next();

                if (!Files.isRegularFile(absolutePath)) {
                    continue;
                }

                // Relative path string
                var relativePath = repoRoot.relativize(absolutePath).toString().replace('\\', '/');

                // Excluded paths check
                if (isExcluded(relativePath, properties.getExcludedPaths())) {
                    continue;
                }

                // Symlink escape guard
                try {
                    var realPath = absolutePath.toRealPath();
                    if (!realPath.startsWith(repoRoot)) {
                        continue;
                    }
                } catch (IOException ignored) {
                    continue;
                }

                // Classify surface
                var fileName = absolutePath.getFileName();
                if (fileName == null) {
                    continue;
                }
                var surface = classifySurface(fileName.toString(), relativePath);
                if (surface == null) {
                    continue;
                }

                // Surface filter: if scope specifies surfaces, only process matching ones
                if (requestedSurfaces != null && !requestedSurfaces.isEmpty() && !requestedSurfaces.contains(surface)) {
                    continue;
                }

                // Surface must be enabled
                if (!properties.getEnabledSurfaces().contains(surface)) {
                    continue;
                }

                // DIFF/PATH_SET: only process files in scope.paths()
                if (scope.mode() == DerivationScopeMode.DIFF || scope.mode() == DerivationScopeMode.PATH_SET) {
                    var paths = scope.paths();
                    if (paths == null || paths.isEmpty()) {
                        continue;
                    }
                    var finalRelativePath = relativePath;
                    if (paths.stream()
                            .noneMatch(p -> finalRelativePath.equals(p) || finalRelativePath.startsWith(p + "/"))) {
                        continue;
                    }
                }

                // Enforce maxFiles cap
                if (fileCount[0] >= properties.getMaxFiles()) {
                    break;
                }
                fileCount[0]++;

                // File size check
                long fileSize;
                try {
                    fileSize = Files.size(absolutePath);
                } catch (IOException ignored) {
                    continue;
                }
                if (fileSize > properties.getMaxFileBytes()) {
                    captureLimits.add(new DerivationCaptureLimitDraft(
                            ADAPTER_ID,
                            CaptureLimitReason.TOOL_EXECUTION_FAILED,
                            null,
                            surface,
                            "File '" + relativePath + "' exceeds the maximum allowed size of "
                                    + properties.getMaxFileBytes() + " bytes",
                            scope.commitSha(),
                            derivedAt));
                    continue;
                }

                // Read content
                String content;
                try {
                    content = Files.readString(absolutePath, StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    captureLimits.add(new DerivationCaptureLimitDraft(
                            ADAPTER_ID,
                            CaptureLimitReason.TOOL_EXECUTION_FAILED,
                            null,
                            surface,
                            "Failed to read file '" + relativePath + "'",
                            scope.commitSha(),
                            derivedAt));
                    continue;
                }

                // Dispatch to normalizer
                try {
                    var normalized = dispatch(
                            surface,
                            relativePath,
                            content,
                            scope.commitSha(),
                            properties.getRulesetVersion(),
                            derivedAt);
                    facts.addAll(normalized);
                } catch (Exception exception) {
                    captureLimits.add(new DerivationCaptureLimitDraft(
                            ADAPTER_ID,
                            CaptureLimitReason.TOOL_EXECUTION_FAILED,
                            null,
                            surface,
                            "Parser failure on '" + relativePath + "'",
                            scope.commitSha(),
                            derivedAt));
                }
            }
        } catch (IOException exception) {
            captureLimits.add(new DerivationCaptureLimitDraft(
                    ADAPTER_ID,
                    CaptureLimitReason.TOOL_EXECUTION_FAILED,
                    null,
                    null,
                    "Failed to walk repository root",
                    scope.commitSha(),
                    derivedAt));
        }

        return new DerivationAdapterResult(facts, captureLimits);
    }

    private List<DerivedSystemModelFact> dispatch(
            String surface,
            String relativePath,
            String content,
            String commitSha,
            String rulesetVersion,
            Instant derivedAt) {
        return switch (surface) {
            case "github-actions" -> ghActionsNormalizer.normalize(
                    surface, relativePath, content, ADAPTER_ID, commitSha, rulesetVersion, derivedAt);
            case "dockerfile" -> dockerfileNormalizer.normalize(
                    surface, relativePath, content, ADAPTER_ID, commitSha, rulesetVersion, derivedAt);
            case "docker-compose" -> dockerComposeNormalizer.normalize(
                    surface, relativePath, content, ADAPTER_ID, commitSha, rulesetVersion, derivedAt);
            case "terraform" -> terraformNormalizer.normalize(
                    surface, relativePath, content, ADAPTER_ID, commitSha, rulesetVersion, derivedAt);
            default -> List.of();
        };
    }

    /**
     * Classify the surface of a file based on its filename and relative path.
     *
     * @return surface string, or null if the file is not a recognized IaC/CI surface
     */
    private static String classifySurface(String filename, String relativePath) {
        var filenameLower = filename.toLowerCase(Locale.ROOT);

        // github-actions: must be under .github/workflows/ and end with .yml or .yaml
        if (relativePath.contains(".github/workflows/")
                && (relativePath.endsWith(".yml") || relativePath.endsWith(".yaml"))) {
            return "github-actions";
        }

        // docker-compose: filename starts with docker-compose or compose, ends with .yml or .yaml
        if ((filenameLower.startsWith("docker-compose") || filenameLower.startsWith("compose"))
                && (filenameLower.endsWith(".yml") || filenameLower.endsWith(".yaml"))) {
            return "docker-compose";
        }

        // dockerfile: equals "dockerfile", starts with "dockerfile.", or ends with ".dockerfile"
        if ("dockerfile".equals(filenameLower)
                || filenameLower.startsWith("dockerfile.")
                || filenameLower.endsWith(".dockerfile")) {
            return "dockerfile";
        }

        // terraform: ends with .tf or .tfvars
        if (filenameLower.endsWith(".tf") || filenameLower.endsWith(".tfvars")) {
            return "terraform";
        }

        return null;
    }

    /**
     * Check whether a relative path falls under any of the excluded path segments.
     */
    private static boolean isExcluded(String relativePath, List<String> excludedPaths) {
        for (String excluded : excludedPaths) {
            if (relativePath.equals(excluded)
                    || relativePath.startsWith(excluded + "/")
                    || relativePath.contains("/" + excluded + "/")
                    || relativePath.endsWith("/" + excluded)) {
                return true;
            }
        }
        return false;
    }
}

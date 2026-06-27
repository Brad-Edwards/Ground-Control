package com.keplerops.groundcontrol.infrastructure.derivation;

import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapter;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterDescriptor;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterResult;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationCaptureLimitDraft;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationScope;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class IacPipelineDerivationAdapter implements DerivationAdapter {

    private static final String ADAPTER_ID = "iac-pipeline-derivation";
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("yaml", IacFactKeys.SURFACE_DOCKERFILE, "hcl");
    private static final Set<String> SUPPORTED_SURFACES = Set.of(
            IacFactKeys.SURFACE_GITHUB_ACTIONS,
            IacFactKeys.SURFACE_DOCKERFILE,
            IacFactKeys.SURFACE_DOCKER_COMPOSE,
            IacFactKeys.SURFACE_TERRAFORM);
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

        captureLimits.addAll(buildUnsupportedSurfaceLimits(scope, derivedAt));

        var repoRoot = properties.getRepositoryRoot().toAbsolutePath().normalize();
        var walkState = new WalkState(facts, captureLimits);

        try {
            Files.walkFileTree(repoRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return handleDirectory(dir, repoRoot);
                }

                @Override
                public FileVisitResult visitFile(Path absolutePath, BasicFileAttributes attrs) {
                    return handleFile(absolutePath, repoRoot, scope, walkState, derivedAt);
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
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

        if (walkState.hitFileCap) {
            captureLimits.add(new DerivationCaptureLimitDraft(
                    ADAPTER_ID,
                    CaptureLimitReason.TOOL_EXECUTION_FAILED,
                    null,
                    null,
                    "IaC/pipeline scan truncated at maxFiles=" + properties.getMaxFiles()
                            + "; some files were not derived",
                    scope.commitSha(),
                    derivedAt));
        }

        return new DerivationAdapterResult(facts, captureLimits);
    }

    private List<DerivationCaptureLimitDraft> buildUnsupportedSurfaceLimits(DerivationScope scope, Instant derivedAt) {
        var limits = new ArrayList<DerivationCaptureLimitDraft>();
        var requestedSurfaces = scope.surfaces();
        if (requestedSurfaces == null || requestedSurfaces.isEmpty()) {
            return limits;
        }
        var enabledSurfaces = properties.getEnabledSurfaces();
        var emitted = new LinkedHashSet<String>();
        for (String surface : requestedSurfaces) {
            if (!enabledSurfaces.contains(surface) && emitted.add(surface)) {
                limits.add(new DerivationCaptureLimitDraft(
                        ADAPTER_ID,
                        CaptureLimitReason.UNSUPPORTED_SURFACE,
                        null,
                        surface,
                        "Surface '" + surface + "' is not supported by the IaC/pipeline adapter",
                        scope.commitSha(),
                        derivedAt));
            }
        }
        return limits;
    }

    private FileVisitResult handleDirectory(Path dir, Path repoRoot) {
        if (dir.equals(repoRoot)) {
            return FileVisitResult.CONTINUE;
        }
        var relativePath = repoRoot.relativize(dir).toString().replace('\\', '/');
        if (isExcluded(relativePath, properties.getExcludedPaths())) {
            return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
    }

    private FileVisitResult handleFile(
            Path absolutePath, Path repoRoot, DerivationScope scope, WalkState walkState, Instant derivedAt) {
        var relativePath = repoRoot.relativize(absolutePath).toString().replace('\\', '/');

        if (!isSymlinkSafe(absolutePath, repoRoot)) {
            return FileVisitResult.CONTINUE;
        }
        var fileName = absolutePath.getFileName();
        if (fileName == null) {
            return FileVisitResult.CONTINUE;
        }
        var surface = classifySurface(fileName.toString(), relativePath);
        if (!shouldProcess(surface, relativePath, scope)) {
            return FileVisitResult.CONTINUE;
        }
        if (walkState.fileCount >= properties.getMaxFiles()) {
            walkState.hitFileCap = true;
            return FileVisitResult.TERMINATE;
        }
        walkState.fileCount++;
        return readAndDispatchFile(
                absolutePath, relativePath, surface, scope, walkState.facts, walkState.captureLimits, derivedAt);
    }

    private static boolean isSymlinkSafe(Path absolutePath, Path repoRoot) {
        try {
            var realPath = absolutePath.toRealPath();
            return realPath.startsWith(repoRoot);
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean shouldProcess(String surface, String relativePath, DerivationScope scope) {
        if (surface == null) {
            return false;
        }
        var requestedSurfaces = scope.surfaces();
        if (requestedSurfaces != null && !requestedSurfaces.isEmpty() && !requestedSurfaces.contains(surface)) {
            return false;
        }
        if (!properties.getEnabledSurfaces().contains(surface)) {
            return false;
        }
        var requestedLanguages = scope.languages();
        if (requestedLanguages != null
                && !requestedLanguages.isEmpty()
                && !requestedLanguages.contains(surfaceLanguage(surface))) {
            return false;
        }
        if (scope.mode() == DerivationScopeMode.DIFF || scope.mode() == DerivationScopeMode.PATH_SET) {
            var paths = scope.paths();
            if (paths == null || paths.isEmpty()) {
                return false;
            }
            if (!isInScope(relativePath, paths)) {
                return false;
            }
        }
        return true;
    }

    private FileVisitResult readAndDispatchFile(
            Path absolutePath,
            String relativePath,
            String surface,
            DerivationScope scope,
            List<DerivedSystemModelFact> facts,
            List<DerivationCaptureLimitDraft> captureLimits,
            Instant derivedAt) {
        long fileSize;
        try {
            fileSize = Files.size(absolutePath);
        } catch (IOException ignored) {
            return FileVisitResult.CONTINUE;
        }
        if (fileSize > properties.getMaxFileBytes()) {
            captureLimits.add(new DerivationCaptureLimitDraft(
                    ADAPTER_ID,
                    CaptureLimitReason.TOOL_EXECUTION_FAILED,
                    null,
                    surface,
                    "File '" + relativePath + "' exceeds the maximum allowed size of " + properties.getMaxFileBytes()
                            + " bytes",
                    scope.commitSha(),
                    derivedAt));
            return FileVisitResult.CONTINUE;
        }

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
            return FileVisitResult.CONTINUE;
        }

        try {
            var normalized = dispatch(
                    surface, relativePath, content, scope.commitSha(), properties.getRulesetVersion(), derivedAt);
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

        return FileVisitResult.CONTINUE;
    }

    private List<DerivedSystemModelFact> dispatch(
            String surface,
            String relativePath,
            String content,
            String commitSha,
            String rulesetVersion,
            Instant derivedAt) {
        return switch (surface) {
            case IacFactKeys.SURFACE_GITHUB_ACTIONS -> ghActionsNormalizer.normalize(
                    surface, relativePath, content, ADAPTER_ID, commitSha, rulesetVersion, derivedAt);
            case IacFactKeys.SURFACE_DOCKERFILE -> dockerfileNormalizer.normalize(
                    surface, relativePath, content, ADAPTER_ID, commitSha, rulesetVersion, derivedAt);
            case IacFactKeys.SURFACE_DOCKER_COMPOSE -> dockerComposeNormalizer.normalize(
                    surface, relativePath, content, ADAPTER_ID, commitSha, rulesetVersion, derivedAt);
            case IacFactKeys.SURFACE_TERRAFORM -> terraformNormalizer.normalize(
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

        if (relativePath.contains(".github/workflows/")
                && (relativePath.endsWith(".yml") || relativePath.endsWith(".yaml"))) {
            return IacFactKeys.SURFACE_GITHUB_ACTIONS;
        }

        if ((filenameLower.startsWith("docker-compose") || filenameLower.startsWith("compose"))
                && (filenameLower.endsWith(".yml") || filenameLower.endsWith(".yaml"))) {
            return IacFactKeys.SURFACE_DOCKER_COMPOSE;
        }

        if (IacFactKeys.SURFACE_DOCKERFILE.equals(filenameLower)
                || filenameLower.startsWith(IacFactKeys.SURFACE_DOCKERFILE + ".")
                || filenameLower.endsWith("." + IacFactKeys.SURFACE_DOCKERFILE)) {
            return IacFactKeys.SURFACE_DOCKERFILE;
        }

        if (filenameLower.endsWith(".tf") || filenameLower.endsWith(".tfvars")) {
            return IacFactKeys.SURFACE_TERRAFORM;
        }

        return null;
    }

    /**
     * Map a supported surface to the grammar/language token it is derived from. Used to honour the
     * {@link DerivationScope} language dimension so a language-scoped run does not persist facts
     * from other grammars.
     */
    private static String surfaceLanguage(String surface) {
        return switch (surface) {
            case IacFactKeys.SURFACE_GITHUB_ACTIONS, IacFactKeys.SURFACE_DOCKER_COMPOSE -> "yaml";
            case IacFactKeys.SURFACE_DOCKERFILE -> IacFactKeys.SURFACE_DOCKERFILE;
            case IacFactKeys.SURFACE_TERRAFORM -> "hcl";
            default -> "";
        };
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

    /**
     * Check whether a file's relative path is in scope for a set of requested paths.
     *
     * <p>Each requested path is normalised via {@link #normalizeScopePath} (trim, strip leading
     * {@code ./}, validate). Matching uses {@link Path#startsWith(Path)} element-wise semantics,
     * so {@code "terraform"} matches {@code "terraform/main.tf"} but never
     * {@code "terraform-modules/x.tf"}.
     */
    private static boolean isInScope(String relativePath, List<String> requestedPaths) {
        var filePath = Path.of(relativePath);
        for (String rawPath : requestedPaths) {
            var scopePath = normalizeScopePath(rawPath);
            if (scopePath.isPresent() && filePath.startsWith(scopePath.get())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalise a raw requested path: trim whitespace, strip leading {@code ./} segments, reject
     * null, empty, absolute, and path-traversal ({@code ..}) inputs.
     *
     * @return the normalised {@link Path}, or empty if the input should be rejected (fail closed)
     */
    private static Optional<Path> normalizeScopePath(String rawPath) {
        if (rawPath == null) {
            return Optional.empty();
        }
        var normalized = rawPath.trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        Path p;
        try {
            p = Path.of(normalized);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (p.isAbsolute()) {
            return Optional.empty();
        }
        for (Path element : p) {
            if ("..".equals(element.toString())) {
                return Optional.empty();
            }
        }
        return Optional.of(p);
    }

    /**
     * Mutable accumulator for the file-tree walk. Bundles the two output lists and two primitive
     * counters that handleFile needs to mutate, reducing handleFile's parameter count to ≤7
     * (fixes S107).
     */
    private static final class WalkState {
        final List<DerivedSystemModelFact> facts;
        final List<DerivationCaptureLimitDraft> captureLimits;
        int fileCount;
        boolean hitFileCap;

        WalkState(List<DerivedSystemModelFact> facts, List<DerivationCaptureLimitDraft> captureLimits) {
            this.facts = facts;
            this.captureLimits = captureLimits;
        }
    }
}

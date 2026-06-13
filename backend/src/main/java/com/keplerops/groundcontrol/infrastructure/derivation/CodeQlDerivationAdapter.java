package com.keplerops.groundcontrol.infrastructure.derivation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapter;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterDescriptor;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterResult;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationCaptureLimitDraft;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class CodeQlDerivationAdapter implements DerivationAdapter {

    private static final String CODEQL_WORK_DIRECTORY = ".gc/codeql";
    private static final String VERSION_COMMAND = "version";
    private static final String VERSION_FIELD = "version";
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("java", "javascript", "typescript", "python");
    private static final Set<String> SUPPORTED_SURFACES = Set.of("application");
    private static final Set<DerivationScopeMode> SUPPORTED_SCOPE_MODES =
            Set.of(DerivationScopeMode.FULL_REPO, DerivationScopeMode.DIFF, DerivationScopeMode.PATH_SET);
    private static final Set<SystemModelFactKind> FACT_KINDS = Set.of(
            SystemModelFactKind.DATA_FLOW,
            SystemModelFactKind.ENTRY_POINT,
            SystemModelFactKind.TAINT_PATH,
            SystemModelFactKind.SECRET_USAGE,
            SystemModelFactKind.EXTERNAL_INTERACTION);

    private final CodeQlDerivationProperties properties;
    private final ObjectMapper objectMapper;
    private final CodeQlCommandRunner commandRunner;
    private final CodeQlSarifNormalizer normalizer;

    @Autowired
    public CodeQlDerivationAdapter(CodeQlDerivationProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new ProcessCodeQlCommandRunner());
    }

    CodeQlDerivationAdapter(
            CodeQlDerivationProperties properties, ObjectMapper objectMapper, CodeQlCommandRunner commandRunner) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.commandRunner = commandRunner;
        this.normalizer = new CodeQlSarifNormalizer(objectMapper);
    }

    @Override
    public DerivationAdapterDescriptor descriptor() {
        return new DerivationAdapterDescriptor(
                "codeql-derivation",
                "CodeQL",
                "runtime",
                "codeql-query-packs",
                rulesetVersion(),
                SUPPORTED_LANGUAGES,
                SUPPORTED_SURFACES,
                SUPPORTED_SCOPE_MODES,
                FACT_KINDS);
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled()
                && properties.hasAnyPinnedQueryPack()
                && commandRunner.canRun(properties.getCliPath(), shortTimeout(properties.getTimeout()));
    }

    @Override
    public DerivationAdapterResult derive(DerivationAdapterRequest request) {
        var derivedAt = Instant.now();
        var toolVersion = resolveToolVersion();
        var facts = new ArrayList<DerivedSystemModelFact>();
        var captureLimits = new ArrayList<DerivationCaptureLimitDraft>();
        for (LanguagePlan language : languagePlans(request)) {
            var queryPack = properties
                    .queryPackFor(request.projectIdentifier(), language.requestedLanguage())
                    .orElse("");
            if (!CodeQlDerivationProperties.isPinnedQueryPack(queryPack)) {
                captureLimits.add(captureLimit(
                        CaptureLimitReason.TOOL_UNAVAILABLE,
                        language.requestedLanguage(),
                        request,
                        "CodeQL query pack pin is not configured for language " + language.requestedLanguage(),
                        derivedAt));
                continue;
            }
            try {
                facts.addAll(runLanguage(language, queryPack, request, toolVersion, derivedAt));
            } catch (RuntimeException exception) {
                captureLimits.add(captureLimit(
                        CaptureLimitReason.TOOL_EXECUTION_FAILED,
                        language.requestedLanguage(),
                        request,
                        "CodeQL execution failed for language " + language.requestedLanguage()
                                + "; raw tool output was not persisted",
                        derivedAt));
            }
        }
        return new DerivationAdapterResult(facts, captureLimits);
    }

    private List<DerivedSystemModelFact> runLanguage(
            LanguagePlan language,
            String queryPack,
            DerivationAdapterRequest request,
            String toolVersion,
            Instant derivedAt) {
        var repositoryRoot = repositoryRoot();
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory(codeQlWorkRoot(repositoryRoot), "run-");
            var databasePath = tempDir.resolve(language.codeQlLanguage() + "-db");
            var sarifPath = tempDir.resolve(language.codeQlLanguage() + ".sarif");
            var createCommand = databaseCreateCommand(language.codeQlLanguage(), repositoryRoot, databasePath);
            commandRunner.run(createCommand, repositoryRoot, properties.getTimeout(), properties.getMaxOutputBytes());
            var analyzeCommand = databaseAnalyzeCommand(databasePath, queryPack, sarifPath);
            commandRunner.run(analyzeCommand, repositoryRoot, properties.getTimeout(), properties.getMaxOutputBytes());
            var sarif = readBounded(sarifPath, properties.getMaxOutputBytes());
            return normalizer
                    .normalize(language.requestedLanguage(), queryPack, sarif, request, toolVersion, derivedAt)
                    .facts();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private List<String> databaseCreateCommand(String language, Path repositoryRoot, Path databasePath) {
        var command = new ArrayList<String>();
        command.add(properties.getCliPath());
        command.add("database");
        command.add("create");
        command.add(databasePath.toString());
        command.add("--language=" + language);
        command.add("--source-root=" + repositoryRoot);
        command.add("--overwrite");
        if (properties.isBuildModeNone()) {
            command.add("--build-mode=none");
        }
        return command;
    }

    private List<String> databaseAnalyzeCommand(Path databasePath, String queryPack, Path sarifPath) {
        return List.of(
                properties.getCliPath(),
                "database",
                "analyze",
                databasePath.toString(),
                queryPack,
                "--format=sarif-latest",
                "--output=" + sarifPath);
    }

    private List<LanguagePlan> languagePlans(DerivationAdapterRequest request) {
        var plans = new LinkedHashMap<String, LanguagePlan>();
        for (String language : request.scope().languages()) {
            var normalized = CodeQlDerivationProperties.normalizeLanguage(language);
            if (!SUPPORTED_LANGUAGES.contains(normalized)) {
                continue;
            }
            var codeQlLanguage = codeQlLanguage(normalized);
            plans.putIfAbsent(codeQlLanguage, new LanguagePlan(normalized, codeQlLanguage));
        }
        return List.copyOf(plans.values());
    }

    private DerivationCaptureLimitDraft captureLimit(
            CaptureLimitReason reason,
            String language,
            DerivationAdapterRequest request,
            String detail,
            Instant capturedAt) {
        return new DerivationCaptureLimitDraft(
                "codeql-derivation",
                reason,
                language,
                "application",
                detail,
                request.scope().commitSha(),
                capturedAt);
    }

    private String resolveToolVersion() {
        try {
            var output = commandRunner.run(
                    List.of(properties.getCliPath(), VERSION_COMMAND, "--format=json"),
                    repositoryRoot(),
                    shortTimeout(properties.getTimeout()),
                    8192);
            var root = objectMapper.readTree(output);
            var version = root.path(VERSION_FIELD).asText("");
            return version.isBlank() ? "unknown" : version;
        } catch (RuntimeException | IOException exception) {
            return "unknown";
        }
    }

    private Path repositoryRoot() {
        return properties.getRepositoryRoot().toAbsolutePath().normalize();
    }

    private String rulesetVersion() {
        var pins = properties.getQueryPacks().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
        return pins.isEmpty() ? "unconfigured" : String.join(",", pins);
    }

    private static String codeQlLanguage(String language) {
        return "typescript".equals(language) ? "javascript" : language;
    }

    private static Duration shortTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return Duration.ofSeconds(5);
        }
        return timeout.compareTo(Duration.ofSeconds(5)) < 0 ? timeout : Duration.ofSeconds(5);
    }

    private static String readBounded(Path path, long maxBytes) throws IOException {
        var bytes = Files.readAllBytes(path);
        if (bytes.length > maxBytes) {
            throw new IOException("CodeQL SARIF output exceeded configured size limit");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Path codeQlWorkRoot(Path repositoryRoot) throws IOException {
        var workRoot =
                repositoryRoot.resolve(CODEQL_WORK_DIRECTORY).toAbsolutePath().normalize();
        Files.createDirectories(workRoot);
        return workRoot;
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // Best-effort cleanup of temp analyzer output; nothing here is persisted.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup of temp analyzer output; nothing here is persisted.
        }
    }

    interface CodeQlCommandRunner {

        boolean canRun(String executable, Duration timeout);

        String run(List<String> command, Path workingDirectory, Duration timeout, long maxOutputBytes);
    }

    private record LanguagePlan(String requestedLanguage, String codeQlLanguage) {}

    static class ProcessCodeQlCommandRunner implements CodeQlCommandRunner {

        @Override
        public boolean canRun(String executable, Duration timeout) {
            try {
                run(List.of(executable, VERSION_COMMAND, "--format=json"), Path.of("."), timeout, 8192);
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }

        @Override
        public String run(List<String> command, Path workingDirectory, Duration timeout, long maxOutputBytes) {
            try {
                var effectiveTimeout =
                        timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofMinutes(10) : timeout;
                var processBuilder = new ProcessBuilder(command);
                processBuilder.directory(workingDirectory.toFile());
                processBuilder.redirectErrorStream(false);
                var process = processBuilder.start();
                var stdout = CompletableFuture.supplyAsync(() -> readLimited(process.getInputStream(), maxOutputBytes));
                var stderr = CompletableFuture.supplyAsync(() -> readLimited(process.getErrorStream(), maxOutputBytes));
                var finished = process.waitFor(Math.max(1, effectiveTimeout.toSeconds()), TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IllegalStateException("CodeQL command timed out");
                }
                var exitCode = process.exitValue();
                if (exitCode != 0) {
                    stderr.join();
                    throw new IllegalStateException("CodeQL command exited with code " + exitCode);
                }
                stderr.join();
                return stdout.join();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("CodeQL command interrupted", exception);
            }
        }

        private static String readLimited(InputStream stream, long maxBytes) {
            try (stream;
                    var output = new ByteArrayOutputStream()) {
                var buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new IllegalStateException("CodeQL output exceeded configured size limit");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}

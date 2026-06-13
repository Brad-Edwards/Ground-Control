package com.keplerops.groundcontrol.infrastructure.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationScope;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeQlDerivationAdapterTest {

    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";
    private static final String BASE_COMMIT = "16792466cf2a1464792846b083d1bd885299b3c";

    @TempDir
    Path repoRoot;

    @Test
    void descriptorDeclaresTheSupportedCodeQlDerivationShape() {
        var adapter = adapter(
                properties(Map.ofEntries(
                        Map.entry("java", "codeql/java-queries@1.10.1"),
                        Map.entry("javascript", "codeql/javascript-queries@2.2.1"),
                        Map.entry("typescript", "codeql/javascript-queries@2.2.1"),
                        Map.entry("python", "codeql/python-queries@1.6.1"))),
                new FakeRunner());

        var descriptor = adapter.descriptor();

        assertThat(descriptor.adapterId()).isEqualTo("codeql-derivation");
        assertThat(descriptor.toolName()).isEqualTo("CodeQL");
        assertThat(descriptor.languages()).containsExactlyInAnyOrder("java", "javascript", "typescript", "python");
        assertThat(descriptor.surfaces()).containsExactly("application");
        assertThat(descriptor.scopeModes())
                .containsExactlyInAnyOrder(
                        DerivationScopeMode.FULL_REPO, DerivationScopeMode.DIFF, DerivationScopeMode.PATH_SET);
        assertThat(descriptor.factKinds())
                .containsExactlyInAnyOrder(
                        SystemModelFactKind.ENTRY_POINT,
                        SystemModelFactKind.DATA_FLOW,
                        SystemModelFactKind.TAINT_PATH,
                        SystemModelFactKind.SECRET_USAGE,
                        SystemModelFactKind.EXTERNAL_INTERACTION);
    }

    @Test
    void unavailableWhenQueryPacksAreNotVersionPinned() {
        var pins = new LinkedHashMap<String, String>();
        pins.put("java", "codeql/java-queries");
        var adapter = adapter(properties(pins), new FakeRunner());

        assertThat(adapter.isAvailable()).isFalse();
    }

    @Test
    void mutableQueryPackSelectorsAreNotPinned() {
        assertThat(CodeQlDerivationProperties.isPinnedQueryPack("codeql/java-queries@1.10.1"))
                .isTrue();
        assertThat(CodeQlDerivationProperties.isPinnedQueryPack("codeql/java-queries@latest"))
                .isFalse();
        assertThat(CodeQlDerivationProperties.isPinnedQueryPack("codeql/java-queries@main"))
                .isFalse();
    }

    @Test
    void deriveRunsCodeQlWithPinnedPackAndNormalizesSarif() {
        var runner = new FakeRunner();
        runner.sarif = sarifForPath("backend/src/main/java/com/example/Controller.java");
        var adapter = adapter(properties(Map.of("java", "codeql/java-queries@1.10.1")), runner);

        var result = adapter.derive(request(new DerivationScope(
                DerivationScopeMode.FULL_REPO, COMMIT, null, List.of(), Set.of("java"), Set.of("application"))));

        assertThat(result.captureLimits()).isEmpty();
        assertThat(result.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.factKind()).isEqualTo(SystemModelFactKind.ENTRY_POINT);
            assertThat(fact.provenance().toolVersion()).isEqualTo("2.23.9");
            assertThat(fact.provenance().rulesetVersion()).isEqualTo("codeql/java-queries@1.10.1");
        });
        assertThat(runner.commands).anySatisfy(command -> {
            assertThat(command).contains("database", "create", "--language=java");
            assertThat(command)
                    .contains("--source-root=" + repoRoot.toAbsolutePath().normalize());
        });
        assertThat(runner.commands).anySatisfy(command -> {
            assertThat(command).contains("database", "analyze", "codeql/java-queries@1.10.1");
            assertThat(command).anyMatch(value -> value.startsWith("--output="));
        });
    }

    @Test
    void deriveFiltersDiffScopeToRequestedPathSurface() {
        var runner = new FakeRunner();
        runner.sarif = sarifForTwoPaths();
        var adapter = adapter(properties(Map.of("java", "codeql/java-queries@1.10.1")), runner);

        var result = adapter.derive(request(new DerivationScope(
                DerivationScopeMode.DIFF,
                COMMIT,
                BASE_COMMIT,
                List.of("backend/src/main/java/com/example/Controller.java"),
                Set.of("java"),
                Set.of("application"))));

        assertThat(result.captureLimits()).isEmpty();
        assertThat(result.facts()).singleElement().satisfies(fact -> assertThat(fact.sourcePath())
                .isEqualTo("backend/src/main/java/com/example/Controller.java"));
    }

    @Test
    void deriveReturnsSanitizedCaptureLimitWhenCodeQlFails() {
        var runner = new FakeRunner();
        runner.failAnalyze = true;
        var adapter = adapter(properties(Map.of("java", "codeql/java-queries@1.10.1")), runner);

        var result = adapter.derive(request(new DerivationScope(
                DerivationScopeMode.FULL_REPO, COMMIT, null, List.of(), Set.of("java"), Set.of("application"))));

        assertThat(result.facts()).isEmpty();
        assertThat(result.captureLimits()).singleElement().satisfies(limit -> {
            assertThat(limit.reason()).isEqualTo(CaptureLimitReason.TOOL_EXECUTION_FAILED);
            assertThat(limit.adapterId()).isEqualTo("codeql-derivation");
            assertThat(limit.detail()).contains("CodeQL execution failed for language java");
            assertThat(limit.detail()).doesNotContain("raw-secret-value");
        });
    }

    private CodeQlDerivationAdapter adapter(CodeQlDerivationProperties properties, FakeRunner runner) {
        properties.setRepositoryRoot(repoRoot);
        properties.setTimeout(Duration.ofSeconds(3));
        return new CodeQlDerivationAdapter(properties, new ObjectMapper(), runner);
    }

    private static CodeQlDerivationProperties properties(Map<String, String> queryPacks) {
        var properties = new CodeQlDerivationProperties();
        properties.setEnabled(true);
        properties.setCliPath("codeql");
        properties.setQueryPacks(queryPacks);
        return properties;
    }

    private static DerivationAdapterRequest request(DerivationScope scope) {
        return new DerivationAdapterRequest(UUID.randomUUID(), "ground-control", scope);
    }

    private static String sarifForPath(String path) {
        return """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": { "driver": { "name": "CodeQL", "rules": [{
                      "id": "java/spring-controller-entry-point",
                      "name": "Spring MVC entry point",
                      "shortDescription": { "text": "HTTP route entry point" },
                      "properties": { "tags": ["entry-point", "route"] }
                    }] } },
                    "results": [{
                      "ruleId": "java/spring-controller-entry-point",
                      "message": { "text": "HTTP route entry point detected." },
                      "locations": [{
                        "physicalLocation": {
                          "artifactLocation": { "uri": "%s" },
                          "region": { "startLine": 42, "startColumn": 5 }
                        }
                      }]
                    }]
                  }]
                }
                """
                .formatted(path);
    }

    private static String sarifForTwoPaths() {
        return """
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": { "driver": { "name": "CodeQL", "rules": [{
                      "id": "java/spring-controller-entry-point",
                      "name": "Spring MVC entry point",
                      "shortDescription": { "text": "HTTP route entry point" },
                      "properties": { "tags": ["entry-point", "route"] }
                    }] } },
                    "results": [
                      {
                        "ruleId": "java/spring-controller-entry-point",
                        "message": { "text": "Backend route." },
                        "locations": [{
                          "physicalLocation": {
                            "artifactLocation": { "uri": "backend/src/main/java/com/example/Controller.java" },
                            "region": { "startLine": 42 }
                          }
                        }]
                      },
                      {
                        "ruleId": "java/spring-controller-entry-point",
                        "message": { "text": "Frontend route." },
                        "locations": [{
                          "physicalLocation": {
                            "artifactLocation": { "uri": "frontend/src/pages/analysis.tsx" },
                            "region": { "startLine": 10 }
                          }
                        }]
                      }
                    ]
                  }]
                }
                """;
    }

    private static class FakeRunner implements CodeQlDerivationAdapter.CodeQlCommandRunner {

        private final List<List<String>> commands = new ArrayList<>();
        private String sarif = sarifForPath("backend/src/main/java/com/example/Controller.java");
        private boolean failAnalyze;

        @Override
        public boolean canRun(String executable, Duration timeout) {
            return true;
        }

        @Override
        public String run(List<String> command, Path workingDirectory, Duration timeout, long maxOutputBytes) {
            commands.add(List.copyOf(command));
            if (command.contains("version")) {
                return "{\"version\":\"2.23.9\"}";
            }
            if (command.contains("analyze")) {
                if (failAnalyze) {
                    throw new IllegalStateException("CodeQL failed with raw-secret-value in stderr");
                }
                var output = command.stream()
                        .filter(value -> value.startsWith("--output="))
                        .map(value -> value.substring("--output=".length()))
                        .findFirst()
                        .orElseThrow();
                try {
                    Files.writeString(Path.of(output), sarif);
                } catch (Exception exception) {
                    throw new IllegalStateException("failed to write fake SARIF", exception);
                }
            }
            return "";
        }
    }
}

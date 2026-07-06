package com.keplerops.groundcontrol.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry-driven backend boundary enforcement (GC-CLD-2). The allowed
 * dependency edges among backend layers are read from the single CLD
 * architecture registry rather than hand-listed here: any ordered pair of
 * backend modules whose edge is not declared in {@code allowed_edges} is
 * asserted to have no dependency. This is the backend arm of the acceptance
 * criterion "a dependency edge not in the registry fails CI in the layer that
 * introduced it"; the frontend and MCP arms read the same registry from
 * {@code tools/policy/checks.py}. Finer semantic rules (Temporal SDK,
 * controller/repository, service placement) remain in {@link ArchitectureTest}
 * because they are not pure module-graph edges.
 */
@AnalyzeClasses(packages = "com.keplerops.groundcontrol", importOptions = ImportOption.DoNotIncludeTests.class)
class RegistryBoundaryArchitectureTest {

    private static final Path REGISTRY_RELATIVE = Paths.get("architecture", "registry", "module-graph.json");

    @ArchTest
    static void backend_modules_respect_registry_allowed_edges(JavaClasses classes) {
        JsonNode registry = loadRegistry();

        Map<String, String> backendPackages = new LinkedHashMap<>();
        for (JsonNode module : registry.get("modules")) {
            if (!"backend".equals(module.path("surface").asText())) {
                continue;
            }
            String id = module.path("id").asText();
            String pkg = module.path("package").asText(null);
            if (pkg == null || pkg.isBlank()) {
                throw new IllegalStateException("backend registry module '" + id + "' must declare a package");
            }
            backendPackages.put(id, pkg);
        }

        Set<String> allowed = new HashSet<>();
        for (JsonNode edge : registry.get("allowed_edges")) {
            allowed.add(edge.path("from").asText() + "->" + edge.path("to").asText());
        }

        for (Map.Entry<String, String> from : backendPackages.entrySet()) {
            for (Map.Entry<String, String> to : backendPackages.entrySet()) {
                if (from.getKey().equals(to.getKey()) || allowed.contains(from.getKey() + "->" + to.getKey())) {
                    continue;
                }
                ArchRule rule = noClasses()
                        .that()
                        .resideInAPackage(from.getValue())
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage(to.getValue())
                        .as("architecture registry: %s must not depend on %s".formatted(from.getKey(), to.getKey()))
                        .because("edge %s -> %s is not declared in architecture/registry/module-graph.json"
                                .formatted(from.getKey(), to.getKey()));
                rule.check(classes);
            }
        }
    }

    private static JsonNode loadRegistry() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path current = start; current != null; current = current.getParent()) {
            Path candidate = current.resolve(REGISTRY_RELATIVE);
            if (Files.isRegularFile(candidate)) {
                try {
                    return new ObjectMapper().readTree(candidate.toFile());
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to read " + candidate, e);
                }
            }
        }
        throw new IllegalStateException("architecture/registry/module-graph.json not found upward from " + start);
    }
}

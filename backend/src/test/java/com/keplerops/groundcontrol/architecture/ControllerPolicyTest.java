package com.keplerops.groundcontrol.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ControllerPolicyTest {

    private static final Path MAIN_ROOT = Path.of("src/main/java");
    private static final Path TEST_ROOT = Path.of("src/test/java");
    private static final Pattern WEBMVCTEST = Pattern.compile("@WebMvcTest\\s*\\(([^)]*)\\)", Pattern.DOTALL);
    // Dotted identifier preceding `.class`, written as disjoint segments so the
    // matcher is linear; a single `[\w.]+\.class` overlaps the `.` and backtracks
    // super-linearly (Sonar S8786).
    private static final Pattern CLASS_LITERAL = Pattern.compile("([\\w$]+(?:\\.[\\w$]+)*)\\.class\\b");
    // Non-static single-type imports only; `import static ...;` has a space the
    // class-token group cannot span, so it never matches here.
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)\\s*;");

    @Test
    void everyControllerHasMatchingWebMvcTest() throws IOException {
        var controllerRoot = MAIN_ROOT.resolve("com/keplerops/groundcontrol/api");

        List<Path> controllers;
        try (Stream<Path> stream = Files.walk(controllerRoot)) {
            controllers = stream.filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                    .toList();
        }

        List<String> testContents = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(TEST_ROOT)) {
            for (Path test : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList()) {
                testContents.add(Files.readString(test));
            }
        }

        for (Path controller : controllers) {
            // Resolve the companion by the controller's fully-qualified class,
            // not its filename stem: the stem collides whenever two packages
            // declare a same-named controller (issue #1167).
            var fqcn = fullyQualifiedName(controller);
            var covered = testContents.stream().anyMatch(content -> testCoversController(content, fqcn));
            assertThat(covered)
                    .as(
                            "Expected controller %s (%s) to have a @WebMvcTest slice resolving to its "
                                    + "fully-qualified class",
                            controller, fqcn)
                    .isTrue();
        }
    }

    private static String fullyQualifiedName(Path controller) {
        var relative = MAIN_ROOT.relativize(controller).toString();
        var withoutExtension = relative.substring(0, relative.length() - ".java".length());
        return withoutExtension.replace(java.io.File.separatorChar, '.').replace('/', '.');
    }

    /**
     * True when a test's {@code @WebMvcTest} annotation resolves to {@code controllerFqcn}. Mirrors
     * Java name binding: a fully-qualified literal matches directly; a simple name binds through the
     * file's single-type import for that name; absent such an import the simple name binds in the
     * file's own package. The import check is what disambiguates same-simple-name controllers in
     * different packages.
     */
    private static boolean testCoversController(String content, String controllerFqcn) {
        Set<String> referenced = new HashSet<>();
        Matcher annotations = WEBMVCTEST.matcher(content);
        while (annotations.find()) {
            Matcher literals = CLASS_LITERAL.matcher(annotations.group(1));
            while (literals.find()) {
                referenced.add(literals.group(1));
            }
        }
        if (referenced.isEmpty()) {
            return false;
        }
        if (referenced.contains(controllerFqcn)) {
            return true;
        }
        var simpleName = controllerFqcn.substring(controllerFqcn.lastIndexOf('.') + 1);
        if (!referenced.contains(simpleName)) {
            return false;
        }
        String bound = null;
        Matcher imports = IMPORT.matcher(content);
        while (imports.find()) {
            var imported = imports.group(1);
            if (imported.substring(imported.lastIndexOf('.') + 1).equals(simpleName)) {
                bound = imported;
                break;
            }
        }
        if (bound != null) {
            return bound.equals(controllerFqcn);
        }
        // No single-type import of the simple name: it binds in the test's own
        // package (or a wildcard import that cannot be resolved statically).
        return true;
    }
}

package com.keplerops.groundcontrol.test.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.function.Executable;

public final class NegativeSuite {

    private NegativeSuite() {}

    public enum Kind {
        AUTHORIZATION,
        INVALID_INPUT,
        PROTOCOL_VIOLATION
    }

    public record Case(String id, Kind kind, Executable exercise, Class<? extends Throwable> expectedError) {

        public Case {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("negative case id must not be blank");
            }
            if (kind == null) {
                throw new IllegalArgumentException("negative case kind must not be null");
            }
            if (exercise == null) {
                throw new IllegalArgumentException("negative case exercise must not be null");
            }
            if (expectedError == null) {
                throw new IllegalArgumentException("negative case expected error must not be null");
            }
        }
    }

    public static Case caseExpecting(
            String id, Kind kind, Executable exercise, Class<? extends Throwable> expectedError) {
        return new Case(id, kind, exercise, expectedError);
    }

    public static List<DynamicTest> dynamicTests(Collection<Case> cases) {
        var pinnedCases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        if (pinnedCases.isEmpty()) {
            throw new IllegalArgumentException("negative suite needs at least one case");
        }
        return pinnedCases.stream()
                .map(testCase -> DynamicTest.dynamicTest(
                        testCase.kind().name().toLowerCase(Locale.ROOT) + " :: " + testCase.id(),
                        () -> assertRejects(testCase)))
                .toList();
    }

    public static void assertRejects(Case testCase) throws Throwable {
        try {
            testCase.exercise().execute();
        } catch (Throwable actual) {
            assertThat(actual).as(testCase.id()).isInstanceOf(testCase.expectedError());
            return;
        }

        throw new AssertionError("Negative case accepted unexpectedly: " + testCase.id());
    }
}

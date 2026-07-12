package com.keplerops.groundcontrol.test.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.junit.jupiter.api.DynamicTest;

public record GoldenCorpus<I, O>(String name, List<Case<I, O>> cases) {

    public GoldenCorpus {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("corpus name must not be blank");
        }
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("golden corpus needs at least one case");
        }
    }

    public int pinnedCount() {
        return cases.size();
    }

    public List<DynamicTest> dynamicTests(Function<? super I, ? extends O> renderer) {
        return cases.stream()
                .map(testCase -> DynamicTest.dynamicTest(
                        name + " :: " + testCase.id(), () -> assertThat(renderer.apply(testCase.input()))
                                .as(testCase.id())
                                .isEqualTo(testCase.expected())))
                .toList();
    }

    public record Case<I, O>(String id, I input, O expected) {

        public Case {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("golden case id must not be blank");
            }
        }
    }
}

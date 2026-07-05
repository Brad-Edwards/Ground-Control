package com.keplerops.groundcontrol.test.oracle;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public interface AbstractPortConformanceSuite<T> {

    List<PortImplementation<T>> implementations();

    default Stream<DynamicTest> conformanceCase(String behavior, PortAssertion<T> assertion) {
        if (behavior == null || behavior.isBlank()) {
            throw new IllegalArgumentException("conformance behavior must not be blank");
        }
        Objects.requireNonNull(assertion, "assertion");
        var implementations = List.copyOf(Objects.requireNonNull(implementations(), "implementations"));
        if (implementations.isEmpty()) {
            throw new IllegalStateException("conformance suite needs at least one implementation");
        }
        return implementations.stream()
                .map(implementation -> DynamicTest.dynamicTest(
                        implementation.name() + " :: " + behavior, () -> assertion.verify(implementation.create())));
    }

    @FunctionalInterface
    interface PortAssertion<T> {
        void verify(T port) throws Exception;
    }
}

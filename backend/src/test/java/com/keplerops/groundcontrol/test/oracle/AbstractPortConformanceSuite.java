package com.keplerops.groundcontrol.test.oracle;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public abstract class AbstractPortConformanceSuite<T> {

    protected abstract List<PortImplementation<T>> implementations();

    protected Stream<DynamicTest> conformanceCase(String behavior, PortAssertion<T> assertion) {
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
    protected interface PortAssertion<T> {
        void verify(T port) throws Exception;
    }
}

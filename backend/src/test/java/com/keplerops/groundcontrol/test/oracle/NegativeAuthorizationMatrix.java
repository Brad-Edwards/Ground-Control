package com.keplerops.groundcontrol.test.oracle;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.function.Executable;

/**
 * GC-O014 negative-authorization matrix helper. Endpoint tests provide the
 * concrete MockMvc assertions; this helper makes the required anonymous,
 * wrong-role, and cross-project cases explicit and repeatable per endpoint
 * class.
 */
public final class NegativeAuthorizationMatrix {

    private NegativeAuthorizationMatrix() {}

    public record EndpointClass(
            String id, Executable anonymousDenied, Executable wrongRoleDenied, Executable crossProjectDenied) {

        public EndpointClass {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("endpoint class id must not be blank");
            }
            Objects.requireNonNull(anonymousDenied, "anonymousDenied");
            Objects.requireNonNull(wrongRoleDenied, "wrongRoleDenied");
            Objects.requireNonNull(crossProjectDenied, "crossProjectDenied");
        }
    }

    public static EndpointClass endpointClass(
            String id, Executable anonymousDenied, Executable wrongRoleDenied, Executable crossProjectDenied) {
        return new EndpointClass(id, anonymousDenied, wrongRoleDenied, crossProjectDenied);
    }

    public static Stream<DynamicTest> dynamicTests(List<EndpointClass> endpointClasses) {
        var classes = List.copyOf(Objects.requireNonNull(endpointClasses, "endpointClasses"));
        if (classes.isEmpty()) {
            throw new IllegalArgumentException("negative authorization matrix needs at least one endpoint class");
        }
        return classes.stream().flatMap(NegativeAuthorizationMatrix::testsFor);
    }

    private static Stream<DynamicTest> testsFor(EndpointClass endpointClass) {
        return Stream.of(
                DynamicTest.dynamicTest(endpointClass.id() + " :: anonymous denied", endpointClass.anonymousDenied()),
                DynamicTest.dynamicTest(endpointClass.id() + " :: wrong role denied", endpointClass.wrongRoleDenied()),
                DynamicTest.dynamicTest(
                        endpointClass.id() + " :: cross-project denied", endpointClass.crossProjectDenied()));
    }
}

package com.keplerops.groundcontrol.test.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;

public final class DifferentialOracle {

    private DifferentialOracle() {}

    public static <I, O> void assertEquivalent(
            String boundaryId,
            I input,
            Function<? super I, ? extends O> referenceModel,
            Function<? super I, ? extends O> implementation) {
        var expected = referenceModel.apply(input);
        var actual = implementation.apply(input);

        assertThat(actual).as(boundaryId).isEqualTo(expected);
    }
}

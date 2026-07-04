package com.keplerops.groundcontrol.test.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public final class OracleInvariants {

    private OracleInvariants() {}

    public static <T> void assertIdempotent(String invariantId, T input, UnaryOperator<T> operation) {
        var once = operation.apply(input);
        var twice = operation.apply(once);

        assertThat(twice).as(invariantId).isEqualTo(once);
    }

    public static <I, W> void assertRoundTrip(
            String invariantId,
            I input,
            Function<? super I, ? extends W> encode,
            Function<? super W, ? extends I> decode) {
        assertThat(decode.apply(encode.apply(input))).as(invariantId).isEqualTo(input);
    }

    public static <T> void assertOrdered(String invariantId, List<T> values, Comparator<? super T> comparator) {
        assertThat(values).as(invariantId).isSortedAccordingTo(comparator);
    }
}

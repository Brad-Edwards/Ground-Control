package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.test.oracle.AsOfShapedNameMatcher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AsOfShapedNameMatcherTest {

    @ParameterizedTest
    @ValueSource(
            strings = {"asOf", "as_of", "AS_OF", "as-of", "asOfDate", "as_of_date", "AS_OF_TIMESTAMP", "asOfTimestamp"})
    void matchesAsOfShapedNames(String name) {
        assertThat(AsOfShapedNameMatcher.matches(name))
                .as("expected '%s' to be as-of shaped", name)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "assessment_result_id",
                "aliasOf",
                "biasOfMeasurement",
                "project",
                "title",
                "min_effectiveness",
                "revisionNumber",
                "assetType"
            })
    void doesNotMatchUnrelatedNames(String name) {
        assertThat(AsOfShapedNameMatcher.matches(name))
                .as("expected '%s' NOT to be as-of shaped", name)
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void doesNotMatchBlank(String name) {
        assertThat(AsOfShapedNameMatcher.matches(name)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void doesNotMatchNull() {
        assertThat(AsOfShapedNameMatcher.matches(null)).isFalse();
    }
}

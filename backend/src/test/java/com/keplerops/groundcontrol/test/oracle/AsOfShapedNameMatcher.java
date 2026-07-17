package com.keplerops.groundcontrol.test.oracle;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Detects field/parameter names shaped like a caller-supplied as-of coordinate ({@code asOf},
 * {@code as_of}, {@code AS_OF_DATE}, {@code asOfTimestamp}, ...). ADR-084 §5: the canonical as-of
 * coordinate is the Envers revision resolved by {@code AsOfRevisionResolver}; no request surface
 * may accept a caller-supplied as-of value that could bypass it.
 *
 * <p>Shared by {@code OpenApiAsOfParameterGuardTest} (integration — scans the real generated
 * OpenAPI spec) and its own fast unit test, so the matching logic itself stays covered without a
 * database (the Sonar CI job does not run Testcontainers).
 *
 * <p>Matching is token-based, not a raw substring search: the name is split on camelCase
 * boundaries, underscores, and hyphens, and the predicate looks for an adjacent {@code as}/{@code
 * of} token pair. A raw substring search for {@code "asof"} would false-positive on unrelated
 * names like {@code aliasOf} or {@code biasOfMeasurement} ("...ias" + "Of" contains the literal
 * substring "asOf").
 */
public final class AsOfShapedNameMatcher {

    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[_-]+");

    private AsOfShapedNameMatcher() {}

    public static boolean matches(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String[] tokens = TOKEN_SEPARATOR.split(
                CAMEL_BOUNDARY.matcher(name).replaceAll("$1_$2").toLowerCase(Locale.ROOT));
        for (int i = 0; i < tokens.length - 1; i++) {
            if ("as".equals(tokens[i]) && "of".equals(tokens[i + 1])) {
                return true;
            }
        }
        return false;
    }
}

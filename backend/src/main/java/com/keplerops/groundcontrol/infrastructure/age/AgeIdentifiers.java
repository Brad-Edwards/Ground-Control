package com.keplerops.groundcontrol.infrastructure.age;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.regex.Pattern;

/**
 * Allowlist validation for AGE identifiers that are embedded into SQL/Cypher text as literals
 * (graph names, node labels, relationship types). Per ADR-032 these tokens cannot be
 * parameter-bound, so they must come from configuration, generated names, enums, or allowlists
 * and be validated before execution. Centralized here so the AGE adapter
 * ({@link AgeGraphService}) and the snapshot cleaner ({@link AgeSnapshotCleaner}) share one
 * canonical policy and cannot drift apart.
 */
final class AgeIdentifiers {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private AgeIdentifiers() {}

    /**
     * Validate a token embedded in SQL/Cypher text as an identifier. Allows alphanumerics plus
     * {@code _} and {@code -} (some entity-type names use hyphens). The allowlist is a hard
     * requirement, not defense in depth, because these identifiers reach AGE as part of a SQL
     * literal and cannot be parameter-bound.
     */
    static String validateGraphName(String name) {
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new DomainValidationException("Invalid graph identifier: " + name);
        }
        return name;
    }
}

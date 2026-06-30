package com.keplerops.groundcontrol.domain.dataclassification.state;

/**
 * Provenance of the active data classification lattice for a project (GC-GRC-006).
 *
 * <p>{@code DEFAULT} means no project-scoped policy is stored and the shipped default taxonomy is in
 * effect. {@code CUSTOM} means an operator replaced it via the configuration surface (GC-GRC-023).
 */
public enum DataClassificationSource {
    DEFAULT,
    CUSTOM
}

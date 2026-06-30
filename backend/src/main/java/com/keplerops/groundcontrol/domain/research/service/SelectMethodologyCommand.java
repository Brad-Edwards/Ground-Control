package com.keplerops.groundcontrol.domain.research.service;

/**
 * GC-RSCH-F006 / ADR-077 — select (or re-select) the active methodology for a
 * research run. The command carries only the {@code methodKey}; the label,
 * profile/catalog version, and required-source set are all derived from the
 * backend-owned methodology catalog ({@link MethodologyCatalog}) at selection
 * time, never supplied by the caller.
 */
public record SelectMethodologyCommand(String methodKey) {}

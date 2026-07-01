package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F006 / ADR-078 — one required primary methodology source within a
 * {@link MethodProfile}. A source is identified by its provider-neutral
 * {@code ref} (a Zotero key, DOI, or stable identifier) and carries a bounded
 * human-readable {@code title}. Immutable backend reference data loaded from the
 * methodology catalog; never persisted directly.
 */
public record MethodProfileSource(String ref, String title) {}

package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-R003 / ADR-063 — whether a run-scoped gate is still awaiting its
 * decision ({@code PENDING}) or has a durable decision recorded
 * ({@code RESOLVED}). A {@code DISABLED}-behavior gate is created already
 * {@code RESOLVED}.
 */
public enum ResearchGateStatus {
    PENDING,
    RESOLVED
}

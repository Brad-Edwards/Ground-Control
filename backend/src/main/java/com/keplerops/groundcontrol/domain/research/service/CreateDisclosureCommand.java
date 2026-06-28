package com.keplerops.groundcontrol.domain.research.service;

import java.util.UUID;

/**
 * Create the final-manuscript disclosure for a run (GC-RSCH-N013, ADR-068 §4).
 * {@code finalArtifactId} / {@code finalAttemptNo} pin the manuscript the
 * disclosure covers; the two {@code declaredNone} flags assert that a family has
 * nothing to disclose. The actor is taken from the authenticated server context
 * (ADR-026), not this command.
 */
public record CreateDisclosureCommand(
        UUID finalArtifactId,
        Integer finalAttemptNo,
        boolean aiPartsDeclaredNone,
        boolean uncertaintyDeclaredNone,
        boolean humanApprovalsDeclaredNone) {}

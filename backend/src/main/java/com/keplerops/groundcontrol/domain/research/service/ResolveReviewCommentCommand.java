package com.keplerops.groundcontrol.domain.research.service;

/**
 * Resolve an open review comment (GC-RSCH-F034, ADR-067). {@code
 * resolutionSummary} is a bounded note. The resolving actor is taken from the
 * authenticated server context (ADR-026), not this command.
 */
public record ResolveReviewCommentCommand(String resolutionSummary) {}

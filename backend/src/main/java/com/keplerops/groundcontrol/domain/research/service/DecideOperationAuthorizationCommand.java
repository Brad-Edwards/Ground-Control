package com.keplerops.groundcontrol.domain.research.service;

/**
 * GC-RSCH-R005 / ADR-084 §3 — an admin/operator decision on a proposed research
 * high-risk operation authorization. {@code approve=false} denies. The deciding
 * actor is taken from the authenticated server context, not this command.
 */
public record DecideOperationAuthorizationCommand(boolean approve, String note) {}

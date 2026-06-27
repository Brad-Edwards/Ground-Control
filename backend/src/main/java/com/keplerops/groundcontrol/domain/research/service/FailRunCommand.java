package com.keplerops.groundcontrol.domain.research.service;

/**
 * Move a run to FAILED with a bounded failure observation (GC-RSCH-N007 /
 * ADR-064 §6). {@code errorCode} is a stable code, {@code errorClass} the
 * retryability class (for example {@code RETRYABLE} / {@code NON_RETRYABLE} /
 * {@code PERMANENT}), and {@code errorSummary} a short safe summary — never a
 * stack trace, raw provider payload, or research content.
 */
public record FailRunCommand(String errorCode, String errorClass, String errorSummary) {}

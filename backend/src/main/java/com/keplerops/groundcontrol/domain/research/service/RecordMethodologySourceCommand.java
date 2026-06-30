package com.keplerops.groundcontrol.domain.research.service;

import java.util.UUID;

/**
 * GC-RSCH-F006 — record an optional (additional) methodology source on the active
 * selection. Required sources are derived from the selected method's catalog
 * profile and snapshotted immutably at selection time (ADR-077); sources recorded
 * here are always optional ({@code required=false}).
 * {@code selectionId} is optional (null means "use active selection").
 */
public record RecordMethodologySourceCommand(UUID selectionId, String sourceRef, String sourceLabel) {}

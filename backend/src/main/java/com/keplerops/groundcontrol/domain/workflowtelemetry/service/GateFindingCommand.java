package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingDisposition;
import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingSourceKind;

/**
 * Immutable command for one finding in an attempt's batch (issue #1355).
 *
 * <p>Carries bounded facts only. There is deliberately no field for a title, body, remediation
 * text, path, or line: the command shape is where that exclusion is enforced for every caller, so
 * prose cannot reach the projection even if an emitter tried to send it.
 *
 * <p>{@code category}, {@code severity}, and {@code classification} are nullable because a source
 * that cannot attest one must omit it — Codex review findings carry no severity, and defaulting
 * one would fabricate a distribution.
 */
public record GateFindingCommand(
        String findingKey,
        FindingSourceKind sourceKind,
        String sourceId,
        String category,
        String severity,
        String classification,
        FindingDisposition disposition) {}

package com.keplerops.groundcontrol.domain.workflowtelemetry;

/**
 * Whether a finding came from a reviewer or a detector (issue #1355).
 *
 * <p>Reviewers ({@code core}, {@code security}, {@code test-quality}) exercise judgement; detectors
 * ({@code sonarcloud}, {@code spotbugs}, {@code vale}, {@code policy}, {@code ci}) run a rule set.
 * Keeping them distinct stops a scanner from being reported as a reviewer, and stops a non-review
 * gate from being assigned a fabricated reviewer so that it fits a per-reviewer aggregate.
 */
public enum FindingSourceKind {
    REVIEWER,
    DETECTOR
}

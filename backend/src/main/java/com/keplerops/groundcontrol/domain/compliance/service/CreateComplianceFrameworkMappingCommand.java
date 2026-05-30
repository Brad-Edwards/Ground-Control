package com.keplerops.groundcontrol.domain.compliance.service;

import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import java.util.UUID;

/**
 * Command record for creating a {@code ComplianceFrameworkMapping}. Exactly one
 * of {@code requirementId} / {@code controlId} must be set; the service
 * validates the XOR before reaching the repository.
 */
public record CreateComplianceFrameworkMappingCommand(
        UUID projectId,
        UUID requirementId,
        UUID controlId,
        ComplianceFrameworkIdentifier framework,
        String frameworkIdentifier,
        String frameworkVersion,
        String frameworkElement,
        CoverageLevel coverageLevel,
        String rationale) {}

package com.keplerops.groundcontrol.api.compliance;

import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import jakarta.validation.constraints.Size;

/**
 * Update-request body for ComplianceFrameworkMapping. All fields are optional
 * (null means "no change"); the source endpoint side (requirement vs control)
 * is immutable after creation — callers delete and re-create to change it.
 */
public record UpdateComplianceFrameworkMappingRequest(
        ComplianceFrameworkIdentifier framework,
        @Size(max = 200) String frameworkIdentifier,
        @Size(max = 60) String frameworkVersion,
        @Size(max = 200) String frameworkElement,
        CoverageLevel coverageLevel,
        String rationale) {}

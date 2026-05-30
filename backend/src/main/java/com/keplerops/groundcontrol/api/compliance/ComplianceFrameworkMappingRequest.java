package com.keplerops.groundcontrol.api.compliance;

import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Create-request body for ComplianceFrameworkMapping. Exactly one of
 * {@code requirementId} / {@code controlId} must be supplied — the service
 * layer validates the XOR before persistence (per GC-I002 / GC-I005 split).
 */
public record ComplianceFrameworkMappingRequest(
        UUID requirementId,
        UUID controlId,
        @NotNull ComplianceFrameworkIdentifier framework,
        @Size(max = 200) String frameworkIdentifier,
        @Size(max = 60) String frameworkVersion,
        @NotBlank @Size(max = 200) String frameworkElement,
        @NotNull CoverageLevel coverageLevel,
        String rationale) {}

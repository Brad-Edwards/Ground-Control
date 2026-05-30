package com.keplerops.groundcontrol.api.compliance;

import com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import java.time.Instant;
import java.util.UUID;

/**
 * API DTO for ComplianceFrameworkMapping (GC-I002 / GC-I005). Decouples the
 * public JSON contract from the domain entity so future domain refactors do
 * not silently change the wire shape.
 */
public record ComplianceFrameworkMappingResponse(
        UUID id,
        UUID projectId,
        UUID requirementId,
        UUID controlId,
        ComplianceFrameworkIdentifier framework,
        String frameworkIdentifier,
        String frameworkVersion,
        String frameworkElement,
        CoverageLevel coverageLevel,
        String rationale,
        Instant createdAt,
        Instant updatedAt) {

    public static ComplianceFrameworkMappingResponse from(ComplianceFrameworkMapping mapping) {
        return new ComplianceFrameworkMappingResponse(
                mapping.getId(),
                mapping.getProject().getId(),
                mapping.getRequirement() != null ? mapping.getRequirement().getId() : null,
                mapping.getControl() != null ? mapping.getControl().getId() : null,
                mapping.getFramework(),
                mapping.getFrameworkIdentifier(),
                mapping.getFrameworkVersion(),
                mapping.getFrameworkElement(),
                mapping.getCoverageLevel(),
                mapping.getRationale(),
                mapping.getCreatedAt(),
                mapping.getUpdatedAt());
    }
}

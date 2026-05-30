package com.keplerops.groundcontrol.domain.compliance.service;

import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import java.util.UUID;

/**
 * Command record for updating a {@code ComplianceFrameworkMapping}. The source
 * endpoint (requirement vs control) is immutable post-create — service callers
 * delete and re-create to change endpoints. Null command fields mean "no
 * change" so partial updates can target only the rationale or coverage level.
 */
public record UpdateComplianceFrameworkMappingCommand(
        UUID projectId,
        UUID mappingId,
        ComplianceFrameworkIdentifier framework,
        String frameworkIdentifier,
        String frameworkVersion,
        String frameworkElement,
        CoverageLevel coverageLevel,
        String rationale) {}

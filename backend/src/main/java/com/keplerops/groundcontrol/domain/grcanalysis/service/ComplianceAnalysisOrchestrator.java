package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.GapSeverity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin orchestrator grouping the two GC-I002 / GC-I007 compliance analysis
 * services ({@link CompliancePostureService} and {@link CrossFrameworkGapService})
 * behind a single injectable facade. This keeps {@link GrcAnalysisService} below
 * the Monster-Class coupling threshold while each service retains its own
 * transactional boundary.
 */
@Service
@Transactional(readOnly = true)
public class ComplianceAnalysisOrchestrator {

    private final CompliancePostureService compliancePostureService;
    private final CrossFrameworkGapService crossFrameworkGapService;

    public ComplianceAnalysisOrchestrator(
            CompliancePostureService compliancePostureService, CrossFrameworkGapService crossFrameworkGapService) {
        this.compliancePostureService = compliancePostureService;
        this.crossFrameworkGapService = crossFrameworkGapService;
    }

    public CompliancePostureResult posture(UUID projectId, Instant asOf, ComplianceFrameworkIdentifier framework) {
        return compliancePostureService.analyze(projectId, asOf, framework);
    }

    public CrossFrameworkGapResult gap(
            UUID projectId, Instant asOf, ComplianceFrameworkIdentifier framework, GapSeverity minSeverity) {
        return crossFrameworkGapService.analyze(projectId, asOf, framework, minSeverity);
    }
}

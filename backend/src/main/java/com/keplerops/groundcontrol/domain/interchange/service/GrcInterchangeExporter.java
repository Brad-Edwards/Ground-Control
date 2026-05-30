package com.keplerops.groundcontrol.domain.interchange.service;

import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.interchange.payload.GrcInterchangeBundle;
import com.keplerops.groundcontrol.domain.interchange.payload.GrcInterchangeBundle.AssetPayload;
import com.keplerops.groundcontrol.domain.interchange.payload.GrcInterchangeBundle.ControlPayload;
import com.keplerops.groundcontrol.domain.interchange.payload.GrcInterchangeBundle.EvidenceArtifactPayload;
import com.keplerops.groundcontrol.domain.interchange.payload.GrcInterchangeBundle.FindingPayload;
import com.keplerops.groundcontrol.domain.interchange.payload.GrcInterchangeBundle.RiskScenarioPayload;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walks each project-scoped GRC domain aggregate into the
 * {@link GrcInterchangeBundle} envelope per GC-P012.
 */
@Service
@Transactional(readOnly = true)
public class GrcInterchangeExporter {

    private final ProjectService projectService;
    private final OperationalAssetRepository operationalAssetRepository;
    private final RiskScenarioRepository riskScenarioRepository;
    private final ControlRepository controlRepository;
    private final FindingRepository findingRepository;
    private final EvidenceArtifactRepository evidenceArtifactRepository;

    public GrcInterchangeExporter(
            ProjectService projectService,
            OperationalAssetRepository operationalAssetRepository,
            RiskScenarioRepository riskScenarioRepository,
            ControlRepository controlRepository,
            FindingRepository findingRepository,
            EvidenceArtifactRepository evidenceArtifactRepository) {
        this.projectService = projectService;
        this.operationalAssetRepository = operationalAssetRepository;
        this.riskScenarioRepository = riskScenarioRepository;
        this.controlRepository = controlRepository;
        this.findingRepository = findingRepository;
        this.evidenceArtifactRepository = evidenceArtifactRepository;
    }

    public GrcInterchangeBundle export(UUID projectId) {
        var project = projectService.getById(projectId);
        List<AssetPayload> assets = operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId).stream()
                .map(a -> new AssetPayload(
                        a.getUid(),
                        a.getName(),
                        a.getAssetType() != null ? a.getAssetType().name() : null,
                        a.getSubtype(),
                        a.getDescription(),
                        a.getOwner(),
                        a.getSteward(),
                        a.getEnvironment() != null ? a.getEnvironment().name() : null,
                        a.getCriticality() != null ? a.getCriticality().name() : null,
                        null,
                        a.getCreatedAt(),
                        a.getUpdatedAt()))
                .toList();
        List<RiskScenarioPayload> scenarios =
                riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                        .map(r -> new RiskScenarioPayload(
                                r.getUid(),
                                r.getTitle(),
                                r.getThreat(),
                                r.getStatus() != null ? r.getStatus().name() : null,
                                null,
                                r.getCreatedAt(),
                                r.getUpdatedAt()))
                        .toList();
        List<ControlPayload> controls = controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(c -> new ControlPayload(
                        c.getUid(),
                        c.getTitle(),
                        c.getDescription(),
                        // The bundle's controlType slot is a structural taxonomy field
                        // (preventive/detective/corrective + free-form category), NOT
                        // the lifecycle status. Mapping c.getStatus() here would advertise
                        // DRAFT/ACTIVE/RETIRED as the control type and corrupt round-trip
                        // through a future controls importer.
                        resolveControlType(c.getControlFunction(), c.getCategory()),
                        null,
                        c.getCreatedAt(),
                        c.getUpdatedAt()))
                .toList();
        List<FindingPayload> findings = findingRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(f -> new FindingPayload(
                        f.getUid(),
                        f.getTitle(),
                        f.getSeverity() != null ? f.getSeverity().name() : null,
                        f.getStatus() != null ? f.getStatus().name() : null,
                        f.getDescription(),
                        null,
                        f.getCreatedAt(),
                        f.getUpdatedAt()))
                .toList();
        List<EvidenceArtifactPayload> evidence =
                evidenceArtifactRepository.findByProjectIdOrderByDerivedAtDesc(projectId).stream()
                        .map(e -> new EvidenceArtifactPayload(
                                e.getUid(),
                                e.getTitle(),
                                e.getEvidenceType() != null
                                        ? e.getEvidenceType().name()
                                        : null,
                                null,
                                null,
                                e.getCreatedAt(),
                                e.getUpdatedAt()))
                        .toList();
        return new GrcInterchangeBundle(
                GrcInterchangeBundle.CURRENT_VERSION,
                Instant.now(),
                project.getIdentifier(),
                assets,
                scenarios,
                controls,
                findings,
                evidence);
    }

    /**
     * Compose the interchange {@code controlType} string from the Control
     * taxonomy fields. The bundle's controlType is a structural classifier — a
     * Control's {@link ControlFunction} (PREVENTIVE/DETECTIVE/CORRECTIVE) plus
     * its optional free-form {@code category} — and is intentionally distinct
     * from the lifecycle {@code status}. Encoded as {@code "FUNCTION:category"}
     * when both are present, or whichever component is set when only one is,
     * or {@code null} when neither is populated so importers see an unknown
     * type rather than a misleading default.
     */
    private static String resolveControlType(ControlFunction function, String category) {
        String functionName = function != null ? function.name() : null;
        String trimmedCategory = (category != null && !category.isBlank()) ? category.trim() : null;
        if (functionName != null && trimmedCategory != null) {
            return functionName + ":" + trimmedCategory;
        }
        if (functionName != null) {
            return functionName;
        }
        return trimmedCategory;
    }
}

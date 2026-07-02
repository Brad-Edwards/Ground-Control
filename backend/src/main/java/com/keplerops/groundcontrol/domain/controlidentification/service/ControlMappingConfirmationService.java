package com.keplerops.groundcontrol.domain.controlidentification.service;

import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.service.CreateRiskControlMappingCommand;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlMappingService;
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModelLink;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelLinkRepository;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import com.keplerops.groundcontrol.domain.threatmodels.service.CreateThreatModelLinkCommand;
import com.keplerops.groundcontrol.domain.threatmodels.service.ThreatModelLinkService;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkTargetType;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and reads confirmed threat→control coverage for GC-GRC-008 (clause c). A candidate control is
 * only a deterministic suggestion; confirming it records the relationship through <em>both</em>
 * canonical mapping aggregates so coverage is graph-queryable two ways:
 *
 * <ul>
 *   <li>{@code RiskControlMapping} (via {@link RiskControlMappingService}) — the queryable coverage edge;
 *   <li>{@code ThreatModelLink} {@code MITIGATED_BY} → {@code CONTROL} (via {@link ThreatModelLinkService})
 *       — the threat-owned traversal edge.
 * </ul>
 *
 * <p>Confirmation is idempotent: re-confirming an already-recorded pair returns the existing edge ids
 * rather than raising a conflict, and a partially recorded pair (only one edge present) is completed.
 */
@Service
@Transactional
public class ControlMappingConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ControlMappingConfirmationService.class);

    private final ControlIdentificationService identificationService;
    private final RiskControlMappingService riskControlMappingService;
    private final RiskControlMappingRepository riskControlMappingRepository;
    private final ThreatModelLinkService threatModelLinkService;
    private final ThreatModelLinkRepository threatModelLinkRepository;
    private final ThreatModelRepository threatModelRepository;
    private final ControlRepository controlRepository;

    public ControlMappingConfirmationService(
            ControlIdentificationService identificationService,
            RiskControlMappingService riskControlMappingService,
            RiskControlMappingRepository riskControlMappingRepository,
            ThreatModelLinkService threatModelLinkService,
            ThreatModelLinkRepository threatModelLinkRepository,
            ThreatModelRepository threatModelRepository,
            ControlRepository controlRepository) {
        this.identificationService = identificationService;
        this.riskControlMappingService = riskControlMappingService;
        this.riskControlMappingRepository = riskControlMappingRepository;
        this.threatModelLinkService = threatModelLinkService;
        this.threatModelLinkRepository = threatModelLinkRepository;
        this.threatModelRepository = threatModelRepository;
        this.controlRepository = controlRepository;
    }

    /**
     * Confirm a catalog control as a mitigation of a threat, recording both canonical mapping edges.
     * Idempotent — an already-recorded edge is left as-is and its id returned.
     *
     * <p>Only a control the deterministic mapping engine actually selects for this threat may be
     * confirmed here (re-derived server-side from the threat's STRIDE category): this proves the
     * recorded mitigation is framework-derived, not LLM-invented or forged. A control that is not an
     * engine candidate is rejected with a {@link DomainValidationException}; callers wanting a
     * non-derived mapping use the generic risk-control-mapping surface.
     *
     * @param controlRole optional mapping role; defaults to {@link MappingControlRole#PREVENTIVE}
     */
    public ControlMappingConfirmation confirm(
            UUID projectId,
            UUID threatModelId,
            UUID controlId,
            MappingControlRole controlRole,
            String mappingObjective,
            String mappingScope) {
        var role = controlRole != null ? controlRole : MappingControlRole.PREVENTIVE;
        var threat = threatModelRepository
                .findByIdAndProjectId(threatModelId, projectId)
                .orElseThrow(() -> new NotFoundException("Threat model not found: " + threatModelId));
        var control = controlRepository
                .findByIdAndProjectId(controlId, projectId)
                .orElseThrow(() -> new NotFoundException("Control not found in project: " + controlId));

        // Auditability guard (GC-GRC-008 clause c, GC-RS-012): only a control the deterministic mapping
        // engine actually selects for this threat may be confirmed through this route. This proves the
        // recorded mitigation is framework-derived rather than an LLM-invented or forged coverage edge —
        // callers wanting a non-derived mapping use the generic risk-control-mapping surface instead.
        var candidate = matchingCandidate(projectId, threat, controlId)
                .orElseThrow(() -> new DomainValidationException(
                        "Control " + control.getUid() + " is not a GC-GRC-008 candidate for threat "
                                + threat.getUid()
                                + "; confirm only records controls the deterministic mapping engine selected",
                        "not_a_control_candidate",
                        java.util.Map.of("threatUid", threat.getUid(), "controlUid", control.getUid())));
        // Carry the derived objective as provenance on the recorded mapping when the caller supplied none.
        var objective = mappingObjective != null ? mappingObjective : candidate.objectiveKey();

        boolean mappingExists = riskControlMappingRepository.existsByControlIdAndThreatModelIdAndOperationalAssetId(
                controlId, threatModelId, null);
        UUID mappingId;
        boolean mappingCreated;
        if (mappingExists) {
            mappingId = existingMappingId(projectId, threatModelId, controlId);
            mappingCreated = false;
        } else {
            var command = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    null,
                    null,
                    threatModelId,
                    null,
                    objective,
                    role,
                    mappingScope,
                    null,
                    null);
            mappingId = riskControlMappingService.create(command).getId();
            mappingCreated = true;
        }

        boolean linkExists = threatModelLinkRepository.existsByThreatModelIdAndTargetTypeAndTargetEntityIdAndLinkType(
                threatModelId, ThreatModelLinkTargetType.CONTROL, controlId, ThreatModelLinkType.MITIGATED_BY);
        UUID linkId;
        boolean linkCreated;
        if (linkExists) {
            linkId = existingLinkId(threatModelId, controlId);
            linkCreated = false;
        } else {
            var linkCommand = new CreateThreatModelLinkCommand(
                    ThreatModelLinkTargetType.CONTROL,
                    controlId,
                    null,
                    ThreatModelLinkType.MITIGATED_BY,
                    null,
                    control.getTitle());
            linkId = threatModelLinkService
                    .create(projectId, threatModelId, linkCommand)
                    .getId();
            linkCreated = true;
        }

        log.info(
                "control_mapping_confirmed: project={} threat={} control={} mapping={}(new={}) link={}(new={})",
                projectId,
                threatModelId,
                control.getUid(),
                mappingId,
                mappingCreated,
                linkId,
                linkCreated);
        return new ControlMappingConfirmation(mappingId, linkId, mappingCreated, linkCreated);
    }

    /**
     * The controls recorded as covering a threat, from both canonical mapping aggregates
     * (GC-GRC-008 acceptance: "which controls cover threat X"). Ordered by control UID.
     */
    @Transactional(readOnly = true)
    public ThreatControlCoverage controlsCoveringThreat(UUID projectId, UUID threatModelId) {
        if (!threatModelRepository.existsByIdAndProjectId(threatModelId, projectId)) {
            throw new NotFoundException("Threat model not found: " + threatModelId);
        }

        Set<UUID> viaMapping = new LinkedHashSet<>();
        for (var mapping : riskControlMappingRepository.findByProjectIdAndThreatModelId(projectId, threatModelId)) {
            var controlId = mappingControlId(mapping);
            if (controlId != null) {
                viaMapping.add(controlId);
            }
        }

        Set<UUID> viaLink = new LinkedHashSet<>();
        for (var link : threatModelLinkRepository.findByThreatModelIdAndTargetType(
                threatModelId, ThreatModelLinkTargetType.CONTROL)) {
            if (link.getLinkType() == ThreatModelLinkType.MITIGATED_BY && link.getTargetEntityId() != null) {
                viaLink.add(link.getTargetEntityId());
            }
        }

        Set<UUID> all = new LinkedHashSet<>();
        all.addAll(viaMapping);
        all.addAll(viaLink);

        List<CoveredControl> covered = new ArrayList<>();
        for (var controlId : all) {
            var control =
                    controlRepository.findByIdAndProjectId(controlId, projectId).orElse(null);
            if (control == null) {
                continue;
            }
            covered.add(new CoveredControl(
                    controlId,
                    control.getUid(),
                    control.getTitle(),
                    viaMapping.contains(controlId),
                    viaLink.contains(controlId)));
        }
        covered.sort(Comparator.comparing(CoveredControl::controlUid));
        return new ThreatControlCoverage(threatModelId, covered);
    }

    /**
     * The deterministic control candidate the engine produces for this persisted threat that matches
     * {@code controlId}, if any. Re-derives candidacy from the threat's STRIDE category over the
     * project's available controls — server-side proof that beats trusting caller-supplied provenance.
     */
    private Optional<ControlCandidate> matchingCandidate(UUID projectId, ThreatModel threat, UUID controlId) {
        var result = ControlIdentificationService.identify(
                DefaultControlMappingRuleSet.standard(),
                List.of(MappableThreat.fromThreatModel(threat)),
                identificationService.loadAvailableControls(projectId));
        return result.candidates().stream()
                .filter(c -> controlId.equals(c.controlId()))
                .findFirst();
    }

    private UUID existingMappingId(UUID projectId, UUID threatModelId, UUID controlId) {
        return riskControlMappingRepository.findByProjectIdAndThreatModelId(projectId, threatModelId).stream()
                .filter(m -> m.getControl() != null
                        && controlId.equals(m.getControl().getId()))
                .map(RiskControlMapping::getId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("RiskControlMapping expected but not found for threat "
                        + threatModelId + " control " + controlId));
    }

    private UUID existingLinkId(UUID threatModelId, UUID controlId) {
        return threatModelLinkRepository
                .findByThreatModelIdAndTargetType(threatModelId, ThreatModelLinkTargetType.CONTROL)
                .stream()
                .filter(l ->
                        l.getLinkType() == ThreatModelLinkType.MITIGATED_BY && controlId.equals(l.getTargetEntityId()))
                .map(ThreatModelLink::getId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("ThreatModelLink expected but not found for threat "
                        + threatModelId + " control " + controlId));
    }

    private static UUID mappingControlId(RiskControlMapping mapping) {
        if (mapping.getControl() != null) {
            return mapping.getControl().getId();
        }
        if (mapping.getScopedImplementation() != null
                && mapping.getScopedImplementation().getControl() != null) {
            return mapping.getScopedImplementation().getControl().getId();
        }
        return null;
    }
}

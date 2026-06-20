package com.keplerops.groundcontrol.domain.riskcontrol.service;

import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskcontrol.model.MappingEvidenceRef;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.ScopedControlImplementationRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for {@link RiskControlMapping} per GC-T003.
 *
 * <p>Owns project-scoped CRUD for the canonical mapping owner. Validates endpoint same-project
 * membership, enforces the exactly-one-endpoint invariant on each side, and prevents duplicate
 * mappings via a {@link ConflictException}. Methodology influence validation is delegated to
 * {@link MethodologyInfluenceValidator} when a profile is present.
 */
@Service
@Transactional
public class RiskControlMappingService {

    private static final Logger log = LoggerFactory.getLogger(RiskControlMappingService.class);
    private static final String MAPPING_NOT_FOUND = "RiskControlMapping not found: ";

    private final RiskControlMappingRepository repository;
    private final ScopedControlImplementationRepository sciRepository;
    private final ProjectService projectService;
    private final ControlRepository controlRepository;
    private final RiskScenarioRepository riskScenarioRepository;
    private final RiskRegisterRecordRepository riskRegisterRecordRepository;
    private final OperationalAssetRepository operationalAssetRepository;
    private final ObservationRepository observationRepository;
    private final MethodologyProfileRepository methodologyProfileRepository;
    private final MethodologyInfluenceValidator methodologyInfluenceValidator;
    private final ThreatModelRepository threatModelRepository;

    public RiskControlMappingService(
            RiskControlMappingRepository repository,
            ScopedControlImplementationRepository sciRepository,
            ProjectService projectService,
            ControlRepository controlRepository,
            RiskScenarioRepository riskScenarioRepository,
            RiskRegisterRecordRepository riskRegisterRecordRepository,
            OperationalAssetRepository operationalAssetRepository,
            ObservationRepository observationRepository,
            MethodologyProfileRepository methodologyProfileRepository,
            MethodologyInfluenceValidator methodologyInfluenceValidator,
            ThreatModelRepository threatModelRepository) {
        this.repository = repository;
        this.sciRepository = sciRepository;
        this.projectService = projectService;
        this.controlRepository = controlRepository;
        this.riskScenarioRepository = riskScenarioRepository;
        this.riskRegisterRecordRepository = riskRegisterRecordRepository;
        this.operationalAssetRepository = operationalAssetRepository;
        this.observationRepository = observationRepository;
        this.methodologyProfileRepository = methodologyProfileRepository;
        this.methodologyInfluenceValidator = methodologyInfluenceValidator;
        this.threatModelRepository = threatModelRepository;
    }

    public RiskControlMapping create(CreateRiskControlMappingCommand command) {
        validateExactlyOneControlEndpoint(command);
        validateExactlyOneAnalysisEndpoint(command);

        var project = projectService.getById(command.projectId());

        // --- Resolve control-side endpoint, then the analysis-side endpoint within it ---
        RiskControlMapping mapping = command.controlId() != null
                ? buildForCatalogControl(project, command)
                : buildForScopedImplementation(project, command);

        // --- C2: Optional asset context ---
        if (command.operationalAssetId() != null) {
            var asset = operationalAssetRepository
                    .findByIdAndProjectId(command.operationalAssetId(), project.getId())
                    .orElseThrow(() -> new NotFoundException(
                            "OperationalAsset not found in project: " + command.operationalAssetId()));
            mapping.setOperationalAsset(asset);
        }

        // --- C3: Objective, scope ---
        if (command.mappingObjective() != null) {
            mapping.setMappingObjective(command.mappingObjective());
        }
        if (command.mappingScope() != null) {
            mapping.setMappingScope(command.mappingScope());
        }

        // --- C4: Methodology influence ---
        if (command.methodologyProfileId() != null) {
            var profile = methodologyProfileRepository
                    .findByIdAndProjectId(command.methodologyProfileId(), project.getId())
                    .orElseThrow(() -> new NotFoundException(
                            "MethodologyProfile not found in project: " + command.methodologyProfileId()));
            mapping.setMethodologyProfile(profile);
            if (command.methodologyInfluence() != null) {
                methodologyInfluenceValidator.validate(profile, command.methodologyInfluence());
                mapping.setMethodologyInfluence(command.methodologyInfluence());
            }
        } else if (command.methodologyInfluence() != null) {
            // Influence without a profile is allowed but not validated
            mapping.setMethodologyInfluence(command.methodologyInfluence());
        }

        var saved = repository.save(mapping);
        log.info("risk_control_mapping_created: id={} project={}", saved.getId(), project.getIdentifier());
        return saved;
    }

    public RiskControlMapping update(UpdateRiskControlMappingCommand command) {
        var mapping = repository
                .findByIdAndProjectId(command.mappingId(), command.projectId())
                .orElseThrow(() -> new NotFoundException(MAPPING_NOT_FOUND + command.mappingId()));

        if (command.controlRole() != null) {
            mapping.setControlRole(command.controlRole());
        }
        if (command.mappingObjective() != null) {
            mapping.setMappingObjective(command.mappingObjective());
        }
        if (command.mappingScope() != null) {
            mapping.setMappingScope(command.mappingScope());
        }
        if (command.methodologyProfileId() != null) {
            var profile = methodologyProfileRepository
                    .findByIdAndProjectId(command.methodologyProfileId(), command.projectId())
                    .orElseThrow(() ->
                            new NotFoundException("MethodologyProfile not found: " + command.methodologyProfileId()));
            mapping.setMethodologyProfile(profile);
        }
        if (command.methodologyInfluence() != null) {
            if (mapping.getMethodologyProfile() != null) {
                methodologyInfluenceValidator.validate(mapping.getMethodologyProfile(), command.methodologyInfluence());
            }
            mapping.setMethodologyInfluence(command.methodologyInfluence());
        }

        var saved = repository.save(mapping);
        log.info("risk_control_mapping_updated: id={} project={}", saved.getId(), command.projectId());
        return saved;
    }

    public void delete(UUID projectId, UUID mappingId) {
        var mapping = repository
                .findByIdAndProjectId(mappingId, projectId)
                .orElseThrow(() -> new NotFoundException(MAPPING_NOT_FOUND + mappingId));
        repository.delete(mapping);
        log.info("risk_control_mapping_deleted: id={} project={}", mappingId, projectId);
    }

    @Transactional(readOnly = true)
    public RiskControlMapping getById(UUID projectId, UUID mappingId) {
        return repository
                .findByIdAndProjectId(mappingId, projectId)
                .orElseThrow(() -> new NotFoundException(MAPPING_NOT_FOUND + mappingId));
    }

    @Transactional(readOnly = true)
    public List<RiskControlMapping> listByProject(UUID projectId) {
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public List<RiskControlMapping> listByScenario(UUID projectId, UUID scenarioId) {
        return repository.findByProjectIdAndRiskScenarioId(projectId, scenarioId);
    }

    @Transactional(readOnly = true)
    public List<RiskControlMapping> listByRecord(UUID projectId, UUID recordId) {
        return repository.findByProjectIdAndRiskRegisterRecordId(projectId, recordId);
    }

    @Transactional(readOnly = true)
    public List<RiskControlMapping> listByControl(UUID projectId, UUID controlId) {
        return repository.findByProjectIdAndControlId(projectId, controlId);
    }

    @Transactional(readOnly = true)
    public List<RiskControlMapping> listByScopedImplementation(UUID projectId, UUID sciId) {
        return repository.findByProjectIdAndScopedImplementationId(projectId, sciId);
    }

    // ---- C8: Observation attach/detach ----

    public RiskControlMapping attachObservation(UUID projectId, UUID mappingId, UUID observationId) {
        var mapping = repository
                .findByIdAndProjectId(mappingId, projectId)
                .orElseThrow(() -> new NotFoundException(MAPPING_NOT_FOUND + mappingId));
        var observation = observationRepository
                .findByIdWithAssetAndProjectId(observationId, projectId)
                .orElseThrow(() -> new NotFoundException("Observation not found in project: " + observationId));
        mapping.addObservation(observation);
        return repository.save(mapping);
    }

    public RiskControlMapping detachObservation(UUID projectId, UUID mappingId, UUID observationId) {
        var mapping = repository
                .findByIdAndProjectId(mappingId, projectId)
                .orElseThrow(() -> new NotFoundException(MAPPING_NOT_FOUND + mappingId));
        var observation = observationRepository
                .findByIdWithAssetAndProjectId(observationId, projectId)
                .orElseThrow(() -> new NotFoundException("Observation not found in project: " + observationId));
        mapping.removeObservation(observation);
        return repository.save(mapping);
    }

    // ---- C8: Evidence ref management ----

    public RiskControlMapping addEvidenceRef(UUID projectId, UUID mappingId, MappingEvidenceRef ref) {
        var mapping = repository
                .findByIdAndProjectId(mappingId, projectId)
                .orElseThrow(() -> new NotFoundException(MAPPING_NOT_FOUND + mappingId));
        mapping.addEvidenceRef(ref);
        return repository.save(mapping);
    }

    public RiskControlMapping addEvidenceRef(
            UUID projectId, UUID mappingId, String evidenceRef, String evidenceNote, UUID evidenceArtifactId) {
        return addEvidenceRef(
                projectId, mappingId, new MappingEvidenceRef(evidenceRef, evidenceNote, evidenceArtifactId));
    }

    // ---- Private helpers ----

    private RiskControlMapping buildForCatalogControl(Project project, CreateRiskControlMappingCommand command) {
        var control = controlRepository
                .findByIdAndProjectId(command.controlId(), project.getId())
                .orElseThrow(() -> new NotFoundException("Control not found in project: " + command.controlId()));

        if (command.riskScenarioId() != null) {
            var scenario = resolveScenario(command, project.getId());
            checkDuplicate(command.controlId(), null, command.riskScenarioId(), null, command.operationalAssetId());
            return RiskControlMapping.forControlScenario(project, control, scenario, command.controlRole());
        }
        if (command.riskRegisterRecordId() != null) {
            var record = resolveRecord(command, project.getId());
            checkDuplicate(
                    command.controlId(), null, null, command.riskRegisterRecordId(), command.operationalAssetId());
            return RiskControlMapping.forControlRecord(project, control, record, command.controlRole());
        }
        var threat = resolveThreat(command, project.getId());
        checkDuplicate(command.controlId(), command.threatModelId(), null, null, command.operationalAssetId());
        return RiskControlMapping.forControlThreat(project, control, threat, command.controlRole());
    }

    private RiskControlMapping buildForScopedImplementation(Project project, CreateRiskControlMappingCommand command) {
        var sci = sciRepository
                .findByIdAndProjectId(command.scopedImplementationId(), project.getId())
                .orElseThrow(() -> new NotFoundException(
                        "ScopedControlImplementation not found in project: " + command.scopedImplementationId()));

        if (command.riskScenarioId() != null) {
            var scenario = resolveScenario(command, project.getId());
            checkDuplicateScoped(
                    command.scopedImplementationId(),
                    null,
                    command.riskScenarioId(),
                    null,
                    command.operationalAssetId());
            return RiskControlMapping.forScopedScenario(project, sci, scenario, command.controlRole());
        }
        if (command.riskRegisterRecordId() != null) {
            var record = resolveRecord(command, project.getId());
            checkDuplicateScoped(
                    command.scopedImplementationId(),
                    null,
                    null,
                    command.riskRegisterRecordId(),
                    command.operationalAssetId());
            return RiskControlMapping.forScopedRecord(project, sci, record, command.controlRole());
        }
        var threat = resolveThreat(command, project.getId());
        checkDuplicateScoped(
                command.scopedImplementationId(), command.threatModelId(), null, null, command.operationalAssetId());
        return RiskControlMapping.forScopedThreat(project, sci, threat, command.controlRole());
    }

    private RiskScenario resolveScenario(CreateRiskControlMappingCommand command, UUID projectId) {
        return riskScenarioRepository
                .findByIdAndProjectId(command.riskScenarioId(), projectId)
                .orElseThrow(
                        () -> new NotFoundException("RiskScenario not found in project: " + command.riskScenarioId()));
    }

    private RiskRegisterRecord resolveRecord(CreateRiskControlMappingCommand command, UUID projectId) {
        return riskRegisterRecordRepository
                .findByIdAndProjectIdWithScenarios(command.riskRegisterRecordId(), projectId)
                .orElseThrow(() -> new NotFoundException(
                        "RiskRegisterRecord not found in project: " + command.riskRegisterRecordId()));
    }

    private ThreatModel resolveThreat(CreateRiskControlMappingCommand command, UUID projectId) {
        return threatModelRepository
                .findByIdAndProjectId(command.threatModelId(), projectId)
                .orElseThrow(
                        () -> new NotFoundException("ThreatModel not found in project: " + command.threatModelId()));
    }

    private void validateExactlyOneControlEndpoint(CreateRiskControlMappingCommand command) {
        boolean hasControl = command.controlId() != null;
        boolean hasScoped = command.scopedImplementationId() != null;
        if (hasControl == hasScoped) {
            throw new DomainValidationException(
                    "Exactly one of controlId or scopedImplementationId must be provided",
                    "invalid_endpoint",
                    Map.of(
                            "controlId",
                            String.valueOf(command.controlId()),
                            "scopedImplementationId",
                            String.valueOf(command.scopedImplementationId())));
        }
    }

    private void validateExactlyOneAnalysisEndpoint(CreateRiskControlMappingCommand command) {
        int count = (command.threatModelId() != null ? 1 : 0)
                + (command.riskScenarioId() != null ? 1 : 0)
                + (command.riskRegisterRecordId() != null ? 1 : 0);
        if (count != 1) {
            throw new DomainValidationException(
                    "Exactly one of threatModelId, riskScenarioId, or riskRegisterRecordId must be provided",
                    "invalid_endpoint",
                    Map.of(
                            "threatModelId",
                            String.valueOf(command.threatModelId()),
                            "riskScenarioId",
                            String.valueOf(command.riskScenarioId()),
                            "riskRegisterRecordId",
                            String.valueOf(command.riskRegisterRecordId())));
        }
    }

    private void checkDuplicate(UUID controlId, UUID threatModelId, UUID scenarioId, UUID recordId, UUID assetId) {
        boolean exists = false;
        if (controlId != null && threatModelId != null) {
            exists = repository.existsByControlIdAndThreatModelIdAndOperationalAssetId(
                    controlId, threatModelId, assetId);
        } else if (controlId != null && scenarioId != null) {
            exists = repository.existsByControlIdAndRiskScenarioIdAndOperationalAssetId(controlId, scenarioId, assetId);
        } else if (controlId != null && recordId != null) {
            exists = repository.existsByControlIdAndRiskRegisterRecordIdAndOperationalAssetId(
                    controlId, recordId, assetId);
        }
        if (exists) {
            throw new ConflictException("Duplicate RiskControlMapping: same endpoint combination already exists");
        }
    }

    private void checkDuplicateScoped(UUID scopedId, UUID threatModelId, UUID scenarioId, UUID recordId, UUID assetId) {
        boolean exists = false;
        if (scopedId != null && threatModelId != null) {
            exists = repository.existsByScopedImplementationIdAndThreatModelIdAndOperationalAssetId(
                    scopedId, threatModelId, assetId);
        } else if (scopedId != null && scenarioId != null) {
            exists = repository.existsByScopedImplementationIdAndRiskScenarioIdAndOperationalAssetId(
                    scopedId, scenarioId, assetId);
        } else if (scopedId != null && recordId != null) {
            exists = repository.existsByScopedImplementationIdAndRiskRegisterRecordIdAndOperationalAssetId(
                    scopedId, recordId, assetId);
        }
        if (exists) {
            throw new ConflictException("Duplicate RiskControlMapping: same endpoint combination already exists");
        }
    }
}

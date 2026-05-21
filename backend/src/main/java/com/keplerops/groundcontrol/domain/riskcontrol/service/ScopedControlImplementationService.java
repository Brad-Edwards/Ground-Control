package com.keplerops.groundcontrol.domain.riskcontrol.service;

import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.ScopedControlImplementationRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service for {@link ScopedControlImplementation} (GC-T003 C1). */
@Service
@Transactional
public class ScopedControlImplementationService {

    private static final Logger log = LoggerFactory.getLogger(ScopedControlImplementationService.class);

    private final ScopedControlImplementationRepository repository;
    private final ProjectService projectService;
    private final ControlRepository controlRepository;
    private final OperationalAssetRepository operationalAssetRepository;

    public ScopedControlImplementationService(
            ScopedControlImplementationRepository repository,
            ProjectService projectService,
            ControlRepository controlRepository,
            OperationalAssetRepository operationalAssetRepository) {
        this.repository = repository;
        this.projectService = projectService;
        this.controlRepository = controlRepository;
        this.operationalAssetRepository = operationalAssetRepository;
    }

    public ScopedControlImplementation create(CreateScopedControlImplementationCommand command) {
        var project = projectService.getById(command.projectId());
        var control = controlRepository
                .findByIdAndProjectId(command.controlId(), project.getId())
                .orElseThrow(() -> new NotFoundException("Control not found in project: " + command.controlId()));

        if (repository.existsByProjectIdAndUid(project.getId(), command.uid())) {
            throw new ConflictException(
                    "ScopedControlImplementation with UID " + command.uid() + " already exists in this project");
        }

        var sci = new ScopedControlImplementation(project, command.uid(), control, command.name());

        if (command.implementationScope() != null) {
            sci.setImplementationScope(command.implementationScope());
        }
        if (command.operationalAssetId() != null) {
            var asset = operationalAssetRepository
                    .findByIdAndProjectId(command.operationalAssetId(), project.getId())
                    .orElseThrow(() -> new NotFoundException(
                            "OperationalAsset not found in project: " + command.operationalAssetId()));
            sci.setOperationalAsset(asset);
        }

        var saved = repository.save(sci);
        log.info("scoped_control_implementation_created: id={} project={}", saved.getId(), project.getIdentifier());
        return saved;
    }

    public ScopedControlImplementation update(UpdateScopedControlImplementationCommand command) {
        var sci = repository
                .findByIdAndProjectId(command.sciId(), command.projectId())
                .orElseThrow(() -> new NotFoundException("ScopedControlImplementation not found: " + command.sciId()));

        if (command.name() != null) {
            sci.setName(command.name());
        }
        if (command.implementationScope() != null) {
            sci.setImplementationScope(command.implementationScope());
        }
        if (command.operationalAssetId() != null) {
            var asset = operationalAssetRepository
                    .findByIdAndProjectId(command.operationalAssetId(), command.projectId())
                    .orElseThrow(() -> new NotFoundException(
                            "OperationalAsset not found in project: " + command.operationalAssetId()));
            sci.setOperationalAsset(asset);
        }

        var saved = repository.save(sci);
        log.info("scoped_control_implementation_updated: id={} project={}", saved.getId(), command.projectId());
        return saved;
    }

    public void delete(UUID projectId, UUID sciId) {
        var sci = repository
                .findByIdAndProjectId(sciId, projectId)
                .orElseThrow(() -> new NotFoundException("ScopedControlImplementation not found: " + sciId));
        repository.delete(sci);
        log.info("scoped_control_implementation_deleted: id={} project={}", sciId, projectId);
    }

    @Transactional(readOnly = true)
    public ScopedControlImplementation getById(UUID projectId, UUID sciId) {
        return repository
                .findByIdAndProjectId(sciId, projectId)
                .orElseThrow(() -> new NotFoundException("ScopedControlImplementation not found: " + sciId));
    }

    @Transactional(readOnly = true)
    public List<ScopedControlImplementation> listByProject(UUID projectId) {
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public List<ScopedControlImplementation> listByProjectAndControl(UUID projectId, UUID controlId) {
        return repository.findByProjectIdAndControlIdOrderByCreatedAtDesc(projectId, controlId);
    }
}

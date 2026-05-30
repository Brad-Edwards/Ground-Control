package com.keplerops.groundcontrol.domain.compliance.service;

import com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping;
import com.keplerops.groundcontrol.domain.compliance.repository.ComplianceFrameworkMappingRepository;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for {@link ComplianceFrameworkMapping} aggregate per GC-I002 /
 * GC-I005 / GC-I007 / GC-L011.
 *
 * <p>Owns project-scoped CRUD for the promoted compliance-framework-mapping
 * aggregate. Validates exactly-one source endpoint (requirement vs control)
 * before reaching the repository, prevents duplicate (endpoint, framework,
 * element) tuples via a {@link ConflictException}, and validates that
 * framework element strings carry no embedded newlines (log-injection guard
 * per the cluster security note).
 */
@Service
@Transactional
public class ComplianceFrameworkMappingService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceFrameworkMappingService.class);
    private static final String MAPPING_NOT_FOUND = "ComplianceFrameworkMapping not found: ";
    private static final String FRAMEWORK_ELEMENT_REQUIRED = "frameworkElement is required";
    private static final String FRAMEWORK_REQUIRED = "framework is required";
    private static final String COVERAGE_LEVEL_REQUIRED = "coverageLevel is required";

    private final ComplianceFrameworkMappingRepository repository;
    private final ProjectService projectService;
    private final RequirementRepository requirementRepository;
    private final ControlRepository controlRepository;

    public ComplianceFrameworkMappingService(
            ComplianceFrameworkMappingRepository repository,
            ProjectService projectService,
            RequirementRepository requirementRepository,
            ControlRepository controlRepository) {
        this.repository = repository;
        this.projectService = projectService;
        this.requirementRepository = requirementRepository;
        this.controlRepository = controlRepository;
    }

    public ComplianceFrameworkMapping create(CreateComplianceFrameworkMappingCommand command) {
        validateExactlyOneSourceEndpoint(command);
        validateRequiredFields(
                command.framework(),
                command.frameworkElement(),
                command.coverageLevel(),
                command.frameworkIdentifier());

        var project = projectService.getById(command.projectId());
        String element = command.frameworkElement().trim();

        ComplianceFrameworkMapping mapping;
        if (command.requirementId() != null) {
            var requirement = requirementRepository
                    .findByIdAndProjectId(command.requirementId(), project.getId())
                    .orElseThrow(() ->
                            new NotFoundException("Requirement not found in project: " + command.requirementId()));
            if (repository.existsByRequirementIdAndFrameworkAndFrameworkElement(
                    command.requirementId(), command.framework(), element)) {
                throw new ConflictException(
                        "Duplicate ComplianceFrameworkMapping for requirement endpoint and element");
            }
            mapping = ComplianceFrameworkMapping.forRequirement(
                    project, requirement, command.framework(), element, command.coverageLevel());
        } else {
            var control = controlRepository
                    .findByIdAndProjectId(command.controlId(), project.getId())
                    .orElseThrow(() -> new NotFoundException("Control not found in project: " + command.controlId()));
            if (repository.existsByControlIdAndFrameworkAndFrameworkElement(
                    command.controlId(), command.framework(), element)) {
                throw new ConflictException("Duplicate ComplianceFrameworkMapping for control endpoint and element");
            }
            mapping = ComplianceFrameworkMapping.forControl(
                    project, control, command.framework(), element, command.coverageLevel());
        }

        mapping.setFrameworkIdentifier(sanitizeExternalIdentifier(command.frameworkIdentifier()));
        mapping.setFrameworkVersion(command.frameworkVersion());
        mapping.setRationale(command.rationale());

        var saved = repository.save(mapping);
        log.info(
                "compliance_framework_mapping_created: id={} project={} framework={}",
                saved.getId(),
                project.getIdentifier(),
                saved.getFramework());
        return saved;
    }

    public ComplianceFrameworkMapping update(UpdateComplianceFrameworkMappingCommand command) {
        var mapping = repository
                .findByIdAndProjectId(command.mappingId(), command.projectId())
                .orElseThrow(() -> new NotFoundException(MAPPING_NOT_FOUND + command.mappingId()));

        ComplianceFrameworkIdentifier newFramework =
                command.framework() != null ? command.framework() : mapping.getFramework();
        String newElement =
                command.frameworkElement() != null ? command.frameworkElement().trim() : mapping.getFrameworkElement();

        if (newElement == null || newElement.isBlank()) {
            throw new DomainValidationException(FRAMEWORK_ELEMENT_REQUIRED);
        }

        // Cluster-744 fix: update MUST apply the same control-char /
        // log-injection guard as create. Without this, an authenticated
        // caller can PUT a `frameworkIdentifier` containing embedded \n /
        // \r / \t / chars < 0x20 — defeating the log-injection invariant
        // that the create path enforces via validateRequiredFields(...).
        if (command.frameworkIdentifier() != null && containsControlChars(command.frameworkIdentifier())) {
            throw new DomainValidationException("frameworkIdentifier must not contain control characters or newlines");
        }

        if (command.framework() != null || command.frameworkElement() != null) {
            assertNoDuplicateOnRename(mapping, newFramework, newElement);
        }

        mapping.setFramework(newFramework);
        mapping.setFrameworkElement(newElement);

        if (command.frameworkIdentifier() != null) {
            mapping.setFrameworkIdentifier(sanitizeExternalIdentifier(command.frameworkIdentifier()));
        }
        if (command.frameworkVersion() != null) {
            mapping.setFrameworkVersion(command.frameworkVersion());
        }
        if (command.coverageLevel() != null) {
            mapping.setCoverageLevel(command.coverageLevel());
        }
        if (command.rationale() != null) {
            mapping.setRationale(command.rationale());
        }

        var saved = repository.save(mapping);
        log.info(
                "compliance_framework_mapping_updated: id={} project={} framework={}",
                saved.getId(),
                command.projectId(),
                saved.getFramework());
        return saved;
    }

    public void delete(UUID projectId, UUID mappingId) {
        var mapping = repository
                .findByIdAndProjectId(mappingId, projectId)
                .orElseThrow(() -> new NotFoundException(MAPPING_NOT_FOUND + mappingId));
        repository.delete(mapping);
        log.info("compliance_framework_mapping_deleted: id={} project={}", mappingId, projectId);
    }

    @Transactional(readOnly = true)
    public ComplianceFrameworkMapping getById(UUID projectId, UUID mappingId) {
        return repository
                .findByIdAndProjectId(mappingId, projectId)
                .orElseThrow(() -> new NotFoundException(MAPPING_NOT_FOUND + mappingId));
    }

    @Transactional(readOnly = true)
    public List<ComplianceFrameworkMapping> listByProject(UUID projectId) {
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public List<ComplianceFrameworkMapping> listByFramework(UUID projectId, ComplianceFrameworkIdentifier framework) {
        return repository.findByProjectIdAndFramework(projectId, framework);
    }

    @Transactional(readOnly = true)
    public List<ComplianceFrameworkMapping> listByRequirement(UUID projectId, UUID requirementId) {
        return repository.findByProjectIdAndRequirementId(projectId, requirementId);
    }

    @Transactional(readOnly = true)
    public List<ComplianceFrameworkMapping> listByControl(UUID projectId, UUID controlId) {
        return repository.findByProjectIdAndControlId(projectId, controlId);
    }

    // ---- Internal validation ----

    private void validateExactlyOneSourceEndpoint(CreateComplianceFrameworkMappingCommand command) {
        boolean hasRequirement = command.requirementId() != null;
        boolean hasControl = command.controlId() != null;
        if (hasRequirement == hasControl) {
            throw new DomainValidationException(
                    "Exactly one of requirementId or controlId must be provided",
                    "invalid_endpoint",
                    Map.of(
                            "requirementId",
                            String.valueOf(command.requirementId()),
                            "controlId",
                            String.valueOf(command.controlId())));
        }
    }

    private void validateRequiredFields(
            ComplianceFrameworkIdentifier framework,
            String frameworkElement,
            com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel coverageLevel,
            String externalIdentifier) {
        if (framework == null) {
            throw new DomainValidationException(FRAMEWORK_REQUIRED);
        }
        if (frameworkElement == null || frameworkElement.isBlank()) {
            throw new DomainValidationException(FRAMEWORK_ELEMENT_REQUIRED);
        }
        if (coverageLevel == null) {
            throw new DomainValidationException(COVERAGE_LEVEL_REQUIRED);
        }
        if (externalIdentifier != null && containsControlChars(externalIdentifier)) {
            throw new DomainValidationException("frameworkIdentifier must not contain control characters or newlines");
        }
    }

    private void assertNoDuplicateOnRename(
            ComplianceFrameworkMapping current, ComplianceFrameworkIdentifier framework, String element) {
        boolean unchanged = current.getFramework() == framework
                && current.getFrameworkElement().equals(element);
        if (unchanged) {
            return;
        }
        boolean duplicate;
        if (current.isRequirementSide()) {
            duplicate = repository.existsByRequirementIdAndFrameworkAndFrameworkElement(
                    current.getRequirement().getId(), framework, element);
        } else {
            duplicate = repository.existsByControlIdAndFrameworkAndFrameworkElement(
                    current.getControl().getId(), framework, element);
        }
        if (duplicate) {
            throw new ConflictException("Duplicate ComplianceFrameworkMapping after rename");
        }
    }

    private String sanitizeExternalIdentifier(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean containsControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c < 0x20) {
                return true;
            }
        }
        return false;
    }
}

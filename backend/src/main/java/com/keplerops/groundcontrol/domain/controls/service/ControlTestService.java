package com.keplerops.groundcontrol.domain.controls.service;

import com.keplerops.groundcontrol.domain.controls.model.ControlTest;
import com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for {@link ControlTest} per GC-I012.
 *
 * <p>Owns project-scoped CRUD: resolves the parent {@code Control} through {@link ControlService}
 * so cross-project access is rejected before any persistence call, enforces project-scoped UID
 * uniqueness, and emits structured lifecycle log lines. Domain validation (null/blank field
 * checks) lives on the DTO layer via Bean Validation; this service handles semantic checks only.
 */
@Service
@Transactional
public class ControlTestService {

    private static final Logger log = LoggerFactory.getLogger(ControlTestService.class);

    private final ControlTestRepository controlTestRepository;
    private final ControlService controlService;
    private final ProjectService projectService;

    public ControlTestService(
            ControlTestRepository controlTestRepository, ControlService controlService, ProjectService projectService) {
        this.controlTestRepository = controlTestRepository;
        this.controlService = controlService;
        this.projectService = projectService;
    }

    public ControlTest create(CreateControlTestCommand command) {
        var project = projectService.getById(command.projectId());
        var control = controlService.getById(project.getId(), command.controlId());
        if (controlTestRepository.existsByProjectIdAndUid(project.getId(), command.uid())) {
            throw new ConflictException("ControlTest with UID " + command.uid() + " already exists in this project");
        }
        var controlTest = new ControlTest(
                project,
                control,
                command.uid(),
                command.methodology(),
                command.conclusion(),
                command.testerIdentity(),
                command.testDate());
        controlTest.setTestSteps(command.testSteps());
        controlTest.setExpectedResults(command.expectedResults());
        controlTest.setActualResults(command.actualResults());
        controlTest.setNotes(command.notes());
        controlTest = controlTestRepository.save(controlTest);
        log.info(
                "control_test_created: uid={} control={} project={}",
                controlTest.getUid(),
                control.getUid(),
                project.getIdentifier());
        return controlTest;
    }

    public ControlTest update(UUID projectId, UUID id, UpdateControlTestCommand command) {
        var controlTest = findOrThrow(projectId, id);
        if (command.methodology() != null) {
            controlTest.setMethodology(command.methodology());
        }
        if (command.testSteps() != null) {
            requireNonBlank(command.testSteps(), "testSteps");
            controlTest.setTestSteps(command.testSteps());
        }
        if (command.expectedResults() != null) {
            requireNonBlank(command.expectedResults(), "expectedResults");
            controlTest.setExpectedResults(command.expectedResults());
        }
        if (command.actualResults() != null) {
            requireNonBlank(command.actualResults(), "actualResults");
            controlTest.setActualResults(command.actualResults());
        }
        if (command.conclusion() != null) {
            controlTest.setConclusion(command.conclusion());
        }
        if (command.testerIdentity() != null) {
            requireNonBlank(command.testerIdentity(), "testerIdentity");
            controlTest.setTesterIdentity(command.testerIdentity());
        }
        if (command.testDate() != null) {
            controlTest.setTestDate(command.testDate());
        }
        if (command.notes() != null) {
            controlTest.setNotes(command.notes());
        }
        controlTest = controlTestRepository.save(controlTest);
        log.info("control_test_updated: uid={} id={}", controlTest.getUid(), controlTest.getId());
        return controlTest;
    }

    @Transactional(readOnly = true)
    public ControlTest getById(UUID projectId, UUID id) {
        return findOrThrow(projectId, id);
    }

    @Transactional(readOnly = true)
    public List<ControlTest> listByProject(UUID projectId) {
        return controlTestRepository.findByProjectIdOrderByTestDateDesc(projectId);
    }

    @Transactional(readOnly = true)
    public List<ControlTest> listByProjectAndControl(UUID projectId, UUID controlId) {
        // Verify the control exists in the project so a cross-project controlId returns 404 rather
        // than silently filtering to an empty list.
        controlService.getById(projectId, controlId);
        return controlTestRepository.findByProjectIdAndControlIdOrderByTestDateDesc(projectId, controlId);
    }

    public void delete(UUID projectId, UUID id) {
        var controlTest = findOrThrow(projectId, id);
        controlTestRepository.delete(controlTest);
        log.info("control_test_deleted: uid={} id={}", controlTest.getUid(), id);
    }

    private ControlTest findOrThrow(UUID projectId, UUID id) {
        return controlTestRepository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("ControlTest not found: " + id));
    }

    /**
     * Reject whitespace-only updates on evidence/provenance fields. Replaces the @Pattern
     * approach that Sonar flagged as polynomial-backtracking risk. A null-element check is
     * unnecessary here — the update flow only calls this when the value is non-null.
     */
    private static void requireNonBlank(String value, String fieldName) {
        if (value.isBlank()) {
            throw new com.keplerops.groundcontrol.domain.exception.DomainValidationException(
                    fieldName + " must not be blank when present",
                    "validation_error",
                    java.util.Map.of("field", fieldName));
        }
    }
}

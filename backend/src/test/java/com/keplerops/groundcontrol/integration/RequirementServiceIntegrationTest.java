package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.service.CreateRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import com.keplerops.groundcontrol.domain.requirements.state.RequirementType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RequirementServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProjectRepository projectRepository;

    private Project testProject;

    @BeforeEach
    void setUp() {
        testProject = projectRepository.findByIdentifier("ground-control").orElseThrow();
    }

    private CreateRequirementCommand makeCommand(String uid) {
        return new CreateRequirementCommand(
                testProject.getId(),
                uid,
                null,
                "Title for " + uid,
                "Statement for " + uid,
                "Rationale",
                RequirementType.FUNCTIONAL,
                Priority.MUST,
                1);
    }

    @Test
    void createPersistsAndIsRetrievableById() {
        var created = requirementService.create(makeCommand("REQ-INT-001"));
        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(Status.DRAFT);

        var found = requirementService.getById(created.getId());
        assertThat(found.getUid()).isEqualTo("REQ-INT-001");
        assertThat(found.getTitle()).isEqualTo("Title for REQ-INT-001");
    }

    @Test
    void getByUidRetrievesByHumanReadableUid() {
        requirementService.create(makeCommand("REQ-INT-002"));

        var found = requirementService.getByUid(testProject.getId(), "REQ-INT-002");
        assertThat(found.getUid()).isEqualTo("REQ-INT-002");
    }

    @Test
    void transitionStatusPersists() {
        var req = requirementService.create(makeCommand("REQ-INT-003"));

        var updated = requirementService.transitionStatus(req.getId(), Status.ACTIVE);
        assertThat(updated.getStatus()).isEqualTo(Status.ACTIVE);

        entityManager.flush();
        entityManager.clear();

        var reloaded = requirementService.getById(req.getId());
        assertThat(reloaded.getStatus()).isEqualTo(Status.ACTIVE);
    }

    @Test
    void archiveSetsArchivedAtAndPersists() {
        var req = requirementService.create(makeCommand("REQ-INT-004"));
        requirementService.transitionStatus(req.getId(), Status.ACTIVE);

        var archived = requirementService.archive(req.getId());
        assertThat(archived.getStatus()).isEqualTo(Status.ARCHIVED);
        assertThat(archived.getArchivedAt()).isNotNull();
    }

    @Test
    void duplicateUidThrowsConflict() {
        requirementService.create(makeCommand("REQ-INT-005"));

        assertThatThrownBy(() -> requirementService.create(makeCommand("REQ-INT-005")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void selfLoopRelationThrowsDomainValidation() {
        var req = requirementService.create(makeCommand("REQ-INT-006"));

        assertThatThrownBy(() -> requirementService.createRelation(req.getId(), req.getId(), RelationType.DEPENDS_ON))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void duplicateRelationThrowsConflict() {
        var source = requirementService.create(makeCommand("REQ-INT-DUP-S"));
        var target = requirementService.create(makeCommand("REQ-INT-DUP-T"));

        requirementService.createRelation(source.getId(), target.getId(), RelationType.DEPENDS_ON);

        assertThatThrownBy(() ->
                        requirementService.createRelation(source.getId(), target.getId(), RelationType.DEPENDS_ON))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void enversAuditTrailRecordsRevisions() {
        // Envers writes audit data on commit, so we must commit to see revisions
        var req = requirementService.create(makeCommand("REQ-INT-008"));
        TestTransaction.flagForCommit();
        TestTransaction.end();

        TestTransaction.start();
        requirementService.transitionStatus(req.getId(), Status.ACTIVE);
        TestTransaction.flagForCommit();
        TestTransaction.end();

        TestTransaction.start();
        var auditReader = AuditReaderFactory.get(entityManager);
        var revisions = auditReader.getRevisions(
                com.keplerops.groundcontrol.domain.requirements.model.Requirement.class, req.getId());

        assertThat(revisions).hasSize(2);
    }
}

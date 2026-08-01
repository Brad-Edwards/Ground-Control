package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.qualitygates.repository.QualityGateRepository;
import com.keplerops.groundcontrol.domain.qualitygates.state.MetricType;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.CreateRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementUidAllocator;
import com.keplerops.groundcontrol.domain.requirements.service.UpdateRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import com.keplerops.groundcontrol.domain.requirements.state.RequirementType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from RequirementServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class RequirementServiceTest {
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final Project TEST_PROJECT = createTestProject();

    private static Project createTestProject() {
        var project = new Project("test-project", "Test Project");
        TestUtil.setField(project, "id", PROJECT_ID);
        return project;
    }

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private RequirementRelationRepository relationRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private QualityGateRepository qualityGateRepository;

    @Mock
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Mock
    private RequirementUidAllocator uidAllocator;

    private RequirementService service;

    @BeforeEach
    void setUp() {
        service = new RequirementService(
                requirementRepository,
                relationRepository,
                projectRepository,
                qualityGateRepository,
                traceabilityLinkRepository,
                eventPublisher,
                uidAllocator);
    }

    private static Requirement makeRequirement(String uid) {
        return new Requirement(TEST_PROJECT, uid, "Title for " + uid, "Statement for " + uid);
    }

    @Nested
    class Create {

        @Test
        void createsRequirementInDraftStatus() {
            var cmd = new CreateRequirementCommand(
                    PROJECT_ID,
                    "REQ-001",
                    null,
                    "Title",
                    "Statement",
                    "Rationale",
                    RequirementType.FUNCTIONAL,
                    Priority.MUST,
                    1);

            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));
            when(requirementRepository.existsByProjectIdAndUidIgnoreCase(PROJECT_ID, "REQ-001"))
                    .thenReturn(false);
            when(requirementRepository.save(any(Requirement.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.create(cmd);
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(Status.DRAFT);
            assertThat(result.getUid()).isEqualTo("REQ-001");
        }

        @Test
        void createsWithNullOptionalFields() {
            var cmd = new CreateRequirementCommand(
                    PROJECT_ID, "REQ-002", null, "Title", "Statement", null, null, null, null);

            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));
            when(requirementRepository.existsByProjectIdAndUidIgnoreCase(PROJECT_ID, "REQ-002"))
                    .thenReturn(false);
            when(requirementRepository.save(any(Requirement.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.create(cmd);
            assertThat(result.getRationale()).isEmpty();
            assertThat(result.getRequirementType()).isEqualTo(RequirementType.FUNCTIONAL); // default
            assertThat(result.getPriority()).isEqualTo(Priority.MUST); // default
        }

        @Test
        void throwsConflictOnDuplicateUid() {
            var cmd = new CreateRequirementCommand(
                    PROJECT_ID, "REQ-001", null, "Title", "Statement", null, null, null, null);

            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));
            when(requirementRepository.existsByProjectIdAndUidIgnoreCase(PROJECT_ID, "REQ-001"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(ConflictException.class);
        }

        @Test
        void createWithUidPrefix_delegatesToAllocator() {
            var cmd = new CreateRequirementCommand(
                    PROJECT_ID, null, "PLAT", "Title", "Statement", null, null, null, null);

            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));
            when(uidAllocator.allocate(PROJECT_ID, "PLAT")).thenReturn("PLAT-001");
            when(requirementRepository.save(any(Requirement.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.create(cmd);
            assertThat(result.getUid()).isEqualTo("PLAT-001");
            verify(uidAllocator).allocate(PROJECT_ID, "PLAT");
        }
    }

    @Nested
    class GetById {

        @Test
        void returnsExistingRequirement() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));

            var result = service.getById(id);
            assertThat(result).isNotNull();
            assertThat(result.getUid()).isEqualTo("REQ-001");
        }

        @Test
        void throwsNotFoundForMissingId() {
            var id = UUID.randomUUID();
            when(requirementRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(id)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class GetByUid {

        @Test
        void returnsExistingRequirement() {
            var req = makeRequirement("REQ-001");
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "REQ-001"))
                    .thenReturn(Optional.of(req));

            var result = service.getByUid(PROJECT_ID, "REQ-001");
            assertThat(result).isNotNull();
            assertThat(result.getUid()).isEqualTo("REQ-001");
        }

        @Test
        void throwsNotFoundForMissingUid() {
            when(requirementRepository.findByProjectIdAndUidIgnoreCase(PROJECT_ID, "NOPE"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getByUid(PROJECT_ID, "NOPE")).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Update {

        @Test
        void updatesFieldsSuccessfully() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));
            when(requirementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateRequirementCommand(
                    "New Title", "New Statement", null, RequirementType.CONSTRAINT, Priority.SHOULD, 2);

            var result = service.update(id, cmd);
            assertThat(result.getTitle()).isEqualTo("New Title");
            assertThat(result.getRequirementType()).isEqualTo(RequirementType.CONSTRAINT);
        }

        @Test
        void updatesWithAllNullOptionalFields() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            req.setRequirementType(RequirementType.CONSTRAINT);
            req.setPriority(Priority.MUST);
            req.setWave(3);
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));
            when(requirementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateRequirementCommand(null, null, null, null, null, null);

            var result = service.update(id, cmd);
            // Original values preserved when nulls passed
            assertThat(result.getTitle()).isEqualTo("Title for REQ-001");
            assertThat(result.getStatement()).isEqualTo("Statement for REQ-001");
            assertThat(result.getRequirementType()).isEqualTo(RequirementType.CONSTRAINT);
            assertThat(result.getPriority()).isEqualTo(Priority.MUST);
            assertThat(result.getWave()).isEqualTo(3);
        }

        @Test
        void update_withNullWave_preservesExistingWave() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            req.setWave(5);
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));
            when(requirementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateRequirementCommand("New Title", null, null, null, null, null);

            var result = service.update(id, cmd);
            assertThat(result.getTitle()).isEqualTo("New Title");
            assertThat(result.getWave()).isEqualTo(5);
        }

        @Test
        void updatesRationale() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));
            when(requirementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateRequirementCommand(null, null, "New rationale", null, null, null);

            var result = service.update(id, cmd);
            assertThat(result.getRationale()).isEqualTo("New rationale");
        }

        @Test
        void throwsNotFoundForMissingId() {
            var id = UUID.randomUUID();
            when(requirementRepository.findById(id)).thenReturn(Optional.empty());

            var cmd = new UpdateRequirementCommand("Title", "Stmt", null, null, null, null);

            assertThatThrownBy(() -> service.update(id, cmd)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class TransitionStatus {

        @Test
        void transitionsSuccessfully() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));
            when(requirementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = service.transitionStatus(id, Status.ACTIVE);
            assertThat(result.getStatus()).isEqualTo(Status.ACTIVE);
        }

        @Test
        void throwsNotFoundForMissingId() {
            var id = UUID.randomUUID();
            when(requirementRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.transitionStatus(id, Status.ACTIVE))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsDomainValidationForInvalidTransition() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));

            assertThatThrownBy(() -> service.transitionStatus(id, Status.ARCHIVED))
                    .isInstanceOf(DomainValidationException.class);
        }

        @Test
        void rejectsDraftToActiveWhenActiveDocumentsCoverageGateIsMissingDocumentsLink() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));
            when(qualityGateRepository.existsByProjectIdAndEnabledTrueAndMetricTypeAndMetricParamAndScopeStatus(
                            PROJECT_ID, MetricType.COVERAGE, LinkType.DOCUMENTS.name(), Status.ACTIVE))
                    .thenReturn(true);
            when(traceabilityLinkRepository.existsByRequirementIdAndLinkType(id, LinkType.DOCUMENTS))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.transitionStatus(id, Status.ACTIVE))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("DOCUMENTS")
                    .hasMessageContaining("REQ-001");
        }

        @Test
        void allowsDraftToActiveWithoutDocumentsLinkWhenDocumentsCoverageGateIsInactive() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));
            when(qualityGateRepository.existsByProjectIdAndEnabledTrueAndMetricTypeAndMetricParamAndScopeStatus(
                            PROJECT_ID, MetricType.COVERAGE, LinkType.DOCUMENTS.name(), Status.ACTIVE))
                    .thenReturn(false);
            when(requirementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = service.transitionStatus(id, Status.ACTIVE);

            assertThat(result.getStatus()).isEqualTo(Status.ACTIVE);
        }
    }

    @Nested
    class Archive {

        @Test
        void archivesSuccessfully() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            req.transitionStatus(Status.ACTIVE);
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));
            when(requirementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = service.archive(id);
            assertThat(result.getStatus()).isEqualTo(Status.ARCHIVED);
            assertThat(result.getArchivedAt()).isNotNull();
        }

        @Test
        void throwsNotFoundForMissingId() {
            var id = UUID.randomUUID();
            when(requirementRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.archive(id)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsDomainValidationFromDraft() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));

            assertThatThrownBy(() -> service.archive(id)).isInstanceOf(DomainValidationException.class);
        }
    }
}

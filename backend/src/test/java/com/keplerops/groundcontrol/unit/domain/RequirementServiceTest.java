package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.CloneRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.service.CreateRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementFilter;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementWithLinks;
import com.keplerops.groundcontrol.domain.requirements.service.UpdateRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import com.keplerops.groundcontrol.domain.requirements.state.RequirementType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

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

    private RequirementService service;

    @BeforeEach
    void setUp() {
        service = new RequirementService(
                requirementRepository,
                relationRepository,
                projectRepository,
                qualityGateRepository,
                traceabilityLinkRepository,
                eventPublisher);
    }

    private static Requirement makeRequirement(String uid) {
        return new Requirement(TEST_PROJECT, uid, "Title for " + uid, "Statement for " + uid);
    }

    private static void setId(Requirement req, UUID id) {
        TestUtil.setField(req, "id", id);
    }

    @Nested
    class Create {

        @Test
        void createsRequirementInDraftStatus() {
            var cmd = new CreateRequirementCommand(
                    PROJECT_ID,
                    "REQ-001",
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
            var cmd = new CreateRequirementCommand(PROJECT_ID, "REQ-002", "Title", "Statement", null, null, null, null);

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
            var cmd = new CreateRequirementCommand(PROJECT_ID, "REQ-001", "Title", "Statement", null, null, null, null);

            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));
            when(requirementRepository.existsByProjectIdAndUidIgnoreCase(PROJECT_ID, "REQ-001"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(ConflictException.class);
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

    @Nested
    class CreateRelation {

        @Test
        void createsRelationSuccessfully() {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var source = makeRequirement("REQ-001");
            var target = makeRequirement("REQ-002");
            setId(source, sourceId);
            setId(target, targetId);

            when(requirementRepository.findById(sourceId)).thenReturn(Optional.of(source));
            when(requirementRepository.findById(targetId)).thenReturn(Optional.of(target));
            when(relationRepository.save(any(RequirementRelation.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.createRelation(sourceId, targetId, RelationType.DEPENDS_ON);
            assertThat(result).isNotNull();
            assertThat(result.getRelationType()).isEqualTo(RelationType.DEPENDS_ON);
        }

        @Test
        void createsSupersedingRelation() {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var source = makeRequirement("REQ-001");
            var target = makeRequirement("REQ-002");
            setId(source, sourceId);
            setId(target, targetId);

            when(requirementRepository.findById(sourceId)).thenReturn(Optional.of(source));
            when(requirementRepository.findById(targetId)).thenReturn(Optional.of(target));
            when(relationRepository.save(any(RequirementRelation.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.createRelation(sourceId, targetId, RelationType.SUPERSEDES);
            assertThat(result).isNotNull();
            assertThat(result.getRelationType()).isEqualTo(RelationType.SUPERSEDES);
        }

        @Test
        void createsRelatedRelation() {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var source = makeRequirement("REQ-001");
            var target = makeRequirement("REQ-002");
            setId(source, sourceId);
            setId(target, targetId);

            when(requirementRepository.findById(sourceId)).thenReturn(Optional.of(source));
            when(requirementRepository.findById(targetId)).thenReturn(Optional.of(target));
            when(relationRepository.save(any(RequirementRelation.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.createRelation(sourceId, targetId, RelationType.RELATED);
            assertThat(result).isNotNull();
            assertThat(result.getRelationType()).isEqualTo(RelationType.RELATED);
        }

        @Test
        void throwsConflictForDuplicateRelation() {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();

            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                            sourceId, targetId, RelationType.DEPENDS_ON))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.createRelation(sourceId, targetId, RelationType.DEPENDS_ON))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void throwsDomainValidationForSelfLoop() {
            var id = UUID.randomUUID();

            assertThatThrownBy(() -> service.createRelation(id, id, RelationType.DEPENDS_ON))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("itself");
        }

        @Test
        void throwsNotFoundForMissingSource() {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            when(requirementRepository.findById(sourceId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createRelation(sourceId, targetId, RelationType.DEPENDS_ON))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsDomainValidationForCrossProjectRelation() {
            var otherProject = new Project("other-project", "Other");
            TestUtil.setField(otherProject, "id", UUID.fromString("00000000-0000-0000-0000-000000000099"));

            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var source = makeRequirement("REQ-001");
            var target = new Requirement(otherProject, "REQ-002", "Title for REQ-002", "Statement for REQ-002");
            setId(source, sourceId);
            setId(target, targetId);

            when(requirementRepository.findById(sourceId)).thenReturn(Optional.of(source));
            when(requirementRepository.findById(targetId)).thenReturn(Optional.of(target));

            assertThatThrownBy(() -> service.createRelation(sourceId, targetId, RelationType.DEPENDS_ON))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("different projects");
        }
    }

    @Nested
    class GetRelations {

        @Test
        void returnsRelations() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));
            when(relationRepository.findBySourceIdWithEntities(id)).thenReturn(List.of());
            when(relationRepository.findByTargetIdWithEntities(id)).thenReturn(List.of());

            var result = service.getRelations(id);
            assertThat(result).isNotNull().isEmpty();
        }

        @Test
        void combinesOutgoingAndIncomingRelations() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            var target = makeRequirement("REQ-002");
            var source = makeRequirement("REQ-003");
            var outgoingRelation = new RequirementRelation(req, target, RelationType.DEPENDS_ON);
            var incomingRelation = new RequirementRelation(source, req, RelationType.DEPENDS_ON);
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));
            when(relationRepository.findBySourceIdWithEntities(id)).thenReturn(List.of(outgoingRelation));
            when(relationRepository.findByTargetIdWithEntities(id)).thenReturn(List.of(incomingRelation));

            var result = service.getRelations(id);

            assertThat(result).containsExactlyInAnyOrder(outgoingRelation, incomingRelation);
        }

        @Test
        void doesNotMutateUnmodifiableJpaResultList() {
            var id = UUID.randomUUID();
            var req = makeRequirement("REQ-001");
            var target = makeRequirement("REQ-002");
            var outgoingRelation = new RequirementRelation(req, target, RelationType.DEPENDS_ON);
            // Simulate a JPA implementation returning an unmodifiable list
            when(requirementRepository.findById(id)).thenReturn(Optional.of(req));
            when(relationRepository.findBySourceIdWithEntities(id)).thenReturn(List.of(outgoingRelation));
            when(relationRepository.findByTargetIdWithEntities(id)).thenReturn(List.of());

            // Must not throw UnsupportedOperationException
            var result = service.getRelations(id);
            assertThat(result).containsExactly(outgoingRelation);
        }

        @Test
        void throwsNotFoundForMissingRequirement() {
            var id = UUID.randomUUID();
            when(requirementRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getRelations(id)).isInstanceOf(NotFoundException.class);
        }
    }

    private static void setRelationId(RequirementRelation rel, UUID id) {
        TestUtil.setField(rel, "id", id);
    }

    @Nested
    class BulkTransitionStatus {

        @Test
        void transitionsMultipleSuccessfully() {
            var id1 = UUID.randomUUID();
            var id2 = UUID.randomUUID();
            var req1 = makeRequirement("REQ-001");
            var req2 = makeRequirement("REQ-002");

            when(requirementRepository.findById(id1)).thenReturn(Optional.of(req1));
            when(requirementRepository.findById(id2)).thenReturn(Optional.of(req2));
            when(requirementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = service.bulkTransitionStatus(List.of(id1, id2), Status.ACTIVE);

            assertThat(result.succeeded()).hasSize(2);
            assertThat(result.failed()).isEmpty();
            assertThat(result.succeeded()).allMatch(r -> r.getStatus() == Status.ACTIVE);
        }

        @Test
        void collectsFailuresAndSuccesses() {
            var validId = UUID.randomUUID();
            var invalidId = UUID.randomUUID();
            var missingId = UUID.randomUUID();

            var validReq = makeRequirement("REQ-001");
            var invalidReq = makeRequirement("REQ-002");
            invalidReq.transitionStatus(Status.ACTIVE);
            invalidReq.transitionStatus(Status.ARCHIVED);

            when(requirementRepository.findById(validId)).thenReturn(Optional.of(validReq));
            when(requirementRepository.findById(invalidId)).thenReturn(Optional.of(invalidReq));
            when(requirementRepository.findById(missingId)).thenReturn(Optional.empty());
            when(requirementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = service.bulkTransitionStatus(List.of(validId, invalidId, missingId), Status.ACTIVE);

            assertThat(result.succeeded()).hasSize(1);
            assertThat(result.succeeded().get(0).getUid()).isEqualTo("REQ-001");
            assertThat(result.failed()).hasSize(2);
        }

        @Test
        void allFailReturnsEmptySucceeded() {
            var id1 = UUID.randomUUID();
            var id2 = UUID.randomUUID();

            when(requirementRepository.findById(id1)).thenReturn(Optional.empty());
            when(requirementRepository.findById(id2)).thenReturn(Optional.empty());

            var result = service.bulkTransitionStatus(List.of(id1, id2), Status.ACTIVE);

            assertThat(result.succeeded()).isEmpty();
            assertThat(result.failed()).hasSize(2);
        }

        @Test
        void missingDocumentsLinkFailureDoesNotStopOtherBulkTransitions() {
            var missingDocsId = UUID.randomUUID();
            var validId = UUID.randomUUID();
            var missingDocsReq = makeRequirement("REQ-MISSING-DOCS");
            var validReq = makeRequirement("REQ-VALID");

            when(requirementRepository.findById(missingDocsId)).thenReturn(Optional.of(missingDocsReq));
            when(requirementRepository.findById(validId)).thenReturn(Optional.of(validReq));
            when(qualityGateRepository.existsByProjectIdAndEnabledTrueAndMetricTypeAndMetricParamAndScopeStatus(
                            PROJECT_ID, MetricType.COVERAGE, LinkType.DOCUMENTS.name(), Status.ACTIVE))
                    .thenReturn(true);
            when(traceabilityLinkRepository.existsByRequirementIdAndLinkType(missingDocsId, LinkType.DOCUMENTS))
                    .thenReturn(false);
            when(traceabilityLinkRepository.existsByRequirementIdAndLinkType(validId, LinkType.DOCUMENTS))
                    .thenReturn(true);
            when(requirementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = service.bulkTransitionStatus(List.of(missingDocsId, validId), Status.ACTIVE);

            assertThat(result.succeeded()).extracting(Requirement::getUid).containsExactly("REQ-VALID");
            assertThat(result.failed()).hasSize(1);
            assertThat(result.failed().getFirst().uid()).isEqualTo("REQ-MISSING-DOCS");
            assertThat(result.failed().getFirst().error()).contains("DOCUMENTS");
        }
    }

    @Nested
    class Clone {

        @Test
        void clonesRequirementWithRelations() {
            var sourceId = UUID.randomUUID();
            var targetId = UUID.randomUUID();
            var source = makeRequirement("REQ-001");
            var target = makeRequirement("REQ-002");
            setId(source, sourceId);
            setId(target, targetId);
            source.setRationale("Important");
            source.setPriority(Priority.SHOULD);
            source.setRequirementType(RequirementType.CONSTRAINT);
            source.setWave(2);

            var outgoingRelation = new RequirementRelation(source, target, RelationType.DEPENDS_ON);

            when(requirementRepository.findById(sourceId)).thenReturn(Optional.of(source));
            when(requirementRepository.existsByProjectIdAndUidIgnoreCase(PROJECT_ID, "REQ-001-CLONE"))
                    .thenReturn(false);
            when(requirementRepository.save(any(Requirement.class))).thenAnswer(inv -> {
                var r = (Requirement) inv.getArgument(0);
                setId(r, UUID.randomUUID());
                return r;
            });
            when(relationRepository.findBySourceIdWithEntities(sourceId)).thenReturn(List.of(outgoingRelation));
            when(relationRepository.save(any(RequirementRelation.class))).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CloneRequirementCommand("REQ-001-CLONE", true);
            var result = service.clone(sourceId, cmd);

            assertThat(result.getUid()).isEqualTo("REQ-001-CLONE");
            assertThat(result.getTitle()).isEqualTo("Title for REQ-001");
            assertThat(result.getStatement()).isEqualTo("Statement for REQ-001");
            assertThat(result.getStatus()).isEqualTo(Status.DRAFT);
            assertThat(result.getRationale()).isEqualTo("Important");
            assertThat(result.getPriority()).isEqualTo(Priority.SHOULD);
            assertThat(result.getRequirementType()).isEqualTo(RequirementType.CONSTRAINT);
            assertThat(result.getWave()).isEqualTo(2);

            org.mockito.Mockito.verify(relationRepository).save(any(RequirementRelation.class));
        }

        @Test
        void clonesRequirementWithoutRelations() {
            var sourceId = UUID.randomUUID();
            var source = makeRequirement("REQ-001");
            setId(source, sourceId);
            source.setRationale("Important");
            source.setPriority(Priority.SHOULD);
            source.setRequirementType(RequirementType.CONSTRAINT);
            source.setWave(2);

            when(requirementRepository.findById(sourceId)).thenReturn(Optional.of(source));
            when(requirementRepository.existsByProjectIdAndUidIgnoreCase(PROJECT_ID, "REQ-001-CLONE"))
                    .thenReturn(false);
            when(requirementRepository.save(any(Requirement.class))).thenAnswer(inv -> {
                var r = (Requirement) inv.getArgument(0);
                setId(r, UUID.randomUUID());
                return r;
            });

            var cmd = new CloneRequirementCommand("REQ-001-CLONE", false);
            var result = service.clone(sourceId, cmd);

            assertThat(result.getUid()).isEqualTo("REQ-001-CLONE");
            assertThat(result.getTitle()).isEqualTo("Title for REQ-001");
            assertThat(result.getStatement()).isEqualTo("Statement for REQ-001");
            assertThat(result.getRationale()).isEqualTo("Important");
            assertThat(result.getPriority()).isEqualTo(Priority.SHOULD);
            assertThat(result.getRequirementType()).isEqualTo(RequirementType.CONSTRAINT);
            assertThat(result.getWave()).isEqualTo(2);

            org.mockito.Mockito.verify(relationRepository, org.mockito.Mockito.never())
                    .findBySourceIdWithEntities(any());
        }

        @Test
        void throwsConflictForDuplicateNewUid() {
            var sourceId = UUID.randomUUID();
            var source = makeRequirement("REQ-001");
            when(requirementRepository.findById(sourceId)).thenReturn(Optional.of(source));
            when(requirementRepository.existsByProjectIdAndUidIgnoreCase(PROJECT_ID, "REQ-EXISTING"))
                    .thenReturn(true);

            var cmd = new CloneRequirementCommand("REQ-EXISTING", false);

            assertThatThrownBy(() -> service.clone(sourceId, cmd)).isInstanceOf(ConflictException.class);
        }

        @Test
        void throwsNotFoundForMissingSource() {
            var sourceId = UUID.randomUUID();
            when(requirementRepository.findById(sourceId)).thenReturn(Optional.empty());

            var cmd = new CloneRequirementCommand("REQ-NEW", false);

            assertThatThrownBy(() -> service.clone(sourceId, cmd)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ListRequirements {

        @SuppressWarnings("unchecked")
        @Test
        void returnsPageWithNullFilter() {
            var page = new PageImpl<>(List.of(makeRequirement("REQ-001")));
            when(requirementRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(page);

            Page<Requirement> result = service.list(PROJECT_ID, Pageable.unpaged(), null);
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
        }

        @SuppressWarnings("unchecked")
        @Test
        void returnsFilteredPage() {
            var page = new PageImpl<>(List.of(makeRequirement("REQ-001")));
            when(requirementRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(page);

            var filter = new RequirementFilter(Status.DRAFT, null, null, null, null);
            Page<Requirement> result = service.list(PROJECT_ID, Pageable.unpaged(), filter);
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
        }
    }

    @Nested
    class DeleteRelation {

        @Test
        void deletesRelationAsSource() {
            var reqId = UUID.randomUUID();
            var relationId = UUID.randomUUID();
            var source = makeRequirement("REQ-001");
            var target = makeRequirement("REQ-002");
            setId(source, reqId);
            setId(target, UUID.randomUUID());
            var relation = new RequirementRelation(source, target, RelationType.DEPENDS_ON);
            setRelationId(relation, relationId);

            when(relationRepository.findById(relationId)).thenReturn(Optional.of(relation));

            service.deleteRelation(reqId, relationId);
            org.mockito.Mockito.verify(relationRepository).delete(relation);
        }

        @Test
        void deletesRelationAsTarget() {
            var reqId = UUID.randomUUID();
            var relationId = UUID.randomUUID();
            var source = makeRequirement("REQ-001");
            var target = makeRequirement("REQ-002");
            setId(source, UUID.randomUUID());
            setId(target, reqId);
            var relation = new RequirementRelation(source, target, RelationType.DEPENDS_ON);
            setRelationId(relation, relationId);

            when(relationRepository.findById(relationId)).thenReturn(Optional.of(relation));

            service.deleteRelation(reqId, relationId);
            org.mockito.Mockito.verify(relationRepository).delete(relation);
        }

        @Test
        void throwsNotFoundForMissingRelation() {
            var reqId = UUID.randomUUID();
            var relationId = UUID.randomUUID();
            when(relationRepository.findById(relationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteRelation(reqId, relationId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Relation not found: " + relationId);
        }

        @Test
        void throwsNotFoundWhenRelationDoesNotBelongToRequirement() {
            var reqId = UUID.randomUUID();
            var relationId = UUID.randomUUID();
            var source = makeRequirement("REQ-001");
            var target = makeRequirement("REQ-002");
            setId(source, UUID.randomUUID());
            setId(target, UUID.randomUUID());
            var relation = new RequirementRelation(source, target, RelationType.DEPENDS_ON);
            setRelationId(relation, relationId);

            when(relationRepository.findById(relationId)).thenReturn(Optional.of(relation));

            // Indistinguishable from a missing relation: identical message, no leak of the
            // relation's real source/target requirement.
            assertThatThrownBy(() -> service.deleteRelation(reqId, relationId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Relation not found: " + relationId);
        }
    }

    @Nested
    class TraceabilityMatrix {

        private TraceabilityLink linkOf(Requirement req, LinkType linkType, String artifactId) {
            return new TraceabilityLink(req, ArtifactType.CODE_FILE, artifactId, linkType);
        }

        @Test
        void groupsLinksByRequirementWithoutNPlusOne() {
            var reqA = makeRequirement("REQ-A");
            var reqB = makeRequirement("REQ-B");
            var idA = UUID.randomUUID();
            var idB = UUID.randomUUID();
            setId(reqA, idA);
            setId(reqB, idB);
            when(requirementRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(reqA, reqB)));
            when(traceabilityLinkRepository.findByRequirementIdIn(any()))
                    .thenReturn(List.of(
                            linkOf(reqA, LinkType.IMPLEMENTS, "a/Impl.java"),
                            linkOf(reqA, LinkType.TESTS, "a/ImplTest.java"),
                            linkOf(reqB, LinkType.DOCUMENTS, "docs/b.md")));

            var page = service.getTraceabilityMatrix(
                    PROJECT_ID, Pageable.unpaged(), new RequirementFilter(null, null, null, null, null), null);

            assertThat(page.getContent()).hasSize(2);
            var rowA = rowFor(page, idA);
            var rowB = rowFor(page, idB);
            assertThat(rowA.links()).hasSize(2);
            assertThat(rowB.links()).hasSize(1);
            assertThat(rowB.links().get(0).getLinkType()).isEqualTo(LinkType.DOCUMENTS);
        }

        @Test
        void filtersByLinkTypeButKeepsGapRequirements() {
            var reqA = makeRequirement("REQ-A");
            var reqB = makeRequirement("REQ-B");
            var idA = UUID.randomUUID();
            var idB = UUID.randomUUID();
            setId(reqA, idA);
            setId(reqB, idB);
            when(requirementRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(reqA, reqB)));
            when(traceabilityLinkRepository.findByRequirementIdIn(any()))
                    .thenReturn(List.of(
                            linkOf(reqA, LinkType.IMPLEMENTS, "a/Impl.java"),
                            linkOf(reqA, LinkType.TESTS, "a/ImplTest.java"),
                            linkOf(reqB, LinkType.DOCUMENTS, "docs/b.md")));

            var page = service.getTraceabilityMatrix(
                    PROJECT_ID,
                    Pageable.unpaged(),
                    new RequirementFilter(null, null, null, null, null),
                    LinkType.IMPLEMENTS);

            // Only A's IMPLEMENTS link survives the filter.
            assertThat(rowFor(page, idA).links()).hasSize(1);
            assertThat(rowFor(page, idA).links().get(0).getLinkType()).isEqualTo(LinkType.IMPLEMENTS);
            // B has no IMPLEMENTS link but still appears as a gap row, not dropped.
            assertThat(rowFor(page, idB).links()).isEmpty();
        }

        @Test
        void emptyRequirementPageSkipsTheLinkQuery() {
            when(requirementRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            var page = service.getTraceabilityMatrix(
                    PROJECT_ID, Pageable.unpaged(), new RequirementFilter(null, null, null, null, null), null);

            assertThat(page.getContent()).isEmpty();
            verifyNoInteractions(traceabilityLinkRepository);
        }

        private RequirementWithLinks rowFor(Page<RequirementWithLinks> page, UUID requirementId) {
            return page.getContent().stream()
                    .filter(row -> row.requirement().getId().equals(requirementId))
                    .findFirst()
                    .orElseThrow();
        }
    }
}

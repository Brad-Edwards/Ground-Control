package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.CloneRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementFilter;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementUidAllocator;
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

/** Split from RequirementServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class RequirementServiceCreateRelationTest {
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

    private static void setId(Requirement req, UUID id) {
        TestUtil.setField(req, "id", id);
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

            verify(relationRepository).save(any(RequirementRelation.class));
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

            verify(relationRepository, never()).findBySourceIdWithEntities(any());
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
}

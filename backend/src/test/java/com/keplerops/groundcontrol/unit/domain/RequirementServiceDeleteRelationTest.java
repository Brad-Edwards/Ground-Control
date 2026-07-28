package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.qualitygates.repository.QualityGateRepository;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementFilter;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementUidAllocator;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementWithLinks;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
class RequirementServiceDeleteRelationTest {
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

    @InjectMocks
    private RequirementService service;

    private static Requirement makeRequirement(String uid) {
        return new Requirement(TEST_PROJECT, uid, "Title for " + uid, "Statement for " + uid);
    }

    private static void setId(Requirement req, UUID id) {
        TestUtil.setField(req, "id", id);
    }

    private static void setRelationId(RequirementRelation rel, UUID id) {
        TestUtil.setField(rel, "id", id);
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
            verify(relationRepository).delete(relation);
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
            verify(relationRepository).delete(relation);
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

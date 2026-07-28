package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.AnalysisService;
import com.keplerops.groundcontrol.domain.requirements.service.AuditService;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementExportRecord;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from AnalysisServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceExtractSubgraphTest {
    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private RequirementRelationRepository relationRepository;

    @Mock
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private AnalysisService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Project TEST_PROJECT = createTestProject();

    private static Project createTestProject() {
        var project = new Project("test-project", "Test Project");
        TestUtil.setField(project, "id", PROJECT_ID);
        return project;
    }

    private static Requirement makeRequirement(String uid, UUID id) {
        var req = new Requirement(TEST_PROJECT, uid, "Title for " + uid, "Statement for " + uid);
        setField(req, "id", id);
        return req;
    }

    private static Requirement makeRequirement(String uid, UUID id, Integer wave) {
        var req = makeRequirement(uid, id);
        req.setWave(wave);
        return req;
    }

    private static void setField(Object obj, String fieldName, Object value) {
        TestUtil.setField(obj, fieldName, value);
    }

    @Nested
    class ExtractSubgraph {

        @Test
        void returnsTransitivelyReachableNodesAndEdges() {
            var aId = UUID.randomUUID();
            var bId = UUID.randomUUID();
            var cId = UUID.randomUUID();
            var dId = UUID.randomUUID();
            var a = makeRequirement("REQ-A", aId, 1);
            var b = makeRequirement("REQ-B", bId, 1);
            var c = makeRequirement("REQ-C", cId, 1);
            var d = makeRequirement("REQ-D", dId, 2);
            var relAB = new RequirementRelation(a, b, RelationType.DEPENDS_ON);
            var relBC = new RequirementRelation(b, c, RelationType.PARENT);
            setField(relAB, "id", UUID.randomUUID());
            setField(relBC, "id", UUID.randomUUID());
            // D is disconnected from A->B->C

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(a, b, c, d));
            when(relationRepository.findActiveWithSourceAndTargetByProjectId(PROJECT_ID))
                    .thenReturn(List.of(relAB, relBC));

            var result = service.extractSubgraph(PROJECT_ID, List.of("REQ-A"));

            assertThat(result.nodes()).extracting(GraphNode::uid).containsExactlyInAnyOrder("REQ-A", "REQ-B", "REQ-C");
            assertThat(result.edges())
                    .extracting(GraphEdge::edgeType)
                    .containsExactlyInAnyOrder("DEPENDS_ON", "PARENT");
        }

        @Test
        void multipleRoots_unionsReachableSets() {
            var aId = UUID.randomUUID();
            var bId = UUID.randomUUID();
            var cId = UUID.randomUUID();
            var a = makeRequirement("REQ-A", aId, 1);
            var b = makeRequirement("REQ-B", bId, 1);
            var c = makeRequirement("REQ-C", cId, 1);
            // A->B and C is separate
            var relAB = new RequirementRelation(a, b, RelationType.DEPENDS_ON);
            setField(relAB, "id", UUID.randomUUID());

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(a, b, c));
            when(relationRepository.findActiveWithSourceAndTargetByProjectId(PROJECT_ID))
                    .thenReturn(List.of(relAB));

            var result = service.extractSubgraph(PROJECT_ID, List.of("REQ-A", "REQ-C"));

            assertThat(result.nodes()).extracting(GraphNode::uid).containsExactlyInAnyOrder("REQ-A", "REQ-B", "REQ-C");
            assertThat(result.edges()).extracting(GraphEdge::edgeType).containsExactlyInAnyOrder("DEPENDS_ON");
        }

        @Test
        void unknownRoot_throwsNotFoundException() {
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of());
            when(relationRepository.findActiveWithSourceAndTargetByProjectId(PROJECT_ID))
                    .thenReturn(List.of());

            var unknownUids = List.of("REQ-UNKNOWN");
            assertThatThrownBy(() -> service.extractSubgraph(PROJECT_ID, unknownUids))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class GetRequirementsExportData {

        @Test
        void emptyProject_returnsEmptyList() {
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of());

            List<RequirementExportRecord> result = service.getRequirementsExportData(PROJECT_ID);

            assertThat(result).isEmpty();
        }

        @Test
        void requirementWithNoLinks_hasEmptyLinks() {
            UUID reqId = UUID.randomUUID();
            var req = makeRequirement("REQ-A", reqId);

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(req));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyCollection()))
                    .thenReturn(List.of());

            List<RequirementExportRecord> result = service.getRequirementsExportData(PROJECT_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).requirement()).isSameAs(req);
            assertThat(result.get(0).traceabilityLinks()).isEmpty();
        }

        @Test
        void linksGroupedCorrectlyByRequirement() {
            UUID aId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            var reqA = makeRequirement("REQ-A", aId);
            var reqB = makeRequirement("REQ-B", bId);
            var linkA = new TraceabilityLink(reqA, ArtifactType.GITHUB_ISSUE, "issue-1", LinkType.IMPLEMENTS);
            var linkB1 = new TraceabilityLink(reqB, ArtifactType.GITHUB_ISSUE, "issue-2", LinkType.TESTS);
            var linkB2 = new TraceabilityLink(reqB, ArtifactType.GITHUB_ISSUE, "issue-3", LinkType.DOCUMENTS);

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(reqA, reqB));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyCollection()))
                    .thenReturn(List.of(linkA, linkB1, linkB2));

            List<RequirementExportRecord> result = service.getRequirementsExportData(PROJECT_ID);

            assertThat(result).hasSize(2);
            RequirementExportRecord exportA = result.stream()
                    .filter(r -> r.requirement().equals(reqA))
                    .findFirst()
                    .orElseThrow();
            assertThat(exportA.traceabilityLinks()).containsExactly(linkA);
            RequirementExportRecord exportB = result.stream()
                    .filter(r -> r.requirement().equals(reqB))
                    .findFirst()
                    .orElseThrow();
            assertThat(exportB.traceabilityLinks()).containsExactlyInAnyOrder(linkB1, linkB2);
        }

        @Test
        void singleBatchQueryIssuedForLinks() {
            UUID aId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            UUID cId = UUID.randomUUID();
            var reqA = makeRequirement("REQ-A", aId);
            var reqB = makeRequirement("REQ-B", bId);
            var reqC = makeRequirement("REQ-C", cId);

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(reqA, reqB, reqC));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyCollection()))
                    .thenReturn(List.of());

            List<RequirementExportRecord> result = service.getRequirementsExportData(PROJECT_ID);

            assertThat(result).hasSize(3);
            verify(traceabilityLinkRepository, times(1)).findByRequirementIdIn(anyCollection());
            verify(traceabilityLinkRepository, never()).findByRequirementId(Mockito.any());
        }
    }
}

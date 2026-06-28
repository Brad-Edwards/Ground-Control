package com.keplerops.groundcontrol.unit.domain.graph;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.service.ResearchGraphProjectionContributor;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceEdgeRelation;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceNodeKind;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceRecordStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceEdge;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceNode;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.repository.ResearchProvenanceEdgeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchProvenanceNodeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResearchGraphProjectionContributorTest {

    @Mock
    private ResearchRunRepository runRepository;

    @Mock
    private ResearchRunArtifactRepository artifactRepository;

    @Mock
    private ResearchProvenanceNodeRepository provenanceNodeRepository;

    @Mock
    private ResearchProvenanceEdgeRepository provenanceEdgeRepository;

    @InjectMocks
    private ResearchGraphProjectionContributor contributor;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    @Test
    void projectsOneNodePerRunArtifactAndProvenanceNode() {
        var run = newRun("RUN-1", ResearchRunStatus.IN_PROGRESS);
        var artifact = newArtifact(run, ResearchArtifactType.PROTOCOL_PLAN);
        artifact.setContentHash("sha256:abc");
        var node = newNode(run, ProvenanceNodeKind.USER_GOAL, "goal-1");
        node.setExternalIdentifier("doi:10.1/x");

        when(runRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(run));
        when(artifactRepository.findByProjectIdAndStatus(projectId, ResearchArtifactStatus.ACTIVE))
                .thenReturn(List.of(artifact));
        when(provenanceNodeRepository.findByProjectIdAndStatus(projectId, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of(node));

        var nodes = contributor.contributeNodes(projectId);

        assertThat(nodes)
                .hasSize(3)
                .allMatch(n -> n.projectIdentifier().equals("ground-control"))
                .extracting(n -> n.entityType())
                .containsExactlyInAnyOrder(
                        GraphEntityType.RESEARCH_RUN,
                        GraphEntityType.RESEARCH_ARTIFACT,
                        GraphEntityType.RESEARCH_PROVENANCE_NODE);

        var runNode = nodeOfType(nodes, GraphEntityType.RESEARCH_RUN);
        assertThat(runNode.properties())
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("currentStage", "METHODOLOGY_SELECTION")
                .containsEntry("autonomyLevel", "AUTONOMOUS")
                .containsKey("startedAt");

        var artifactNode = nodeOfType(nodes, GraphEntityType.RESEARCH_ARTIFACT);
        assertThat(artifactNode.properties())
                .containsEntry("artifactType", "PROTOCOL_PLAN")
                .containsEntry(
                        "stage",
                        ResearchArtifactType.PROTOCOL_PLAN.producingStage().name())
                .containsEntry("status", "ACTIVE")
                .containsEntry("attemptNo", 1)
                .containsEntry("contentHash", "sha256:abc");

        var provNode = nodeOfType(nodes, GraphEntityType.RESEARCH_PROVENANCE_NODE);
        assertThat(provNode.properties())
                .containsEntry("kind", "USER_GOAL")
                .containsEntry("status", "ACTIVE")
                .containsEntry("externalIdentifier", "doi:10.1/x");
    }

    @Test
    void excludesFailedRunsAndTheirArtifactsAndNodes() {
        var liveRun = newRun("RUN-LIVE", ResearchRunStatus.COMPLETED);
        var failedRun = newRun("RUN-FAILED", ResearchRunStatus.FAILED);
        var liveArtifact = newArtifact(liveRun, ResearchArtifactType.SEARCH_LOG);
        var failedArtifact = newArtifact(failedRun, ResearchArtifactType.SEARCH_LOG);
        var liveNode = newNode(liveRun, ProvenanceNodeKind.QUERY, "q-1");
        var failedNode = newNode(failedRun, ProvenanceNodeKind.QUERY, "q-2");

        when(runRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(liveRun, failedRun));
        when(artifactRepository.findByProjectIdAndStatus(projectId, ResearchArtifactStatus.ACTIVE))
                .thenReturn(List.of(liveArtifact, failedArtifact));
        when(provenanceNodeRepository.findByProjectIdAndStatus(projectId, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of(liveNode, failedNode));

        var nodes = contributor.contributeNodes(projectId);

        // Only the live run's run/artifact/provenance nodes survive; the FAILED
        // run and everything hanging off it stays out of the default projection.
        assertThat(nodes)
                .hasSize(3)
                .noneMatch(n -> n.id().equals(GraphIds.nodeId(GraphEntityType.RESEARCH_RUN, failedRun.getId())))
                .noneMatch(
                        n -> n.id().equals(GraphIds.nodeId(GraphEntityType.RESEARCH_ARTIFACT, failedArtifact.getId())))
                .noneMatch(n ->
                        n.id().equals(GraphIds.nodeId(GraphEntityType.RESEARCH_PROVENANCE_NODE, failedNode.getId())));
    }

    @Test
    void doesNotProjectRawResearchContent() {
        var run = newRun("RUN-1", ResearchRunStatus.IN_PROGRESS);
        var node = newNode(run, ProvenanceNodeKind.SYNTHESIS_CLAIM, "claim-1");
        node.setSummary("unpublished synthesis prose that must never reach the graph");
        node.setLocator("/home/user/private/research/run/full-text.pdf");

        when(runRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(run));
        when(artifactRepository.findByProjectIdAndStatus(projectId, ResearchArtifactStatus.ACTIVE))
                .thenReturn(List.of());
        when(provenanceNodeRepository.findByProjectIdAndStatus(projectId, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of(node));

        var provNode = nodeOfType(contributor.contributeNodes(projectId), GraphEntityType.RESEARCH_PROVENANCE_NODE);

        assertThat(provNode.properties()).doesNotContainKeys("summary", "locator", "subjectKey", "toolName");
        assertThat(provNode.label()).doesNotContain("unpublished");
        assertThat(provNode.properties().values())
                .noneMatch(v -> String.valueOf(v).contains("unpublished"))
                .noneMatch(v -> String.valueOf(v).contains("/home/user"));
    }

    @Test
    void projectsStructuralAndProvenanceEdgesPreservingDirection() {
        var run = newRun("RUN-1", ResearchRunStatus.COMPLETED);
        var artifact = newArtifact(run, ResearchArtifactType.CHARTING_DATA);
        var upstream = newNode(run, ProvenanceNodeKind.CANDIDATE_SOURCE, "src-1");
        var downstream = newNode(run, ProvenanceNodeKind.SYNTHESIS_CLAIM, "claim-1");
        downstream.setArtifactId(artifact.getId());
        var edge = newEdge(run, upstream.getId(), downstream.getId(), ProvenanceEdgeRelation.SUPPORTS);

        stubAll(List.of(run), List.of(artifact), List.of(upstream, downstream), List.of(edge));

        var edges = contributor.contributeEdges(projectId);

        assertThat(edges)
                .extracting(e -> e.edgeType())
                .containsExactlyInAnyOrder("HAS_RESEARCH_ARTIFACT", "ARTIFACT_HAS_PROVENANCE", "SUPPORTS");

        var hasArtifact = edges.stream()
                .filter(e -> e.edgeType().equals("HAS_RESEARCH_ARTIFACT"))
                .findFirst()
                .orElseThrow();
        assertThat(hasArtifact.sourceId()).isEqualTo(GraphIds.nodeId(GraphEntityType.RESEARCH_RUN, run.getId()));
        assertThat(hasArtifact.targetId())
                .isEqualTo(GraphIds.nodeId(GraphEntityType.RESEARCH_ARTIFACT, artifact.getId()));

        var artifactHasProv = edges.stream()
                .filter(e -> e.edgeType().equals("ARTIFACT_HAS_PROVENANCE"))
                .findFirst()
                .orElseThrow();
        assertThat(artifactHasProv.sourceId())
                .isEqualTo(GraphIds.nodeId(GraphEntityType.RESEARCH_ARTIFACT, artifact.getId()));
        assertThat(artifactHasProv.targetId())
                .isEqualTo(GraphIds.nodeId(GraphEntityType.RESEARCH_PROVENANCE_NODE, downstream.getId()));

        var supports = edges.stream()
                .filter(e -> e.edgeType().equals("SUPPORTS"))
                .findFirst()
                .orElseThrow();
        // ADR-069 direction: upstream input -> downstream output. A swap would
        // make backward traversal contradict the ledger.
        assertThat(supports.sourceId())
                .isEqualTo(GraphIds.nodeId(GraphEntityType.RESEARCH_PROVENANCE_NODE, upstream.getId()));
        assertThat(supports.targetId())
                .isEqualTo(GraphIds.nodeId(GraphEntityType.RESEARCH_PROVENANCE_NODE, downstream.getId()));
    }

    @Test
    void omitsProvenanceEdgesWithAnEndpointOutsideTheProjectedNodeSet() {
        var run = newRun("RUN-1", ResearchRunStatus.COMPLETED);
        var present = newNode(run, ProvenanceNodeKind.CANDIDATE_SOURCE, "src-1");
        var missingId = UUID.randomUUID();
        // Edge points at a node that is not in the ACTIVE projected set (e.g. a
        // SUPERSEDED node) — projecting it would create a dangling graph edge.
        var dangling = newEdge(run, present.getId(), missingId, ProvenanceEdgeRelation.DERIVED_FROM);

        stubAll(List.of(run), List.of(), List.of(present), List.of(dangling));

        assertThat(contributor.contributeEdges(projectId))
                .noneMatch(e -> e.edgeType().equals("DERIVED_FROM"));
    }

    private void stubAll(
            List<ResearchRun> runs,
            List<ResearchRunArtifact> artifacts,
            List<ResearchProvenanceNode> nodes,
            List<ResearchProvenanceEdge> edges) {
        when(runRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(runs);
        when(artifactRepository.findByProjectIdAndStatus(projectId, ResearchArtifactStatus.ACTIVE))
                .thenReturn(artifacts);
        when(provenanceNodeRepository.findByProjectIdAndStatus(projectId, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(nodes);
        when(provenanceEdgeRepository.findByProjectIdAndStatus(projectId, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(edges);
    }

    private ResearchRun newRun(String uid, ResearchRunStatus status) {
        var run = new ResearchRun(project, uid, AutonomyLevel.AUTONOMOUS);
        setField(run, "id", UUID.randomUUID());
        setField(run, "status", status);
        return run;
    }

    private ResearchRunArtifact newArtifact(ResearchRun run, ResearchArtifactType type) {
        var artifact = new ResearchRunArtifact(run, type, 1);
        setField(artifact, "id", UUID.randomUUID());
        return artifact;
    }

    private ResearchProvenanceNode newNode(ResearchRun run, ProvenanceNodeKind kind, String subjectKey) {
        var node = new ResearchProvenanceNode(run, kind, subjectKey);
        setField(node, "id", UUID.randomUUID());
        return node;
    }

    private ResearchProvenanceEdge newEdge(ResearchRun run, UUID fromNodeId, UUID toNodeId, ProvenanceEdgeRelation r) {
        var edge = new ResearchProvenanceEdge(run, fromNodeId, toNodeId, r);
        setField(edge, "id", UUID.randomUUID());
        return edge;
    }

    private static com.keplerops.groundcontrol.domain.graph.model.GraphNode nodeOfType(
            List<com.keplerops.groundcontrol.domain.graph.model.GraphNode> nodes, GraphEntityType type) {
        return nodes.stream().filter(n -> n.entityType() == type).findFirst().orElseThrow();
    }
}

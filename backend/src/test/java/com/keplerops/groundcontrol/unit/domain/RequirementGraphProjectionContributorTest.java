package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.service.RequirementGraphProjectionContributor;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RequirementGraphProjectionContributorTest {

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private RequirementRelationRepository relationRepository;

    @Mock
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private RiskScenarioRepository riskScenarioRepository;

    @InjectMocks
    private RequirementGraphProjectionContributor contributor;

    @Test
    void contributesRequirementNodesAndRelationEdges() {
        var fixture = fixture();
        var target = requirement(fixture.project(), "REQ-2", "Audit");
        var relation = new RequirementRelation(fixture.requirement(), target, RelationType.DEPENDS_ON);
        setField(relation, "id", UUID.randomUUID());
        setField(relation, "createdAt", Instant.parse("2026-04-02T12:00:00Z"));

        when(requirementRepository.findByProjectIdAndArchivedAtIsNull(fixture.projectId()))
                .thenReturn(List.of(fixture.requirement(), target));
        when(relationRepository.findActiveWithSourceAndTargetByProjectId(fixture.projectId()))
                .thenReturn(List.of(relation));
        noTraceabilityLinks(fixture.projectId());

        var nodes = contributor.contributeNodes(fixture.projectId());
        var edges = contributor.contributeEdges(fixture.projectId());

        assertThat(nodes).hasSize(2);
        assertThat(nodes.getFirst().id())
                .isEqualTo(GraphIds.nodeId(
                        GraphEntityType.REQUIREMENT, fixture.requirement().getId()));
        assertThat(nodes.getFirst().properties())
                .containsEntry("title", "Identity")
                .containsEntry("wave", 2);
        assertThat(edges).singleElement().satisfies(edge -> {
            assertThat(edge.edgeType()).isEqualTo("DEPENDS_ON");
            assertThat(edge.sourceId())
                    .isEqualTo(GraphIds.nodeId(
                            GraphEntityType.REQUIREMENT, fixture.requirement().getId()));
            assertThat(edge.targetId()).isEqualTo(GraphIds.nodeId(GraphEntityType.REQUIREMENT, target.getId()));
            assertThat(edge.properties()).containsEntry("sourceUid", "REQ-1").containsEntry("targetUid", "REQ-2");
        });
    }

    @Test
    void projectsEveryTraceabilityLinkTypeToOneSharedArtifactReference() {
        var fixture = fixture();
        String identifier = "src/main/java/Exact Case.java";
        var links = List.of(
                traceability(fixture.requirement(), ArtifactType.CODE_FILE, identifier, LinkType.IMPLEMENTS),
                traceability(fixture.requirement(), ArtifactType.CODE_FILE, identifier, LinkType.TESTS),
                traceability(fixture.requirement(), ArtifactType.CODE_FILE, identifier, LinkType.DOCUMENTS),
                traceability(fixture.requirement(), ArtifactType.CODE_FILE, identifier, LinkType.CONSTRAINS),
                traceability(fixture.requirement(), ArtifactType.CODE_FILE, identifier, LinkType.VERIFIES));
        stubProjection(fixture, links);

        var nodes = contributor.contributeNodes(fixture.projectId());
        var edges = contributor.contributeEdges(fixture.projectId());

        String artifactNodeId =
                GraphIds.artifactReferenceNodeId(fixture.projectId(), ArtifactType.CODE_FILE, identifier);
        assertThat(nodes)
                .filteredOn(node -> node.entityType() == GraphEntityType.ARTIFACT_REFERENCE)
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.id()).isEqualTo(artifactNodeId);
                    assertThat(node.uid()).isEqualTo(identifier);
                    assertThat(node.properties())
                            .containsEntry("artifactType", "CODE_FILE")
                            .containsEntry("artifactIdentifier", identifier);
                });
        assertThat(edges)
                .extracting(GraphEdge::edgeType)
                .containsExactlyInAnyOrder("IMPLEMENTS", "TESTS", "DOCUMENTS", "CONSTRAINS", "VERIFIES");
        assertThat(edges).allSatisfy(edge -> {
            assertThat(edge.sourceId())
                    .isEqualTo(GraphIds.nodeId(
                            GraphEntityType.REQUIREMENT, fixture.requirement().getId()));
            assertThat(edge.targetId()).isEqualTo(artifactNodeId);
            assertThat(edge.targetEntityType()).isEqualTo(GraphEntityType.ARTIFACT_REFERENCE);
        });
    }

    @Test
    void resolvesFirstClassTargetsInProjectAndFallsBackForArchivedOrMissingTargets() {
        var fixture = fixture();
        var control = new Control(fixture.project(), "CTRL-1", "Access control", ControlFunction.PREVENTIVE);
        setField(control, "id", UUID.randomUUID());
        var liveRisk = risk(fixture.project(), "RS-1");
        var archivedRisk = risk(fixture.project(), "RS-ARCHIVED");
        archivedRisk.transitionStatus(RiskScenarioStatus.ARCHIVED);
        var links = List.of(
                traceability(fixture.requirement(), ArtifactType.CONTROL, "CTRL-1", LinkType.IMPLEMENTS),
                traceability(fixture.requirement(), ArtifactType.RISK_SCENARIO, "RS-1", LinkType.CONSTRAINS),
                traceability(fixture.requirement(), ArtifactType.RISK_SCENARIO, "RS-ARCHIVED", LinkType.DOCUMENTS),
                traceability(fixture.requirement(), ArtifactType.CONTROL, "CTRL-MISSING", LinkType.VERIFIES));
        stubProjection(fixture, links);
        when(controlRepository.findByProjectIdAndUidIn(fixture.projectId(), Set.of("CTRL-1", "CTRL-MISSING")))
                .thenReturn(List.of(control));
        when(riskScenarioRepository.findByProjectIdAndUidIn(fixture.projectId(), Set.of("RS-1", "RS-ARCHIVED")))
                .thenReturn(List.of(liveRisk, archivedRisk));

        var nodes = contributor.contributeNodes(fixture.projectId());
        var edges = contributor.contributeEdges(fixture.projectId());

        assertThat(edges)
                .filteredOn(edge -> edge.edgeType().equals("IMPLEMENTS"))
                .singleElement()
                .satisfies(edge -> {
                    assertThat(edge.targetId()).isEqualTo(GraphIds.nodeId(GraphEntityType.CONTROL, control.getId()));
                    assertThat(edge.targetEntityType()).isEqualTo(GraphEntityType.CONTROL);
                });
        assertThat(edges)
                .filteredOn(edge -> edge.edgeType().equals("CONSTRAINS"))
                .singleElement()
                .satisfies(edge -> {
                    assertThat(edge.targetId())
                            .isEqualTo(GraphIds.nodeId(GraphEntityType.RISK_SCENARIO, liveRisk.getId()));
                    assertThat(edge.targetEntityType()).isEqualTo(GraphEntityType.RISK_SCENARIO);
                });
        assertThat(edges)
                .filteredOn(edge -> Set.of("DOCUMENTS", "VERIFIES").contains(edge.edgeType()))
                .allMatch(edge -> edge.targetEntityType() == GraphEntityType.ARTIFACT_REFERENCE);
        assertThat(nodes)
                .filteredOn(node -> node.entityType() == GraphEntityType.ARTIFACT_REFERENCE)
                .extracting(node -> node.properties().get("artifactIdentifier"))
                .containsExactlyInAnyOrder("RS-ARCHIVED", "CTRL-MISSING");
    }

    @Test
    void projectScopeChangesArtifactReferenceIdentity() {
        var otherProject = new Project("other", "Other");
        var otherProjectId = UUID.randomUUID();
        setField(otherProject, "id", otherProjectId);
        String identifier = "shared/file.java";

        assertThat(GraphIds.artifactReferenceNodeId(otherProjectId, ArtifactType.CODE_FILE, identifier))
                .isNotEqualTo(
                        GraphIds.artifactReferenceNodeId(fixture().projectId(), ArtifactType.CODE_FILE, identifier));
    }

    private Fixture fixture() {
        var project = new Project("ground-control", "Ground Control");
        var projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        var requirement = requirement(project, "REQ-1", "Identity");
        requirement.setWave(2);
        return new Fixture(project, projectId, requirement);
    }

    private Requirement requirement(Project project, String uid, String title) {
        var requirement = new Requirement(project, uid, title, title + " statement");
        setField(requirement, "id", UUID.randomUUID());
        return requirement;
    }

    private RiskScenario risk(Project project, String uid) {
        var risk = new RiskScenario(project, uid, uid, "threat", "method", "asset", "effect");
        setField(risk, "id", UUID.randomUUID());
        return risk;
    }

    private TraceabilityLink traceability(
            Requirement requirement, ArtifactType artifactType, String identifier, LinkType linkType) {
        var link = new TraceabilityLink(requirement, artifactType, identifier, linkType);
        setField(link, "id", UUID.randomUUID());
        link.setArtifactTitle("Title for " + identifier);
        link.setArtifactUrl("https://example.invalid/" + identifier);
        return link;
    }

    private void stubProjection(Fixture fixture, List<TraceabilityLink> links) {
        when(requirementRepository.findByProjectIdAndArchivedAtIsNull(fixture.projectId()))
                .thenReturn(List.of(fixture.requirement()));
        when(relationRepository.findActiveWithSourceAndTargetByProjectId(fixture.projectId()))
                .thenReturn(List.of());
        when(traceabilityLinkRepository.findLiveRequirementLinksByProjectId(fixture.projectId()))
                .thenReturn(links);
    }

    private void noTraceabilityLinks(UUID projectId) {
        when(traceabilityLinkRepository.findLiveRequirementLinksByProjectId(projectId))
                .thenReturn(List.of());
    }

    private record Fixture(Project project, UUID projectId, Requirement requirement) {}
}

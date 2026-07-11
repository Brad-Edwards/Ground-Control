package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.audits.model.Audit;
import com.keplerops.groundcontrol.domain.audits.model.AuditLink;
import com.keplerops.groundcontrol.domain.audits.repository.AuditLinkRepository;
import com.keplerops.groundcontrol.domain.audits.repository.AuditRepository;
import com.keplerops.groundcontrol.domain.audits.service.AuditGraphProjectionContributor;
import com.keplerops.groundcontrol.domain.audits.state.AuditLinkTargetType;
import com.keplerops.groundcontrol.domain.audits.state.AuditLinkType;
import com.keplerops.groundcontrol.domain.audits.state.AuditStatus;
import com.keplerops.groundcontrol.domain.audits.state.AuditType;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditGraphProjectionContributorTest {

    @Mock
    private AuditRepository auditRepository;

    @Mock
    private AuditLinkRepository auditLinkRepository;

    @Mock
    private OperationalAssetRepository operationalAssetRepository;

    @Mock
    private RiskScenarioRepository riskScenarioRepository;

    @InjectMocks
    private AuditGraphProjectionContributor contributor;

    private Project makeProject(UUID projectId) {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", projectId);
        return project;
    }

    private Audit newAudit(Project project, String uid, String title) {
        var a = new Audit(project, uid, title, AuditType.INTERNAL, "All production systems.");
        setField(a, "id", UUID.randomUUID());
        return a;
    }

    @Test
    void contributesNodesForAllStatuses() {
        var projectId = UUID.randomUUID();
        var project = makeProject(projectId);

        var planned = newAudit(project, "AUDIT-1", "Planned audit");
        var inProgress = newAudit(project, "AUDIT-2", "In-progress audit");
        inProgress.transitionStatus(AuditStatus.IN_PROGRESS);
        var closed = newAudit(project, "AUDIT-3", "Closed audit");
        closed.transitionStatus(AuditStatus.IN_PROGRESS);
        closed.transitionStatus(AuditStatus.DRAFT_REPORT);
        closed.transitionStatus(AuditStatus.FINAL_REPORT);
        closed.transitionStatus(AuditStatus.CLOSED);

        when(auditRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(planned, inProgress, closed));

        var nodes = contributor.contributeNodes(projectId);

        assertThat(nodes).hasSize(3).allMatch(n -> n.entityType() == GraphEntityType.AUDIT);
        assertThat(nodes.stream().map(n -> n.properties().get("status")))
                .containsExactlyInAnyOrder("PLANNED", "IN_PROGRESS", "CLOSED");
        assertThat(nodes.get(0).id()).isEqualTo(GraphIds.nodeId(GraphEntityType.AUDIT, planned.getId()));
        assertThat(nodes.get(0).properties())
                .containsEntry("auditType", "INTERNAL")
                .containsEntry("uid", "AUDIT-1")
                .containsEntry("title", "Planned audit");
    }

    @Test
    void nodePropertiesIncludeCountsAndProjectIdentifier() {
        var projectId = UUID.randomUUID();
        var project = makeProject(projectId);
        var audit = newAudit(project, "AUDIT-1", "Audit with data");
        audit.setObjectives(List.of("Obj A", "Obj B"));
        audit.setTeamMembers(List.of("alice"));
        audit.setCreatedBy("analyst");

        when(auditRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(audit));

        var nodes = contributor.contributeNodes(projectId);

        assertThat(nodes).hasSize(1);
        var props = nodes.get(0).properties();
        assertThat(props)
                .containsEntry("projectIdentifier", "ground-control")
                .containsEntry("objectiveCount", 2)
                .containsEntry("phaseCount", 0)
                .containsEntry("teamMemberCount", 1)
                .containsEntry("createdBy", "analyst");
    }

    @Test
    void omitsNullOptionalFieldsFromNodeProperties() {
        var projectId = UUID.randomUUID();
        var project = makeProject(projectId);
        var audit = newAudit(project, "AUDIT-SPARSE", "Sparse audit");

        when(auditRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(audit));

        var nodes = contributor.contributeNodes(projectId);

        var props = nodes.get(0).properties();
        assertThat(props)
                .doesNotContainKey("createdBy")
                .doesNotContainKey("createdAt")
                .doesNotContainKey("updatedAt")
                .containsEntry("title", "Sparse audit");
    }

    @Test
    void contributesEdgesOnlyForInternalTargets() {
        var projectId = UUID.randomUUID();
        var project = makeProject(projectId);
        var audit = newAudit(project, "AUDIT-1", "Audit");

        var controlId = UUID.randomUUID();
        var controlLink = new AuditLink(audit, AuditLinkTargetType.CONTROL, controlId, null, AuditLinkType.ASSESSES);
        setField(controlLink, "id", UUID.randomUUID());

        var assetId = UUID.randomUUID();
        var assetLink = new AuditLink(audit, AuditLinkTargetType.ASSET, assetId, null, AuditLinkType.SCOPES);
        setField(assetLink, "id", UUID.randomUUID());

        var externalFrameworkLink =
                new AuditLink(audit, AuditLinkTargetType.FRAMEWORK, null, "ISO-27001", AuditLinkType.SCOPES);
        setField(externalFrameworkLink, "id", UUID.randomUUID());

        var externalLink =
                new AuditLink(audit, AuditLinkTargetType.EXTERNAL, null, "legacy-ref", AuditLinkType.ASSOCIATED);
        setField(externalLink, "id", UUID.randomUUID());

        when(auditLinkRepository.findByProjectId(projectId))
                .thenReturn(List.of(controlLink, assetLink, externalFrameworkLink, externalLink));
        when(operationalAssetRepository.findIdsByProjectIdAndArchivedAtIsNull(projectId))
                .thenReturn(List.of(assetId));
        when(riskScenarioRepository.findIdsByProjectIdAndStatusNot(projectId, RiskScenarioStatus.ARCHIVED))
                .thenReturn(List.of());

        var edges = contributor.contributeEdges(projectId);

        assertThat(edges).hasSize(2);
        assertThat(edges.stream().map(e -> e.targetEntityType()))
                .containsExactlyInAnyOrder(GraphEntityType.CONTROL, GraphEntityType.OPERATIONAL_ASSET);
        assertThat(edges).allMatch(e -> e.sourceEntityType() == GraphEntityType.AUDIT);
    }

    @Test
    void edgesMapAllSupportedInternalTargetTypes() {
        var projectId = UUID.randomUUID();
        var project = makeProject(projectId);
        var audit = newAudit(project, "AUDIT-EDGES", "Audit with all targets");

        var riskScenarioId = UUID.randomUUID();
        var rsLink =
                new AuditLink(audit, AuditLinkTargetType.RISK_SCENARIO, riskScenarioId, null, AuditLinkType.ASSESSES);
        setField(rsLink, "id", UUID.randomUUID());

        // RISK_REGISTER_RECORD is a retired target type (ADR-089); it must never
        // produce an edge even though the enum constant remains for historical rows.
        var riskRegisterId = UUID.randomUUID();
        var rrLink = new AuditLink(
                audit, AuditLinkTargetType.RISK_REGISTER_RECORD, riskRegisterId, null, AuditLinkType.ASSESSES);
        setField(rrLink, "id", UUID.randomUUID());

        var evidenceId = UUID.randomUUID();
        var evLink = new AuditLink(audit, AuditLinkTargetType.EVIDENCE, evidenceId, null, AuditLinkType.EVIDENCED_BY);
        setField(evLink, "id", UUID.randomUUID());

        var findingId = UUID.randomUUID();
        var findLink = new AuditLink(audit, AuditLinkTargetType.FINDING, findingId, null, AuditLinkType.FOLLOWS_UP_ON);
        setField(findLink, "id", UUID.randomUUID());

        when(auditLinkRepository.findByProjectId(projectId)).thenReturn(List.of(rsLink, rrLink, evLink, findLink));
        when(operationalAssetRepository.findIdsByProjectIdAndArchivedAtIsNull(projectId))
                .thenReturn(List.of());
        when(riskScenarioRepository.findIdsByProjectIdAndStatusNot(projectId, RiskScenarioStatus.ARCHIVED))
                .thenReturn(List.of(riskScenarioId));

        var edges = contributor.contributeEdges(projectId);

        // rrLink (RISK_REGISTER_RECORD) is a retired target type and produces no edge.
        assertThat(edges).hasSize(3);
        assertThat(edges.stream().map(e -> e.targetEntityType()))
                .containsExactlyInAnyOrder(
                        GraphEntityType.RISK_SCENARIO, GraphEntityType.EVIDENCE_ARTIFACT, GraphEntityType.FINDING);
    }

    @Test
    void skipsEdgesWithNullTargetEntityId() {
        var projectId = UUID.randomUUID();
        var project = makeProject(projectId);
        var audit = newAudit(project, "AUDIT-1", "Audit");

        var internalLink =
                new AuditLink(audit, AuditLinkTargetType.CONTROL, UUID.randomUUID(), null, AuditLinkType.ASSESSES);
        setField(internalLink, "id", UUID.randomUUID());

        var nullIdLink = new AuditLink(audit, AuditLinkTargetType.CONTROL, null, null, AuditLinkType.ASSESSES);
        setField(nullIdLink, "id", UUID.randomUUID());

        when(auditLinkRepository.findByProjectId(projectId)).thenReturn(List.of(internalLink, nullIdLink));
        when(operationalAssetRepository.findIdsByProjectIdAndArchivedAtIsNull(projectId))
                .thenReturn(List.of());
        when(riskScenarioRepository.findIdsByProjectIdAndStatusNot(projectId, RiskScenarioStatus.ARCHIVED))
                .thenReturn(List.of());

        var edges = contributor.contributeEdges(projectId);

        assertThat(edges).hasSize(1);
    }

    @Test
    void skipsEdgesToArchivedAssets() {
        var projectId = UUID.randomUUID();
        var project = makeProject(projectId);
        var audit = newAudit(project, "AUDIT-ARCH", "Audit with archived target");

        var liveAssetId = UUID.randomUUID();
        var archivedAssetId = UUID.randomUUID();

        var liveLink = new AuditLink(audit, AuditLinkTargetType.ASSET, liveAssetId, null, AuditLinkType.SCOPES);
        setField(liveLink, "id", UUID.randomUUID());

        var staleLink = new AuditLink(audit, AuditLinkTargetType.ASSET, archivedAssetId, null, AuditLinkType.SCOPES);
        setField(staleLink, "id", UUID.randomUUID());

        when(auditLinkRepository.findByProjectId(projectId)).thenReturn(List.of(liveLink, staleLink));
        when(operationalAssetRepository.findIdsByProjectIdAndArchivedAtIsNull(projectId))
                .thenReturn(List.of(liveAssetId));
        when(riskScenarioRepository.findIdsByProjectIdAndStatusNot(projectId, RiskScenarioStatus.ARCHIVED))
                .thenReturn(List.of());

        var edges = contributor.contributeEdges(projectId);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).targetId()).isEqualTo(GraphIds.nodeId(GraphEntityType.OPERATIONAL_ASSET, liveAssetId));
    }
}

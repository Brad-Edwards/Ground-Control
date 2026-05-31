package com.keplerops.groundcontrol.domain.audits.service;

import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.audits.repository.AuditLinkRepository;
import com.keplerops.groundcontrol.domain.audits.repository.AuditRepository;
import com.keplerops.groundcontrol.domain.audits.state.AuditLinkTargetType;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.graph.service.GraphProjectionContributor;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AuditGraphProjectionContributor implements GraphProjectionContributor {

    private final AuditRepository auditRepository;
    private final AuditLinkRepository auditLinkRepository;
    private final OperationalAssetRepository operationalAssetRepository;
    private final RiskScenarioRepository riskScenarioRepository;

    public AuditGraphProjectionContributor(
            AuditRepository auditRepository,
            AuditLinkRepository auditLinkRepository,
            OperationalAssetRepository operationalAssetRepository,
            RiskScenarioRepository riskScenarioRepository) {
        this.auditRepository = auditRepository;
        this.auditLinkRepository = auditLinkRepository;
        this.operationalAssetRepository = operationalAssetRepository;
        this.riskScenarioRepository = riskScenarioRepository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        return auditRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(a -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("uid", a.getUid());
                    properties.put("title", a.getTitle());
                    properties.put("auditType", a.getAuditType().name());
                    properties.put("status", a.getStatus().name());
                    properties.put("projectIdentifier", a.getProject().getIdentifier());
                    properties.put("phaseCount", a.getPhases().size());
                    properties.put("objectiveCount", a.getObjectives().size());
                    properties.put("teamMemberCount", a.getTeamMembers().size());
                    if (a.getCreatedBy() != null) {
                        properties.put("createdBy", a.getCreatedBy());
                    }
                    if (a.getCreatedAt() != null) {
                        properties.put("createdAt", a.getCreatedAt());
                    }
                    if (a.getUpdatedAt() != null) {
                        properties.put("updatedAt", a.getUpdatedAt());
                    }
                    return new GraphNode(
                            GraphIds.nodeId(GraphEntityType.AUDIT, a.getId()),
                            a.getId().toString(),
                            GraphEntityType.AUDIT,
                            a.getProject().getIdentifier(),
                            a.getUid(),
                            a.getUid(),
                            properties);
                })
                .toList();
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        Set<UUID> liveAssetIds =
                Set.copyOf(operationalAssetRepository.findIdsByProjectIdAndArchivedAtIsNull(projectId));
        Set<UUID> liveRiskScenarioIds = Set.copyOf(
                riskScenarioRepository.findIdsByProjectIdAndStatusNot(projectId, RiskScenarioStatus.ARCHIVED));

        return auditLinkRepository.findByProjectId(projectId).stream()
                .map(link -> toAuditLinkEdge(
                        link.getId(),
                        link.getAudit().getId(),
                        link.getTargetType(),
                        link.getTargetEntityId(),
                        link.getLinkType().name(),
                        liveAssetIds,
                        liveRiskScenarioIds))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Maps an audit link's target type to a graph entity type for edge construction.
     * The {@link AuditLinkTargetType#FRAMEWORK} case is handled for backward
     * compatibility with persisted audit records authored before GC-I002; it maps to
     * {@code null} (external target, no graph node). New links must use
     * {@link AuditLinkTargetType#COMPLIANCE_FRAMEWORK_MAPPING}.
     */
    @SuppressWarnings("deprecation")
    private GraphEdge toAuditLinkEdge(
            UUID linkId,
            UUID auditId,
            AuditLinkTargetType targetType,
            UUID targetEntityId,
            String edgeType,
            Set<UUID> liveAssetIds,
            Set<UUID> liveRiskScenarioIds) {
        if (targetEntityId == null) {
            return null;
        }
        var targetEntityType =
                switch (targetType) {
                    case ASSET -> GraphEntityType.OPERATIONAL_ASSET;
                    case CONTROL -> GraphEntityType.CONTROL;
                    case RISK_SCENARIO -> GraphEntityType.RISK_SCENARIO;
                    case RISK_REGISTER_RECORD -> GraphEntityType.RISK_REGISTER_RECORD;
                    case EVIDENCE -> GraphEntityType.EVIDENCE_ARTIFACT;
                    case FINDING -> GraphEntityType.FINDING;
                    case COMPLIANCE_FRAMEWORK_MAPPING -> GraphEntityType.COMPLIANCE_FRAMEWORK_MAPPING;
                    case FRAMEWORK, EXTERNAL -> null;
                };
        if (targetEntityType == null) {
            return null;
        }
        boolean targetIsLive =
                switch (targetType) {
                    case ASSET -> liveAssetIds.contains(targetEntityId);
                    case RISK_SCENARIO -> liveRiskScenarioIds.contains(targetEntityId);
                    default -> true;
                };
        if (!targetIsLive) {
            return null;
        }
        return new GraphEdge(
                linkId.toString(),
                edgeType,
                GraphIds.nodeId(GraphEntityType.AUDIT, auditId),
                GraphIds.nodeId(targetEntityType, targetEntityId),
                GraphEntityType.AUDIT,
                targetEntityType,
                Map.of());
    }
}

package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RiskGraphProjectionContributor implements GraphProjectionContributor {

    private final RiskScenarioRepository riskScenarioRepository;
    private final RiskScenarioLinkRepository riskScenarioLinkRepository;
    private final RiskRegisterRecordRepository riskRegisterRecordRepository;
    private final RiskAssessmentResultRepository riskAssessmentResultRepository;
    private final TreatmentPlanRepository treatmentPlanRepository;
    private final MethodologyProfileRepository methodologyProfileRepository;

    public RiskGraphProjectionContributor(
            RiskScenarioRepository riskScenarioRepository,
            RiskScenarioLinkRepository riskScenarioLinkRepository,
            RiskRegisterRecordRepository riskRegisterRecordRepository,
            RiskAssessmentResultRepository riskAssessmentResultRepository,
            TreatmentPlanRepository treatmentPlanRepository,
            MethodologyProfileRepository methodologyProfileRepository) {
        this.riskScenarioRepository = riskScenarioRepository;
        this.riskScenarioLinkRepository = riskScenarioLinkRepository;
        this.riskRegisterRecordRepository = riskRegisterRecordRepository;
        this.riskAssessmentResultRepository = riskAssessmentResultRepository;
        this.treatmentPlanRepository = treatmentPlanRepository;
        this.methodologyProfileRepository = methodologyProfileRepository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        var scenarioNodes = riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(scenario -> scenario.getStatus() != RiskScenarioStatus.ARCHIVED)
                .map(scenario -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("title", scenario.getTitle());
                    properties.put("status", scenario.getStatus().name());
                    properties.put("threat", scenario.getThreat());
                    properties.put("method", scenario.getMethod());
                    properties.put("asset", scenario.getAsset());
                    properties.put("effect", scenario.getEffect());
                    properties.put("timeHorizon", scenario.getTimeHorizon());
                    properties.put("createdBy", scenario.getCreatedBy());
                    return new GraphNode(
                            GraphIds.nodeId(GraphEntityType.RISK_SCENARIO, scenario.getId()),
                            scenario.getId().toString(),
                            GraphEntityType.RISK_SCENARIO,
                            scenario.getProject().getIdentifier(),
                            scenario.getUid(),
                            scenario.getUid(),
                            properties);
                })
                .toList();

        var recordNodes =
                riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId).stream()
                        .map(record -> {
                            Map<String, Object> properties = new LinkedHashMap<>();
                            properties.put("title", record.getTitle());
                            properties.put("status", record.getStatus().name());
                            properties.put("owner", record.getOwner());
                            properties.put("reviewCadence", record.getReviewCadence());
                            properties.put("nextReviewAt", record.getNextReviewAt());
                            properties.put("categoryTags", record.getCategoryTags());
                            properties.put("assetScopeSummary", record.getAssetScopeSummary());
                            return new GraphNode(
                                    GraphIds.nodeId(GraphEntityType.RISK_REGISTER_RECORD, record.getId()),
                                    record.getId().toString(),
                                    GraphEntityType.RISK_REGISTER_RECORD,
                                    record.getProject().getIdentifier(),
                                    record.getUid(),
                                    record.getUid(),
                                    properties);
                        })
                        .toList();

        var assessmentNodes =
                riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId).stream()
                        .map(result -> {
                            Map<String, Object> properties = new LinkedHashMap<>();
                            properties.put(
                                    "title", result.getMethodologyProfile().getName());
                            properties.put("assessmentAt", result.getAssessmentAt());
                            properties.put("observationDate", result.getObservationDate());
                            properties.put("timeHorizon", result.getTimeHorizon());
                            properties.put("confidence", result.getConfidence());
                            properties.put(
                                    "approvalState", result.getApprovalState().name());
                            properties.put("analystIdentity", result.getAnalystIdentity());
                            return new GraphNode(
                                    GraphIds.nodeId(GraphEntityType.RISK_ASSESSMENT_RESULT, result.getId()),
                                    result.getId().toString(),
                                    GraphEntityType.RISK_ASSESSMENT_RESULT,
                                    result.getProject().getIdentifier(),
                                    null,
                                    result.getMethodologyProfile().getProfileKey(),
                                    properties);
                        })
                        .toList();

        var treatmentNodes = treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(plan -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("title", plan.getTitle());
                    properties.put("strategy", plan.getStrategy().name());
                    properties.put("status", plan.getStatus().name());
                    properties.put("owner", plan.getOwner());
                    properties.put("dueDate", plan.getDueDate());
                    return new GraphNode(
                            GraphIds.nodeId(GraphEntityType.TREATMENT_PLAN, plan.getId()),
                            plan.getId().toString(),
                            GraphEntityType.TREATMENT_PLAN,
                            plan.getProject().getIdentifier(),
                            plan.getUid(),
                            plan.getUid(),
                            properties);
                })
                .toList();

        var methodologyNodes = methodologyProfileRepository.findByProjectIdOrderByNameAscVersionDesc(projectId).stream()
                .map(profile -> {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("title", profile.getName());
                    properties.put("family", profile.getFamily().name());
                    properties.put("version", profile.getVersion());
                    properties.put("status", profile.getStatus().name());
                    return new GraphNode(
                            GraphIds.nodeId(GraphEntityType.METHODOLOGY_PROFILE, profile.getId()),
                            profile.getId().toString(),
                            GraphEntityType.METHODOLOGY_PROFILE,
                            profile.getProject().getIdentifier(),
                            profile.getProfileKey(),
                            profile.getProfileKey(),
                            properties);
                })
                .toList();

        return java.util.stream.Stream.of(scenarioNodes, recordNodes, assessmentNodes, treatmentNodes, methodologyNodes)
                .flatMap(List::stream)
                .toList();
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        var scenarioLinkEdges = riskScenarioLinkRepository.findByProjectId(projectId).stream()
                .map(link -> toScenarioLinkEdge(
                        link.getId(),
                        link.getRiskScenario().getId(),
                        link.getTargetType(),
                        link.getTargetEntityId(),
                        link.getLinkType().name()))
                .filter(java.util.Objects::nonNull)
                .toList();

        var recordEdges =
                riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId).stream()
                        .flatMap(record -> record.getRiskScenarios().stream()
                                .map(scenario -> new GraphEdge(
                                        "TRACKS:" + record.getId() + ":" + scenario.getId(),
                                        "TRACKS",
                                        GraphIds.nodeId(GraphEntityType.RISK_REGISTER_RECORD, record.getId()),
                                        GraphIds.nodeId(GraphEntityType.RISK_SCENARIO, scenario.getId()),
                                        GraphEntityType.RISK_REGISTER_RECORD,
                                        GraphEntityType.RISK_SCENARIO,
                                        Map.of())))
                        .toList();

        var assessmentEdges =
                riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId).stream()
                        .flatMap(result -> java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(
                                        new GraphEdge(
                                                "ASSESSES:" + result.getId() + ":"
                                                        + result.getRiskScenario()
                                                                .getId(),
                                                "ASSESSES",
                                                GraphIds.nodeId(GraphEntityType.RISK_ASSESSMENT_RESULT, result.getId()),
                                                GraphIds.nodeId(
                                                        GraphEntityType.RISK_SCENARIO,
                                                        result.getRiskScenario().getId()),
                                                GraphEntityType.RISK_ASSESSMENT_RESULT,
                                                GraphEntityType.RISK_SCENARIO,
                                                Map.of()),
                                        new GraphEdge(
                                                "USES_METHOD:" + result.getId() + ":"
                                                        + result.getMethodologyProfile()
                                                                .getId(),
                                                "USES_METHOD",
                                                GraphIds.nodeId(GraphEntityType.RISK_ASSESSMENT_RESULT, result.getId()),
                                                GraphIds.nodeId(
                                                        GraphEntityType.METHODOLOGY_PROFILE,
                                                        result.getMethodologyProfile()
                                                                .getId()),
                                                GraphEntityType.RISK_ASSESSMENT_RESULT,
                                                GraphEntityType.METHODOLOGY_PROFILE,
                                                Map.of())),
                                result.getObservations().stream()
                                        .map(observation -> new GraphEdge(
                                                "USED_OBSERVATION:" + result.getId() + ":" + observation.getId(),
                                                "USED_OBSERVATION",
                                                GraphIds.nodeId(GraphEntityType.RISK_ASSESSMENT_RESULT, result.getId()),
                                                GraphIds.nodeId(GraphEntityType.OBSERVATION, observation.getId()),
                                                GraphEntityType.RISK_ASSESSMENT_RESULT,
                                                GraphEntityType.OBSERVATION,
                                                Map.of()))))
                        .toList();

        var treatmentEdges = treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(plan -> new GraphEdge(
                        "TREATS:" + plan.getId() + ":"
                                + plan.getRiskRegisterRecord().getId(),
                        "TREATS",
                        GraphIds.nodeId(GraphEntityType.TREATMENT_PLAN, plan.getId()),
                        GraphIds.nodeId(
                                GraphEntityType.RISK_REGISTER_RECORD,
                                plan.getRiskRegisterRecord().getId()),
                        GraphEntityType.TREATMENT_PLAN,
                        GraphEntityType.RISK_REGISTER_RECORD,
                        Map.of()))
                .toList();

        return java.util.stream.Stream.of(scenarioLinkEdges, recordEdges, assessmentEdges, treatmentEdges)
                .flatMap(List::stream)
                .toList();
    }

    private GraphEdge toScenarioLinkEdge(
            UUID linkId, UUID scenarioId, RiskScenarioLinkTargetType targetType, UUID targetEntityId, String edgeType) {
        if (targetEntityId == null) {
            return null;
        }
        var targetEntityType =
                switch (targetType) {
                    case OBSERVATION -> GraphEntityType.OBSERVATION;
                    case ASSET -> GraphEntityType.OPERATIONAL_ASSET;
                    case REQUIREMENT -> GraphEntityType.REQUIREMENT;
                    case RISK_REGISTER_RECORD -> GraphEntityType.RISK_REGISTER_RECORD;
                    case RISK_ASSESSMENT_RESULT -> GraphEntityType.RISK_ASSESSMENT_RESULT;
                    case TREATMENT_PLAN -> GraphEntityType.TREATMENT_PLAN;
                    case METHODOLOGY_PROFILE -> GraphEntityType.METHODOLOGY_PROFILE;
                    case CONTROL -> GraphEntityType.CONTROL;
                    case THREAT_MODEL -> GraphEntityType.THREAT_MODEL;
                    case FINDING -> GraphEntityType.FINDING;
                    case AUDIT_RECORD -> GraphEntityType.AUDIT;
                        // Always emits an edge to the EVIDENCE_ARTIFACT node.
                        // EvidenceArtifactGraphProjectionContributor projects every
                        // evidence artifact (current and superseded) so this edge
                        // never dangles.
                    case EVIDENCE -> GraphEntityType.EVIDENCE_ARTIFACT;
                    case VULNERABILITY, EXTERNAL -> null;
                };
        if (targetEntityType == null) {
            return null;
        }
        return new GraphEdge(
                linkId.toString(),
                edgeType,
                GraphIds.nodeId(GraphEntityType.RISK_SCENARIO, scenarioId),
                GraphIds.nodeId(targetEntityType, targetEntityId),
                GraphEntityType.RISK_SCENARIO,
                targetEntityType,
                Map.of());
    }
}

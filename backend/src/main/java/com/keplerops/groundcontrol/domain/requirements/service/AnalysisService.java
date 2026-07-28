package com.keplerops.groundcontrol.domain.requirements.service;

import static com.keplerops.groundcontrol.domain.requirements.service.AnalysisServiceSupport.topoSortWave;

import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.graph.model.GraphProjection;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AnalysisService {

    private static final List<RelationType> DAG_TYPES =
            List.of(RelationType.PARENT, RelationType.DEPENDS_ON, RelationType.REFINES);

    private static final Set<Status> SATISFIED_STATUSES = Set.of(Status.ACTIVE, Status.DEPRECATED, Status.ARCHIVED);

    private final RequirementRepository requirementRepository;
    private final RequirementRelationRepository relationRepository;
    private final TraceabilityLinkRepository traceabilityLinkRepository;
    private final AuditService auditService;

    public AnalysisService(
            RequirementRepository requirementRepository,
            RequirementRelationRepository relationRepository,
            TraceabilityLinkRepository traceabilityLinkRepository,
            AuditService auditService) {
        this.requirementRepository = requirementRepository;
        this.relationRepository = relationRepository;
        this.traceabilityLinkRepository = traceabilityLinkRepository;
        this.auditService = auditService;
    }

    public List<CycleResult> detectCycles(UUID projectId) {
        List<RequirementRelation> relations =
                relationRepository.findActiveByProjectAndRelationTypeIn(projectId, DAG_TYPES);

        Map<UUID, List<UUID>> adjacencyList = new HashMap<>();
        Map<UUID, String> idToUid = new HashMap<>();
        Map<UUID, Map<UUID, RelationType>> edgeTypes = new HashMap<>();

        for (RequirementRelation rel : relations) {
            UUID sourceId = rel.getSource().getId();
            UUID targetId = rel.getTarget().getId();
            idToUid.put(sourceId, rel.getSource().getUid());
            idToUid.put(targetId, rel.getTarget().getUid());
            adjacencyList.computeIfAbsent(sourceId, k -> new ArrayList<>()).add(targetId);
            adjacencyList.putIfAbsent(targetId, new ArrayList<>());
            edgeTypes.computeIfAbsent(sourceId, k -> new HashMap<>()).put(targetId, rel.getRelationType());
        }

        List<List<UUID>> cycles = GraphAlgorithms.findCycles(adjacencyList);

        return cycles.stream()
                .map(cycle -> {
                    List<String> members = cycle.stream().map(idToUid::get).toList();
                    List<CycleEdge> edges = new ArrayList<>();
                    for (int i = 0; i < cycle.size() - 1; i++) {
                        UUID src = cycle.get(i);
                        UUID tgt = cycle.get(i + 1);
                        RelationType type =
                                edgeTypes.getOrDefault(src, Map.of()).get(tgt);
                        edges.add(new CycleEdge(idToUid.get(src), idToUid.get(tgt), type));
                    }
                    return new CycleResult(members, edges);
                })
                .toList();
    }

    public List<Requirement> findOrphans(UUID projectId) {
        return requirementRepository.findOrphansByProjectId(projectId);
    }

    public List<Requirement> findCoverageGaps(UUID projectId, LinkType linkType) {
        return requirementRepository.findCoverageGapsByProjectIdAndLinkType(projectId, linkType);
    }

    public Set<Requirement> impactAnalysis(UUID requirementId) {
        Requirement seed = requirementRepository
                .findById(requirementId)
                .orElseThrow(() -> new NotFoundException("Requirement not found: " + requirementId));

        UUID projectId = seed.getProject().getId();
        List<RequirementRelation> relations =
                relationRepository.findActiveByProjectAndRelationTypeIn(projectId, DAG_TYPES);

        // Build reverse adjacency list: target -> list of sources (downstream = those that depend on target)
        Map<UUID, List<UUID>> reverseAdj = new HashMap<>();
        for (RequirementRelation rel : relations) {
            UUID sourceId = rel.getSource().getId();
            UUID targetId = rel.getTarget().getId();
            reverseAdj.computeIfAbsent(targetId, k -> new ArrayList<>()).add(sourceId);
        }

        Set<UUID> reachableIds = GraphAlgorithms.findReachable(requirementId, reverseAdj);

        List<Requirement> fetched = requirementRepository.findAllById(reachableIds);
        if (fetched.size() != reachableIds.size()) {
            Set<UUID> foundIds = fetched.stream().map(Requirement::getId).collect(Collectors.toSet());
            UUID missingId = reachableIds.stream()
                    .filter(id -> !foundIds.contains(id))
                    .findFirst()
                    .orElseThrow();
            throw new NotFoundException("Requirement not found: " + missingId);
        }
        return new HashSet<>(fetched);
    }

    public List<ConsistencyViolation> detectConsistencyViolations(UUID projectId) {
        List<RequirementRelation> allRelations = relationRepository.findActiveWithSourceAndTargetByProjectId(projectId);
        List<ConsistencyViolation> violations = new ArrayList<>();

        for (RequirementRelation rel : allRelations) {
            Status sourceStatus = rel.getSource().getStatus();
            Status targetStatus = rel.getTarget().getStatus();
            boolean bothActive = sourceStatus == Status.ACTIVE && targetStatus == Status.ACTIVE;

            if (bothActive && rel.getRelationType() == RelationType.CONFLICTS_WITH) {
                violations.add(new ConsistencyViolation(rel, "ACTIVE_CONFLICT"));
            } else if (bothActive && rel.getRelationType() == RelationType.SUPERSEDES) {
                violations.add(new ConsistencyViolation(rel, "ACTIVE_SUPERSEDES"));
            }
        }

        return violations;
    }

    public CompletenessResult analyzeCompleteness(UUID projectId) {
        List<Requirement> allRequirements = requirementRepository.findByProjectIdAndArchivedAtIsNull(projectId);

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        List<CompletenessIssue> issues = new ArrayList<>();

        for (Requirement req : allRequirements) {
            String status = req.getStatus().name();
            byStatus.merge(status, 1, Integer::sum);

            if (req.getTitle() == null || req.getTitle().isBlank()) {
                issues.add(new CompletenessIssue(req.getUid(), "missing title"));
            }
            if (req.getStatement() == null || req.getStatement().isBlank()) {
                issues.add(new CompletenessIssue(req.getUid(), "missing statement"));
            }
        }

        return new CompletenessResult(allRequirements.size(), byStatus, issues);
    }

    public List<RequirementRelation> crossWaveValidation(UUID projectId) {
        List<RequirementRelation> allRelations = relationRepository.findActiveWithSourceAndTargetByProjectId(projectId);
        List<RequirementRelation> violations = new ArrayList<>();

        for (RequirementRelation rel : allRelations) {
            Integer sourceWave = rel.getSource().getWave();
            Integer targetWave = rel.getTarget().getWave();
            if (sourceWave != null && targetWave != null && sourceWave < targetWave) {
                violations.add(rel);
            }
        }

        return violations;
    }

    public WorkOrderResult getWorkOrder(UUID projectId) {
        List<Requirement> allRequirements = requirementRepository.findByProjectIdAndArchivedAtIsNull(projectId);
        List<RequirementRelation> relations =
                relationRepository.findActiveByProjectAndRelationTypeIn(projectId, DAG_TYPES);

        Map<UUID, Requirement> reqById = indexRequirementsById(allRequirements);
        Map<UUID, List<UUID>> dependsOn = buildDependencyMap(relations, reqById);
        Map<UUID, BlockingStatus> blockingStatusMap = new HashMap<>();
        Map<UUID, List<String>> blockedByMap = new HashMap<>();
        computeBlockingStatuses(allRequirements, dependsOn, reqById, blockingStatusMap, blockedByMap);

        Map<Integer, List<Requirement>> byWave = groupByWave(allRequirements);
        return buildWorkOrderResult(allRequirements, byWave, dependsOn, reqById, blockingStatusMap, blockedByMap);
    }

    private Map<UUID, Requirement> indexRequirementsById(List<Requirement> requirements) {
        Map<UUID, Requirement> reqById = new HashMap<>();
        for (Requirement req : requirements) {
            reqById.put(req.getId(), req);
        }
        return reqById;
    }

    private Map<UUID, List<UUID>> buildDependencyMap(
            List<RequirementRelation> relations, Map<UUID, Requirement> reqById) {
        Map<UUID, List<UUID>> dependsOn = new HashMap<>();
        for (RequirementRelation rel : relations) {
            UUID sourceId = rel.getSource().getId();
            UUID targetId = rel.getTarget().getId();
            if (reqById.containsKey(sourceId) && reqById.containsKey(targetId)) {
                dependsOn.computeIfAbsent(sourceId, k -> new ArrayList<>()).add(targetId);
            }
        }
        return dependsOn;
    }

    private void computeBlockingStatuses(
            List<Requirement> allRequirements,
            Map<UUID, List<UUID>> dependsOn,
            Map<UUID, Requirement> reqById,
            Map<UUID, BlockingStatus> blockingStatusMap,
            Map<UUID, List<String>> blockedByMap) {
        for (Requirement req : allRequirements) {
            UUID id = req.getId();
            List<UUID> deps = dependsOn.getOrDefault(id, List.of());
            if (deps.isEmpty()) {
                blockingStatusMap.put(id, BlockingStatus.UNCONSTRAINED);
                blockedByMap.put(id, List.of());
            } else {
                List<String> blockers = findBlockers(deps, reqById);
                blockingStatusMap.put(id, blockers.isEmpty() ? BlockingStatus.UNBLOCKED : BlockingStatus.BLOCKED);
                blockedByMap.put(id, blockers);
            }
        }
    }

    private List<String> findBlockers(List<UUID> deps, Map<UUID, Requirement> reqById) {
        List<String> blockers = new ArrayList<>();
        for (UUID depId : deps) {
            Requirement dep = reqById.get(depId);
            if (dep != null && !SATISFIED_STATUSES.contains(dep.getStatus())) {
                blockers.add(dep.getUid());
            }
        }
        return blockers;
    }

    private Map<Integer, List<Requirement>> groupByWave(List<Requirement> allRequirements) {
        Map<Integer, List<Requirement>> byWave = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
        for (Requirement req : allRequirements) {
            byWave.computeIfAbsent(req.getWave(), k -> new ArrayList<>()).add(req);
        }
        return byWave;
    }

    private WorkOrderResult buildWorkOrderResult(
            List<Requirement> allRequirements,
            Map<Integer, List<Requirement>> byWave,
            Map<UUID, List<UUID>> dependsOn,
            Map<UUID, Requirement> reqById,
            Map<UUID, BlockingStatus> blockingStatusMap,
            Map<UUID, List<String>> blockedByMap) {
        int globalOrder = 0;
        int totalUnblocked = 0;
        int totalBlocked = 0;
        int totalUnconstrained = 0;
        List<WorkOrderWave> waves = new ArrayList<>();

        for (Map.Entry<Integer, List<Requirement>> entry : byWave.entrySet()) {
            Integer wave = entry.getKey();
            List<Requirement> waveReqs = entry.getValue();
            List<UUID> sorted = topoSortWave(waveReqs, dependsOn);
            WorkOrderWaveAccumulator acc =
                    buildWaveItems(sorted, reqById, blockingStatusMap, blockedByMap, globalOrder);
            globalOrder = acc.nextGlobalOrder;
            totalUnblocked += acc.unblocked;
            totalBlocked += acc.blocked;
            totalUnconstrained += acc.unconstrained;
            waves.add(
                    new WorkOrderWave(wave, waveReqs.size(), acc.unblocked, acc.blocked, acc.unconstrained, acc.items));
        }

        return new WorkOrderResult(allRequirements.size(), totalUnblocked, totalBlocked, totalUnconstrained, waves);
    }

    private WorkOrderWaveAccumulator buildWaveItems(
            List<UUID> sorted,
            Map<UUID, Requirement> reqById,
            Map<UUID, BlockingStatus> blockingStatusMap,
            Map<UUID, List<String>> blockedByMap,
            int globalOrder) {
        int waveUnblocked = 0;
        int waveBlocked = 0;
        int waveUnconstrained = 0;
        List<WorkOrderItem> items = new ArrayList<>();

        for (UUID id : sorted) {
            Requirement req = reqById.get(id);
            BlockingStatus bs = blockingStatusMap.get(id);
            switch (bs) {
                case UNBLOCKED -> waveUnblocked++;
                case BLOCKED -> waveBlocked++;
                case UNCONSTRAINED -> waveUnconstrained++;
                default -> throw new IllegalStateException("Unexpected blocking status: " + bs);
            }
            items.add(new WorkOrderItem(
                    id,
                    req.getUid(),
                    req.getTitle(),
                    req.getStatus().name(),
                    req.getPriority().name(),
                    req.getWave(),
                    globalOrder++,
                    bs,
                    blockedByMap.getOrDefault(id, List.of())));
        }

        return new WorkOrderWaveAccumulator(items, waveUnblocked, waveBlocked, waveUnconstrained, globalOrder);
    }

    private record WorkOrderWaveAccumulator(
            List<WorkOrderItem> items, int unblocked, int blocked, int unconstrained, int nextGlobalOrder) {}

    public DashboardStats getDashboardStats(UUID projectId) {
        List<Requirement> allRequirements = requirementRepository.findByProjectIdAndArchivedAtIsNull(projectId);

        // byStatus — single pass
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (Requirement req : allRequirements) {
            byStatus.merge(req.getStatus().name(), 1, Integer::sum);
        }

        // byWave — group by wave, count by status per group
        // TreeMap with nulls-first comparator so null waves sort first
        Map<Integer, List<Requirement>> byWaveGroup = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        for (Requirement req : allRequirements) {
            byWaveGroup.computeIfAbsent(req.getWave(), k -> new ArrayList<>()).add(req);
        }
        List<WaveStats> byWave = byWaveGroup.entrySet().stream()
                .map(entry -> {
                    Map<String, Integer> waveByStatus = new LinkedHashMap<>();
                    for (Requirement req : entry.getValue()) {
                        waveByStatus.merge(req.getStatus().name(), 1, Integer::sum);
                    }
                    return new WaveStats(entry.getKey(), entry.getValue().size(), waveByStatus);
                })
                .toList();

        // coverageByLinkType — one set-based query per LinkType, no per-requirement calls
        int total = allRequirements.size();
        Set<UUID> reqIds = allRequirements.stream().map(Requirement::getId).collect(Collectors.toSet());
        Map<String, CoverageStats> coverageByLinkType = new LinkedHashMap<>();
        for (LinkType linkType : LinkType.values()) {
            int covered = 0;
            if (!reqIds.isEmpty()) {
                covered = traceabilityLinkRepository
                        .findRequirementIdsWithLinkType(reqIds, linkType)
                        .size();
            }
            double percentage = total > 0 ? Math.round(covered * 1000.0 / total) / 10.0 : 0.0;
            coverageByLinkType.put(linkType.name(), new CoverageStats(total, covered, percentage));
        }

        // recentChanges — delegate to AuditService
        List<RecentChange> recentChanges = auditService.getRecentRequirementChanges(reqIds, 10);

        return new DashboardStats(total, byStatus, byWave, coverageByLinkType, recentChanges);
    }

    public List<RequirementExportRecord> getRequirementsExportData(UUID projectId) {
        List<Requirement> requirements = requirementRepository.findByProjectIdAndArchivedAtIsNull(projectId);
        if (requirements.isEmpty()) {
            return List.of();
        }
        Set<UUID> reqIds = requirements.stream().map(Requirement::getId).collect(Collectors.toSet());
        List<TraceabilityLink> allLinks = traceabilityLinkRepository.findByRequirementIdIn(reqIds);
        Map<UUID, List<TraceabilityLink>> linksByReqId = allLinks.stream()
                .collect(Collectors.groupingBy(link -> link.getRequirement().getId()));
        return requirements.stream()
                .map(req -> new RequirementExportRecord(req, linksByReqId.getOrDefault(req.getId(), List.of())))
                .toList();
    }

    public GraphProjection extractSubgraph(UUID projectId, List<String> rootUids) {
        List<Requirement> allRequirements = requirementRepository.findByProjectIdAndArchivedAtIsNull(projectId);
        List<RequirementRelation> allRelations = relationRepository.findActiveWithSourceAndTargetByProjectId(projectId);

        // Resolve root UUIDs from UIDs
        Map<String, UUID> uidToId = new HashMap<>();
        for (Requirement req : allRequirements) {
            uidToId.put(req.getUid(), req.getId());
        }

        Set<UUID> roots = new HashSet<>();
        for (String uid : rootUids) {
            UUID id = uidToId.get(uid);
            if (id == null) {
                throw new NotFoundException("Requirement not found: " + uid);
            }
            roots.add(id);
        }

        // Build bidirectional adjacency list (all relation types)
        Map<UUID, List<UUID>> adjacencyList = new HashMap<>();
        for (RequirementRelation rel : allRelations) {
            UUID sourceId = rel.getSource().getId();
            UUID targetId = rel.getTarget().getId();
            adjacencyList.computeIfAbsent(sourceId, k -> new ArrayList<>()).add(targetId);
            adjacencyList.computeIfAbsent(targetId, k -> new ArrayList<>()).add(sourceId);
        }

        Set<UUID> reachableIds = GraphAlgorithms.findReachableFromMultiple(roots, adjacencyList);

        List<Requirement> subgraphRequirements = allRequirements.stream()
                .filter(req -> reachableIds.contains(req.getId()))
                .toList();

        List<RequirementRelation> subgraphRelations = allRelations.stream()
                .filter(rel -> reachableIds.contains(rel.getSource().getId())
                        && reachableIds.contains(rel.getTarget().getId()))
                .toList();

        return toGraphProjection(subgraphRequirements, subgraphRelations);
    }

    public GraphProjection getGraphVisualization(UUID projectId) {
        List<Requirement> requirements = requirementRepository.findByProjectIdAndArchivedAtIsNull(projectId);
        List<RequirementRelation> relations = relationRepository.findActiveWithSourceAndTargetByProjectId(projectId);
        return toGraphProjection(requirements, relations);
    }

    private GraphProjection toGraphProjection(List<Requirement> requirements, List<RequirementRelation> relations) {
        return new GraphProjection(
                requirements.stream().map(this::toGraphNode).toList(),
                relations.stream().map(this::toGraphEdge).toList());
    }

    private GraphNode toGraphNode(Requirement requirement) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", requirement.getTitle());
        properties.put("statement", requirement.getStatement());
        properties.put("priority", requirement.getPriority().name());
        properties.put("status", requirement.getStatus().name());
        properties.put("requirementType", requirement.getRequirementType().name());
        properties.put("wave", requirement.getWave());
        return new GraphNode(
                requirement.getId().toString(),
                requirement.getId().toString(),
                GraphEntityType.REQUIREMENT,
                requirement.getProject().getIdentifier(),
                requirement.getUid(),
                requirement.getUid(),
                properties);
    }

    private GraphEdge toGraphEdge(RequirementRelation relation) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sourceUid", relation.getSource().getUid());
        properties.put("targetUid", relation.getTarget().getUid());
        properties.put("createdAt", relation.getCreatedAt());
        return new GraphEdge(
                relation.getId().toString(),
                relation.getRelationType().name(),
                relation.getSource().getId().toString(),
                relation.getTarget().getId().toString(),
                GraphEntityType.REQUIREMENT,
                GraphEntityType.REQUIREMENT,
                properties);
    }
}

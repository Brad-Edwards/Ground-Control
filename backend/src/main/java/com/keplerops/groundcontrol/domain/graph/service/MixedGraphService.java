package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.GraphTraversalLimits;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.graph.model.GraphProjection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
// Repeatable-read so a getVisualization call that resolves the active AGE snapshot and then issues
// its node and edge queries observes one consistent snapshot even if a materialization commits a
// new snapshot mid-read (ADR-062). Harmless for the AGE-disabled in-memory fallback path.
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MixedGraphService {

    private final MixedGraphClient mixedGraphClient;

    public MixedGraphService(MixedGraphClient mixedGraphClient) {
        this.mixedGraphClient = mixedGraphClient;
    }

    public GraphProjection getVisualization(UUID projectId, List<String> entityTypeNames) {
        Set<GraphEntityType> entityTypes = parseEntityTypes(entityTypeNames);
        // Client applies the filter and the projection cap; service-side enforceProjectionCap is
        // defence-in-depth in case a future client returns more than the contract permits.
        return enforceProjectionCap(mixedGraphClient.getVisualization(projectId, entityTypes));
    }

    public GraphProjection extractSubgraph(
            UUID projectId, List<String> rootNodeIds, int maxDepth, List<String> entityTypeNames) {
        validateDepth(maxDepth);
        validateRootNodeIds(rootNodeIds);
        return neighborhoodProjection(projectId, rootNodeIds, maxDepth, entityTypeNames);
    }

    public GraphProjection traverse(
            UUID projectId, List<String> rootNodeIds, int maxDepth, List<String> entityTypeNames) {
        // Same operational shape as extractSubgraph today; the public surface is intentionally
        // distinct because the two endpoints document different intents (subgraph slice vs.
        // adjacency traversal) and may diverge later (e.g., directional vs. undirected expansion).
        return extractSubgraph(projectId, rootNodeIds, maxDepth, entityTypeNames);
    }

    public List<GraphPathResult> findPaths(
            UUID projectId, String sourceNodeId, String targetNodeId, int maxDepth, List<String> entityTypeNames) {
        validateDepth(maxDepth);
        validateNodeIdentifier(sourceNodeId, "sourceNodeId");
        validateNodeIdentifier(targetNodeId, "targetNodeId");
        Set<GraphEntityType> entityTypes = parseEntityTypes(entityTypeNames);
        var projection = enforceProjectionCap(mixedGraphClient.getVisualization(projectId, entityTypes));
        var nodesById = projection.nodes().stream().collect(Collectors.toMap(GraphNode::id, node -> node));
        if (!nodesById.containsKey(sourceNodeId)) {
            throw new NotFoundException("Graph node not found: " + sourceNodeId);
        }
        if (!nodesById.containsKey(targetNodeId)) {
            throw new NotFoundException("Graph node not found: " + targetNodeId);
        }
        var adjacency = buildAdjacency(projection.edges());
        var edgeLookup = buildEdgeLookup(projection.edges());

        Deque<List<String>> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(List.of(sourceNodeId));
        visited.add(sourceNodeId);

        while (!queue.isEmpty()) {
            var path = queue.removeFirst();
            var current = path.getLast();
            int edgeCount = path.size() - 1;
            if (current.equals(targetNodeId)) {
                List<String> edgeTypes = new ArrayList<>();
                for (int i = 0; i < path.size() - 1; i++) {
                    var edge = edgeLookup.get(undirectedKey(path.get(i), path.get(i + 1)));
                    edgeTypes.add(edge != null ? edge.edgeType() : "RELATED");
                }
                return List.of(new GraphPathResult(path, edgeTypes));
            }
            // Stop expanding as soon as the current path already has maxDepth edges — extending
            // it would produce a path with more edges than the caller asked for.
            if (edgeCount >= maxDepth) {
                continue;
            }
            for (String neighbor : adjacency.getOrDefault(current, Set.of())) {
                if (visited.add(neighbor)) {
                    List<String> nextPath = new ArrayList<>(path);
                    nextPath.add(neighbor);
                    queue.addLast(nextPath);
                }
            }
        }
        return List.of();
    }

    private GraphProjection neighborhoodProjection(
            UUID projectId, List<String> rootNodeIds, int maxDepth, List<String> entityTypeNames) {
        Set<GraphEntityType> entityTypes = parseEntityTypes(entityTypeNames);
        var projection = enforceProjectionCap(mixedGraphClient.getVisualization(projectId, entityTypes));
        var nodesById = projection.nodes().stream().collect(Collectors.toMap(GraphNode::id, node -> node));
        for (String rootNodeId : rootNodeIds) {
            if (!nodesById.containsKey(rootNodeId)) {
                throw new NotFoundException("Graph node not found: " + rootNodeId);
            }
        }

        var adjacency = buildAdjacency(projection.edges());
        var visited = new LinkedHashSet<String>();
        var queue = new ArrayDeque<Map.Entry<String, Integer>>();
        for (String rootNodeId : rootNodeIds) {
            visited.add(rootNodeId);
            queue.add(Map.entry(rootNodeId, 0));
        }

        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (current.getValue() >= maxDepth) {
                continue;
            }
            for (String neighbor : adjacency.getOrDefault(current.getKey(), Set.of())) {
                if (visited.add(neighbor)) {
                    queue.addLast(Map.entry(neighbor, current.getValue() + 1));
                }
            }
        }

        var nodes = projection.nodes().stream()
                .filter(node -> visited.contains(node.id()))
                .toList();
        var edges = projection.edges().stream()
                .filter(edge -> visited.contains(edge.sourceId()) && visited.contains(edge.targetId()))
                .toList();
        return new GraphProjection(nodes, edges);
    }

    private Set<GraphEntityType> parseEntityTypes(List<String> entityTypeNames) {
        if (entityTypeNames == null || entityTypeNames.isEmpty()) {
            return Set.of();
        }
        if (entityTypeNames.size() > GraphTraversalLimits.MAX_ENTITY_TYPE_FILTER) {
            throw new DomainValidationException("entityTypes size " + entityTypeNames.size() + " exceeds maximum "
                    + GraphTraversalLimits.MAX_ENTITY_TYPE_FILTER);
        }
        Set<GraphEntityType> parsed = new LinkedHashSet<>();
        for (String raw : entityTypeNames) {
            if (raw == null) {
                throw new DomainValidationException("entityTypes contains a null value");
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                // Symmetric with the DTO @NotBlank constraint: an internal caller passing a blank
                // entity-type value must not silently fall through to "no filter" — that would be
                // the validation-bypass surface this service-level check exists to close.
                throw new DomainValidationException("entityTypes contains a blank value");
            }
            if (trimmed.length() > GraphTraversalLimits.MAX_NODE_IDENTIFIER_LENGTH) {
                throw new DomainValidationException(
                        "entityTypes value exceeds maximum length " + GraphTraversalLimits.MAX_NODE_IDENTIFIER_LENGTH);
            }
            try {
                parsed.add(GraphEntityType.valueOf(trimmed));
            } catch (IllegalArgumentException ex) {
                // Map AGE-native IllegalArgumentException to a stable validation envelope; otherwise
                // the request would fall through GlobalExceptionHandler.handleGeneric as a 500 and
                // leak the enum class name in logs.
                throw new DomainValidationException("Unknown entityType: " + trimmed);
            }
        }
        return parsed;
    }

    private static void validateDepth(int maxDepth) {
        if (maxDepth < 1 || maxDepth > GraphTraversalLimits.MAX_DEPTH) {
            throw new DomainValidationException("Invalid graph traversal depth: " + maxDepth + " (must be 1.."
                    + GraphTraversalLimits.MAX_DEPTH + ")");
        }
    }

    private static void validateNodeIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(fieldName + " must not be blank");
        }
        if (value.length() > GraphTraversalLimits.MAX_NODE_IDENTIFIER_LENGTH) {
            throw new DomainValidationException(
                    fieldName + " exceeds maximum length " + GraphTraversalLimits.MAX_NODE_IDENTIFIER_LENGTH);
        }
    }

    private static void validateRootNodeIds(List<String> rootNodeIds) {
        if (rootNodeIds == null || rootNodeIds.isEmpty()) {
            throw new DomainValidationException("rootNodeIds must not be empty");
        }
        if (rootNodeIds.size() > GraphTraversalLimits.MAX_ROOT_NODES) {
            throw new DomainValidationException("rootNodeIds size " + rootNodeIds.size() + " exceeds maximum "
                    + GraphTraversalLimits.MAX_ROOT_NODES);
        }
        for (String id : rootNodeIds) {
            if (id == null || id.isBlank()) {
                throw new DomainValidationException("rootNodeIds contains a blank value");
            }
            if (id.length() > GraphTraversalLimits.MAX_NODE_IDENTIFIER_LENGTH) {
                throw new DomainValidationException(
                        "rootNodeIds value exceeds maximum length " + GraphTraversalLimits.MAX_NODE_IDENTIFIER_LENGTH);
            }
        }
    }

    private static GraphProjection enforceProjectionCap(GraphProjection projection) {
        if (projection.nodes().size() > GraphTraversalLimits.MAX_PROJECTION_NODES) {
            throw new DomainValidationException(
                    "projection node count " + projection.nodes().size()
                            + " exceeds maximum " + GraphTraversalLimits.MAX_PROJECTION_NODES
                            + "; apply an entityTypes filter to narrow the result");
        }
        if (projection.edges().size() > GraphTraversalLimits.MAX_PROJECTION_EDGES) {
            throw new DomainValidationException(
                    "projection edge count " + projection.edges().size()
                            + " exceeds maximum " + GraphTraversalLimits.MAX_PROJECTION_EDGES
                            + "; apply an entityTypes filter to narrow the result");
        }
        return projection;
    }

    private Map<String, Set<String>> buildAdjacency(List<GraphEdge> edges) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (GraphEdge edge : edges) {
            adjacency
                    .computeIfAbsent(edge.sourceId(), ignored -> new LinkedHashSet<>())
                    .add(edge.targetId());
            adjacency
                    .computeIfAbsent(edge.targetId(), ignored -> new LinkedHashSet<>())
                    .add(edge.sourceId());
        }
        return adjacency;
    }

    private Map<String, GraphEdge> buildEdgeLookup(List<GraphEdge> edges) {
        Map<String, GraphEdge> lookup = new LinkedHashMap<>();
        for (GraphEdge edge : edges) {
            lookup.putIfAbsent(undirectedKey(edge.sourceId(), edge.targetId()), edge);
        }
        return lookup;
    }

    private String undirectedKey(String left, String right) {
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }
}

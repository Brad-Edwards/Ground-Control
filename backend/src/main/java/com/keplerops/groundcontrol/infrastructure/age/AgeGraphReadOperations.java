package com.keplerops.groundcontrol.infrastructure.age;

import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphService.MAX_FIND_PATHS_RESULTS;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphService.MAX_GRAPH_TRAVERSAL_DEPTH;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphService.parseAgtypeValue;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_PROJECT_IDENTIFIER;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_UID;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.bindAgtypeParams;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.buildCypherPathSql;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.buildCypherSql;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.encodeParams;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.enforceProjectionSizeCap;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.filterByEntityType;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.stringValue;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.toGraphEdge;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.toGraphNode;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.validateDepth;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.validateGraphName;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.validateUid;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.GraphTraversalLimits;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.graph.model.GraphProjection;
import com.keplerops.groundcontrol.domain.graph.service.GraphProjectionRegistryService;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.service.PathResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Traversal and visualization reads over the AGE graph.
 *
 * Split out of {@link AgeGraphService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class AgeGraphReadOperations {

    private final JdbcTemplate jdbcTemplate;
    private final AgeProperties ageProperties;
    private final GraphProjectionRegistryService graphProjectionRegistryService;
    private final ProjectRepository projectRepository;
    private final AgeGraphSnapshotRepository snapshotRepository;
    private final AgeGraphService service;

    AgeGraphReadOperations(
            JdbcTemplate jdbcTemplate,
            AgeProperties ageProperties,
            GraphProjectionRegistryService graphProjectionRegistryService,
            ProjectRepository projectRepository,
            AgeGraphSnapshotRepository snapshotRepository,
            AgeGraphService service) {
        this.jdbcTemplate = jdbcTemplate;
        this.ageProperties = ageProperties;
        this.graphProjectionRegistryService = graphProjectionRegistryService;
        this.projectRepository = projectRepository;
        this.snapshotRepository = snapshotRepository;
        this.service = service;
    }

    /**
     * Resolve the graph name a read should query: the active snapshot when one has been published,
     * otherwise the configured base graph.
     *
     * <p>The base-graph fallback is the upgrade/bootstrap compatibility path. {@code
     * age_graph_snapshot} starts empty, but an existing deployment can already have the configured
     * base graph populated by the pre-ADR-062 in-place materializer; without this fallback every
     * graph read would return empty after deploy until an operator re-materialized, violating the
     * "readers keep seeing the previous complete graph" guarantee. It is bounded: the fallback
     * applies ONLY while no snapshot row exists. Once the first snapshot is published the pointer
     * always wins, and the base graph is never read again. On a truly fresh database the base graph
     * is the empty graph created by V010, so the fallback reads empty — exactly the pre-snapshot
     * behavior. The name is allowlist-validated before any caller embeds it as a SQL literal.
     */
    private String resolveActiveGraph() {
        return validateGraphName(snapshotRepository.findActiveGraphName().orElseGet(ageProperties::graphName));
    }

    List<String> getAncestors(UUID projectId, String uid, int depth) {
        if (!ageProperties.enabled()) {
            return List.of();
        }
        validateUid(uid);
        validateDepth(depth);
        String graph = resolveActiveGraph();
        String projectIdentifier = getProjectIdentifier(projectId);
        service.setupSearchPath();

        // PARENT edges are materialized source→target and the domain convention is
        // child→parent (RequirementRelation(child, parent, PARENT)), so ancestors of n are
        // reachable by following OUTGOING PARENT edges from n. The depth bound is an int
        // already validated against MAX_GRAPH_TRAVERSAL_DEPTH; AGE 1.6 does not parameterize
        // variable-length-path bounds, but integer formatting is not injectable.
        String cypher = "MATCH (n:REQUIREMENT {uid: $uid, project_identifier: $project_identifier})"
                + "-[:PARENT*1.." + depth + "]->(a:REQUIREMENT {project_identifier: $project_identifier}) "
                + "RETURN a.uid";
        String params = encodeParams(Map.of(KEY_UID, uid, KEY_PROJECT_IDENTIFIER, projectIdentifier));

        return queryUids(graph, cypher, params);
    }

    List<String> getDescendants(UUID projectId, String uid, int depth) {
        if (!ageProperties.enabled()) {
            return List.of();
        }
        validateUid(uid);
        validateDepth(depth);
        String graph = resolveActiveGraph();
        String projectIdentifier = getProjectIdentifier(projectId);
        service.setupSearchPath();

        // Inverse of getAncestors: descendants of n are reachable by following INCOMING PARENT
        // edges to n.
        String cypher = "MATCH (n:REQUIREMENT {uid: $uid, project_identifier: $project_identifier})"
                + "<-[:PARENT*1.." + depth + "]-(d:REQUIREMENT {project_identifier: $project_identifier}) "
                + "RETURN d.uid";
        String params = encodeParams(Map.of(KEY_UID, uid, KEY_PROJECT_IDENTIFIER, projectIdentifier));

        return queryUids(graph, cypher, params);
    }

    List<PathResult> findPaths(UUID projectId, String sourceUid, String targetUid) {
        if (!ageProperties.enabled()) {
            return List.of();
        }
        validateUid(sourceUid);
        validateUid(targetUid);
        String graph = resolveActiveGraph();
        String projectIdentifier = getProjectIdentifier(projectId);
        service.setupSearchPath();

        // findPaths has no depth in its API contract, so we hard-cap variable-length traversal
        // at MAX_GRAPH_TRAVERSAL_DEPTH and apply a result LIMIT INSIDE the Cypher block to
        // bound the work AGE itself does (the outer SQL LIMIT only truncates rows after AGE
        // has materialized them, which doesn't bound expansion on a cyclic graph).
        //
        // Returning `nodes(path)` and `relationships(path)` directly — rather than through a
        // list comprehension like `[n IN nodes(path) | n.uid]` — works around an AGE 1.6 plan
        // error ("could not find properties for n") triggered when the planner mixes path
        // accessors with subsequent property lookups. We iterate the returned vertex/edge
        // arrays in Java and pull the UID/label from each element.
        String cypher = "MATCH path = (s:REQUIREMENT {uid: $source_uid, project_identifier: $project_identifier})"
                + "-[*1.." + MAX_GRAPH_TRAVERSAL_DEPTH
                + "]->(t:REQUIREMENT {uid: $target_uid, project_identifier: $project_identifier}) "
                + "RETURN nodes(path), relationships(path) LIMIT " + MAX_FIND_PATHS_RESULTS;
        String params = encodeParams(
                Map.of("source_uid", sourceUid, "target_uid", targetUid, KEY_PROJECT_IDENTIFIER, projectIdentifier));

        List<PathResult> paths = new ArrayList<>();
        jdbcTemplate.query(buildCypherPathSql(graph, cypher), bindAgtypeParams(params), (RowCallbackHandler) rs -> {
            List<String> nodeUids = service.extractPathNodeUids(rs.getString(1));
            List<String> edgeLabels = service.extractPathEdgeLabels(rs.getString(2));
            paths.add(new PathResult(nodeUids, edgeLabels));
        });
        return paths;
    }

    GraphProjection getVisualization(UUID projectId, Set<GraphEntityType> entityTypes) {
        Set<GraphEntityType> filter = entityTypes == null ? Set.of() : entityTypes;
        if (!ageProperties.enabled()) {
            // AGE-disabled fallback walks every contributor in memory; we cannot short-circuit the
            // contributor loop, so apply the entityTypes filter and size cap in memory. The
            // filter is applied BEFORE the cap, so a caller's narrowing actually matters: a tight
            // filter on a large project produces a small projection that passes the cap.
            GraphProjection full = graphProjectionRegistryService.buildProjectionForProject(projectId);
            GraphProjection filtered = filter.isEmpty() ? full : filterByEntityType(full, filter);
            return enforceProjectionSizeCap(filtered);
        }
        String graph = resolveActiveGraph();
        String projectIdentifier = getProjectIdentifier(projectId);
        service.setupSearchPath();

        // Push BOTH the entityTypes narrowing and the size cap into Cypher itself. The filter uses
        // a parameter-bound `IN` against the `entity_type` property (a string materialized at
        // executeCreateNode time, KEY_ENTITY_TYPE), so labels are NOT inlined into the query text
        // — the surrounding ADR-032 constraint is preserved. The LIMIT (MAX + 1) is enforced on
        // the FILTERED set, which means a caller narrowing the projection actually narrows what
        // the database materializes; the +1 row, if present, signals overflow at adapter level so
        // the bound is still enforced on database work even when no filter is supplied.
        int nodeLimitInclusive = GraphTraversalLimits.MAX_PROJECTION_NODES + 1;
        List<GraphNode> nodes = new ArrayList<>();
        Map<String, Object> nodeParamMap = new LinkedHashMap<>();
        nodeParamMap.put(KEY_PROJECT_IDENTIFIER, projectIdentifier);
        StringBuilder nodeCypher = new StringBuilder("MATCH (n {project_identifier: $project_identifier})");
        if (!filter.isEmpty()) {
            List<String> typeNames = filter.stream().map(GraphEntityType::name).toList();
            nodeParamMap.put("entity_types", typeNames);
            nodeCypher.append(" WHERE n.entity_type IN $entity_types");
        }
        nodeCypher.append(" RETURN properties(n) LIMIT ").append(nodeLimitInclusive);
        jdbcTemplate.query(
                buildCypherSql(graph, nodeCypher.toString()),
                bindAgtypeParams(encodeParams(nodeParamMap)),
                (RowCallbackHandler) rs -> nodes.add(toGraphNode(parseAgtypeMap(rs.getString(1)))));
        if (nodes.size() > GraphTraversalLimits.MAX_PROJECTION_NODES) {
            throw new DomainValidationException(
                    filter.isEmpty()
                            ? "projection node count exceeds maximum " + GraphTraversalLimits.MAX_PROJECTION_NODES
                                    + "; apply an entityTypes filter to narrow the result"
                            : "projection node count exceeds maximum " + GraphTraversalLimits.MAX_PROJECTION_NODES
                                    + " even with the supplied entityTypes filter; narrow the filter further");
        }

        // Edges must connect two nodes whose entity_type is in the filter set. We bind the filter
        // a second time as edge params; AGE evaluates `IN` against the bound list parameter, so
        // the cost of materialization stays bounded by the filter on both endpoints. The size cap
        // applies to the filtered edges.
        int edgeLimitInclusive = GraphTraversalLimits.MAX_PROJECTION_EDGES + 1;
        List<GraphEdge> edges = new ArrayList<>();
        Map<String, Object> edgeParamMap = new LinkedHashMap<>();
        edgeParamMap.put(KEY_PROJECT_IDENTIFIER, projectIdentifier);
        StringBuilder edgeCypher = new StringBuilder("MATCH (s {project_identifier: $project_identifier})"
                + "-[r]->(t {project_identifier: $project_identifier})");
        if (!filter.isEmpty()) {
            List<String> typeNames = filter.stream().map(GraphEntityType::name).toList();
            edgeParamMap.put("entity_types", typeNames);
            edgeCypher.append(" WHERE s.entity_type IN $entity_types AND t.entity_type IN $entity_types");
        }
        edgeCypher.append(" RETURN properties(r) LIMIT ").append(edgeLimitInclusive);
        jdbcTemplate.query(
                buildCypherSql(graph, edgeCypher.toString()),
                bindAgtypeParams(encodeParams(edgeParamMap)),
                (RowCallbackHandler) rs -> edges.add(toGraphEdge(parseAgtypeMap(rs.getString(1)))));
        if (edges.size() > GraphTraversalLimits.MAX_PROJECTION_EDGES) {
            throw new DomainValidationException(
                    filter.isEmpty()
                            ? "projection edge count exceeds maximum " + GraphTraversalLimits.MAX_PROJECTION_EDGES
                                    + "; apply an entityTypes filter to narrow the result"
                            : "projection edge count exceeds maximum " + GraphTraversalLimits.MAX_PROJECTION_EDGES
                                    + " even with the supplied entityTypes filter; narrow the filter further");
        }

        return new GraphProjection(nodes, edges);
    }

    private List<String> queryUids(String graph, String cypher, String params) {
        List<String> results = new ArrayList<>();
        jdbcTemplate.query(buildCypherSql(graph, cypher), bindAgtypeParams(params), (RowCallbackHandler)
                rs -> results.add(stringValue(parseAgtypeValue(rs.getString(1)))));
        return results;
    }

    private static Map<String, Object> parseAgtypeMap(String agtypeValue) {
        Object parsed = parseAgtypeValue(agtypeValue);
        if (parsed instanceof Map<?, ?> values) {
            Map<String, Object> map = new LinkedHashMap<>();
            values.forEach((key, value) -> map.put(String.valueOf(key), value));
            return map;
        }
        return Map.of();
    }

    private String getProjectIdentifier(UUID projectId) {
        return projectRepository
                .findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId))
                .getIdentifier();
    }
}

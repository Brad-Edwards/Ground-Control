package com.keplerops.groundcontrol.infrastructure.age;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.GraphTraversalLimits;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.graph.model.GraphProjection;
import com.keplerops.groundcontrol.domain.graph.service.GraphProjectionRegistryService;
import com.keplerops.groundcontrol.domain.graph.service.MixedGraphClient;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.service.GraphClient;
import com.keplerops.groundcontrol.domain.requirements.service.PathResult;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * AGE adapter. Owns Cypher/SQL construction for the {@code ag_catalog.cypher(...)} surface.
 *
 * <p>Per ADR-032, every user-controlled value reaches AGE through one of two paths:
 *
 * <ul>
 *   <li>Cypher parameters bound via the third argument of {@code cypher(graph, query, params)} —
 *       used for all string/JSON-shaped values (UIDs, project identifiers, free-form properties
 *       like requirement titles and statements).
 *   <li>Allowlist-validated identifiers — used only for tokens AGE cannot parameterize: graph
 *       names, node labels, relationship types, and Cypher property keys.
 * </ul>
 *
 * <p>String concatenation of user-supplied data into the SQL or Cypher text is not allowed. The
 * {@link AgeIdentifiers} graph-identifier allowlist and {@link #SAFE_PARAM_NAME} allowlist are
 * defense in depth; the primary mitigation is parameter binding.
 *
 * <p>Class-level {@link Transactional} pins every public AGE call to a single connection. AGE's
 * {@code LOAD 'age'} and {@code SET search_path} commands are connection-local, so without a
 * transaction those settings could land on one pooled connection and the subsequent
 * {@code ag_catalog.cypher(...)} call could land on another — failing with "function
 * ag_catalog.cypher does not exist" or returning empty results.
 *
 * <p>The transaction also runs at {@link Isolation#REPEATABLE_READ} (ADR-062). A read resolves the
 * active snapshot name and then issues its graph query/queries; under the default read-committed
 * isolation a snapshot publication committing between those statements could let a reader observe
 * one statement against the old snapshot and the next against the new. A repeatable-read snapshot
 * pins every statement of a read — and the multi-query projection capture during publication — to a
 * single consistent moment. Because publication is INSERT-only (it never updates a prior snapshot
 * row), repeatable-read raises no write-serialization conflict for concurrent publishers, which are
 * additionally serialized by an advisory lock.
 */
@Component
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class AgeGraphService implements GraphClient, MixedGraphClient {

    private static final Logger log = LoggerFactory.getLogger(AgeGraphService.class);
    private static final java.util.regex.Pattern SAFE_PARAM_NAME = java.util.regex.Pattern.compile("^[a-zA-Z_]\\w*$");

    // Implicit AGE property keys this adapter writes / reads. Centralized so
    // executeCreateNode/Edge, toGraphNode/Edge, getVisualization, and APPROVED_PROPERTY_KEYS
    // all reference the same string literal.
    private static final String KEY_ID = "id";
    private static final String KEY_DOMAIN_ID = "domain_id";
    private static final String KEY_ENTITY_TYPE = "entity_type";
    private static final String KEY_PROJECT_IDENTIFIER = "project_identifier";
    private static final String KEY_UID = "uid";
    private static final String KEY_LABEL = "label";
    private static final String KEY_EDGE_TYPE = "edge_type";
    private static final String KEY_SOURCE_ID = "source_id";
    private static final String KEY_TARGET_ID = "target_id";
    private static final String KEY_SOURCE_ENTITY_TYPE = "source_entity_type";
    private static final String KEY_TARGET_ENTITY_TYPE = "target_entity_type";

    private static final String AGTYPE_TAG_VERTEX = "::vertex";
    private static final String AGTYPE_TAG_EDGE = "::edge";
    private static final String AGTYPE_TAG_PATH = "::path";
    // Jackson mapper used for both the agtype params payload and for parsing AGE rows back. We
    // explicitly disable WRITE_DATES_AS_TIMESTAMPS so that Instant / LocalDate properties are
    // bound as ISO-8601 strings (matching the previous String.format-based path that called
    // instant.toString()). Without this, JavaTimeModule would default to Jackson's
    // big-decimal-seconds shape, and getVisualization() would silently emit numeric timestamps
    // when AGE is enabled while still emitting ISO strings when AGE is disabled — a
    // configuration-dependent API regression.
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    /**
     * Maximum allowed traversal depth for {@link #getAncestors}/{@link #getDescendants}/{@link
     * #findPaths}. AGE 1.6 cannot parameterize variable-length-path bounds, so we enforce a hard
     * cap before constructing the cypher; otherwise a caller could request {@code depth=10_000}
     * and trigger an unbounded graph expansion. Cap chosen to fit any realistic requirement
     * dependency tree while keeping latency bounded. Delegated to the canonical policy in
     * {@link GraphTraversalLimits} per ADR-032 so this adapter and {@code MixedGraphService}
     * cannot drift apart.
     */
    static final int MAX_GRAPH_TRAVERSAL_DEPTH = GraphTraversalLimits.MAX_DEPTH;

    /**
     * Result-row cap on {@link #findPaths}. Even with a depth bound, a dense or cyclic graph
     * can produce an exponential number of distinct paths between two requirements; this cap
     * keeps the response size bounded and the per-call latency predictable. Delegated to the
     * canonical policy in {@link GraphTraversalLimits}.
     */
    static final int MAX_FIND_PATHS_RESULTS = GraphTraversalLimits.MAX_PATH_RESULTS;

    /** Maximum allowed length for a UID arriving at the AGE adapter (matches the column width). */
    static final int MAX_UID_LENGTH = 50;

    /**
     * Approved AGE property keys. Per ADR-032, dynamic Cypher tokens (labels, relationship
     * names, property keys) must come from a fixed allowlist — not just satisfy a syntactic
     * pattern — so that a future {@link com.keplerops.groundcontrol.domain.graph.service
     * .GraphProjectionContributor} cannot silently grow the AGE schema by emitting unknown
     * property keys. The set covers both the implicit keys this adapter writes
     * ({@code id}, {@code domain_id}, {@code entity_type}, etc.) and every key currently
     * emitted by {@code *GraphProjectionContributor} implementations. New contributors must
     * add their keys here and ship a regression test; that's intentional friction.
     */
    public static final Set<String> APPROVED_PROPERTY_KEYS = Set.of(
            // Adapter-emitted (set in executeCreateNode / executeCreateEdge).
            KEY_ID,
            KEY_DOMAIN_ID,
            KEY_ENTITY_TYPE,
            KEY_PROJECT_IDENTIFIER,
            KEY_UID,
            KEY_LABEL,
            KEY_EDGE_TYPE,
            KEY_SOURCE_ID,
            KEY_TARGET_ID,
            KEY_SOURCE_ENTITY_TYPE,
            KEY_TARGET_ENTITY_TYPE,
            // Requirement projection.
            "title",
            "statement",
            "priority",
            "status",
            "requirementType",
            "wave",
            "archivedAt",
            "createdAt",
            "createdBy",
            "sourceUid",
            "targetUid",
            "artifactIdentifier",
            // Asset projection.
            "name",
            "description",
            "assetType",
            "assetScopeSummary",
            "owner",
            // GC-M012 asset ownership / criticality / scope metadata. Every key
            // here is also emitted by AssetGraphProjectionContributor; any new
            // node-property emit must register here too or AGE materialization
            // throws DomainValidationException at write time.
            "steward",
            "environment",
            "criticality",
            "businessContext",
            "scopeDesignation",
            // GC-M018 knowledge / completeness state. Emitted on both
            // OPERATIONAL_ASSET nodes and AssetRelation edges by
            // AssetGraphProjectionContributor; must be approved here or AGE
            // materialization rejects the writes with
            // DomainValidationException at validatePropertyKey time.
            "knowledgeState",
            "categoryTags",
            "expiresAt",
            "observedAt",
            // Observation projection.
            "narrative",
            "observationDate",
            "observationKey",
            // Architecture model projection.
            "elementKind",
            "modelVersion",
            "schemaVersion",
            "summary",
            "sourcePath",
            "trustBoundaryKey",
            "dataClassificationKey",
            "flowDirection",
            "flowStableKey",
            "provenanceSource",
            "commitSha",
            "observationValue",
            "evidenceRef",
            "analystIdentity",
            "confidence",
            // Risk-scenario projection.
            "category",
            "threat",
            "method",
            "asset",
            "effect",
            "stride",
            "timeHorizon",
            "property",
            "strategy",
            // Treatment / control / verification.
            "result",
            "verifiedAt",
            "prover",
            "source",
            "reviewCadence",
            "nextReviewAt",
            "dueDate",
            "assessmentAt",
            "assuranceLevel",
            // Methodology profile.
            "family",
            "version",
            // Finding projection (GC-V001 / ADR-038).
            "findingType",
            "severity",
            "rootCauseAnalysis",
            // Control / ControlTest / ControlEffectivenessAssessment projections (ADR-039).
            "controlFunction",
            "controlUid",
            "methodology",
            "conclusion",
            "testerIdentity",
            "testDate",
            "designEffectiveness",
            "operatingEffectiveness",
            "assessor",
            "assessedAt",
            // Document projection (GC-G007). updatedAt is also referenced here for
            // the first time; all other Document keys (title, version, description,
            // createdBy, createdAt) were already present from earlier contributors.
            "updatedAt",
            // Research graph projection (ADR-070, #1003). Bounded identifiers, enum
            // names, attempt counts, hashes, and timestamps only — never summary,
            // locator, subjectKey, or other raw research content (ADR-070 §5).
            // status is reused from the requirement projection above.
            "currentStage",
            "autonomyLevel",
            "startedAt",
            "stoppedAt",
            "artifactType",
            "stage",
            "attemptNo",
            "contentHash",
            "kind",
            "externalIdentifier");
    // AGE's ag_catalog.cypher() function takes cstring/cstring/agtype. Its first two arguments
    // are parsed at SQL parse time by AGE's parser hook, so they cannot be JDBC bind parameters
    // — they must be SQL literals. The third argument (params agtype) is the user-data carrier
    // and IS bound through JDBC, so all user-controlled values (UIDs, project identifiers,
    // free-form requirement properties) flow exclusively through the bound params payload and
    // are referenced from the cypher template via $paramName (AGE-internal substitution).
    //
    // Graph names and cypher templates are constructed solely from allowlisted identifiers
    // (graph name, entity-type labels, edge-type labels, property keys — all validated by
    // validateGraphName) and Cypher template syntax. No user value ever reaches the SQL string.

    /** Scope recorded on every snapshot today: one global all-project graph (ADR-062 seam). */
    private static final String SNAPSHOT_SCOPE_GLOBAL = "GLOBAL";

    /**
     * Advisory-lock key serializing concurrent graph publications so two refreshes cannot
     * interleave their snapshot writes or race the active-snapshot version. Arbitrary stable
     * constant; held for the duration of the publishing transaction via {@code
     * pg_advisory_xact_lock}.
     */
    private static final long GRAPH_PUBLICATION_ADVISORY_LOCK_KEY = 0x6763_6772_6170_68L; // "gcgraph"

    private final JdbcTemplate jdbcTemplate;
    private final AgeProperties ageProperties;
    private final GraphProjectionRegistryService graphProjectionRegistryService;
    private final ProjectRepository projectRepository;
    private final AgeGraphSnapshotRepository snapshotRepository;
    private final AgeSnapshotCleaner snapshotCleaner;

    public AgeGraphService(
            JdbcTemplate jdbcTemplate,
            AgeProperties ageProperties,
            GraphProjectionRegistryService graphProjectionRegistryService,
            ProjectRepository projectRepository,
            AgeGraphSnapshotRepository snapshotRepository,
            AgeSnapshotCleaner snapshotCleaner) {
        this.jdbcTemplate = jdbcTemplate;
        this.ageProperties = ageProperties;
        this.graphProjectionRegistryService = graphProjectionRegistryService;
        this.projectRepository = projectRepository;
        this.snapshotRepository = snapshotRepository;
        this.snapshotCleaner = snapshotCleaner;
    }

    @Override
    public void materializeGraph() {
        if (!ageProperties.enabled()) {
            log.debug("graph_materialization_skipped: reason=disabled");
            return;
        }

        // Fail fast on a misconfigured base graph name before acquiring any lock or sequence value.
        String baseGraph = validateGraphName(ageProperties.graphName());
        setupSearchPath();

        // Serialize concurrent publishers: only one materialization at a time builds a snapshot and
        // advances the active version. Held until this transaction ends (ADR-062).
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + GRAPH_PUBLICATION_ADVISORY_LOCK_KEY + ")");

        long version = snapshotRepository.nextVersion();
        String snapshotGraph = validateGraphName(baseGraph + "_v" + version);

        // Build the new snapshot into a fresh, inactive graph — the active snapshot readers query is
        // never touched. The snapshot name is allowlist-validated (ADR-032) AND bound as a parameter
        // here (cast to AGE's name type) rather than concatenated into the SQL; user data still flows
        // only through the bound agtype params of each CREATE.
        jdbcTemplate.query(
                "SELECT create_graph(?::name)",
                (PreparedStatementSetter) ps -> ps.setString(1, snapshotGraph),
                (RowCallbackHandler) rs -> {});

        var projection = graphProjectionRegistryService.buildProjection();
        for (GraphNode node : projection.nodes()) {
            executeCreateNode(snapshotGraph, node);
        }
        for (GraphEdge edge : projection.edges()) {
            executeCreateEdge(snapshotGraph, edge);
        }

        // Publish: record the new snapshot. Because the active snapshot is the greatest-version row,
        // this INSERT — visible only at commit — is the atomic pointer swap. A failed refresh rolls
        // back the new graph and this row together, leaving the previous snapshot active.
        snapshotRepository.insertSnapshot(
                version,
                snapshotGraph,
                SNAPSHOT_SCOPE_GLOBAL,
                projection.nodes().size(),
                projection.edges().size(),
                ActorHolder.get());

        log.info(
                "graph_snapshot_published: graph={} nodes={} edges={} version={}",
                snapshotGraph,
                projection.nodes().size(),
                projection.edges().size(),
                version);

        // Drop snapshots beyond retention only AFTER this swap commits — never before, so a reader
        // mid-resolution cannot lose its snapshot. Skipped when no transaction synchronization is
        // active (e.g. a unit test invoking the method without a surrounding transaction).
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    snapshotCleaner.cleanup();
                }
            });
        }
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

    @Override
    public List<String> getAncestors(UUID projectId, String uid, int depth) {
        if (!ageProperties.enabled()) {
            return List.of();
        }
        validateUid(uid);
        validateDepth(depth);
        String graph = resolveActiveGraph();
        String projectIdentifier = getProjectIdentifier(projectId);
        setupSearchPath();

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

    @Override
    public List<String> getDescendants(UUID projectId, String uid, int depth) {
        if (!ageProperties.enabled()) {
            return List.of();
        }
        validateUid(uid);
        validateDepth(depth);
        String graph = resolveActiveGraph();
        String projectIdentifier = getProjectIdentifier(projectId);
        setupSearchPath();

        // Inverse of getAncestors: descendants of n are reachable by following INCOMING PARENT
        // edges to n.
        String cypher = "MATCH (n:REQUIREMENT {uid: $uid, project_identifier: $project_identifier})"
                + "<-[:PARENT*1.." + depth + "]-(d:REQUIREMENT {project_identifier: $project_identifier}) "
                + "RETURN d.uid";
        String params = encodeParams(Map.of(KEY_UID, uid, KEY_PROJECT_IDENTIFIER, projectIdentifier));

        return queryUids(graph, cypher, params);
    }

    @Override
    public List<PathResult> findPaths(UUID projectId, String sourceUid, String targetUid) {
        if (!ageProperties.enabled()) {
            return List.of();
        }
        validateUid(sourceUid);
        validateUid(targetUid);
        String graph = resolveActiveGraph();
        String projectIdentifier = getProjectIdentifier(projectId);
        setupSearchPath();

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
            List<String> nodeUids = extractPathNodeUids(rs.getString(1));
            List<String> edgeLabels = extractPathEdgeLabels(rs.getString(2));
            paths.add(new PathResult(nodeUids, edgeLabels));
        });
        return paths;
    }

    @Override
    public GraphProjection getVisualization(UUID projectId, Set<GraphEntityType> entityTypes) {
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
        setupSearchPath();

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

    private static GraphProjection filterByEntityType(GraphProjection projection, Set<GraphEntityType> entityTypes) {
        List<GraphNode> nodes = projection.nodes().stream()
                .filter(node -> entityTypes.contains(node.entityType()))
                .toList();
        java.util.Set<String> visibleNodeIds =
                nodes.stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet());
        List<GraphEdge> edges = projection.edges().stream()
                .filter(edge -> visibleNodeIds.contains(edge.sourceId()) && visibleNodeIds.contains(edge.targetId()))
                .toList();
        return new GraphProjection(nodes, edges);
    }

    private static GraphProjection enforceProjectionSizeCap(GraphProjection projection) {
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

    private void executeCreateNode(String graph, GraphNode node) {
        String label = validateGraphName(node.entityType().name());
        Map<String, Object> nodeProps = new LinkedHashMap<>();
        nodeProps.put(KEY_ID, node.id());
        nodeProps.put(KEY_DOMAIN_ID, node.domainId());
        nodeProps.put(KEY_ENTITY_TYPE, node.entityType().name());
        nodeProps.put(KEY_PROJECT_IDENTIFIER, node.projectIdentifier());
        nodeProps.put(KEY_UID, node.uid());
        nodeProps.put(KEY_LABEL, node.label());
        nodeProps.putAll(node.properties());

        ParamBuilder builder = new ParamBuilder("p_");
        String propClause = renderPropertyClause(nodeProps, builder);
        String cypher = "CREATE (:" + label + " " + propClause + ")";
        executeCypher(graph, cypher, builder.toJson());
    }

    private void executeCreateEdge(String graph, GraphEdge edge) {
        String edgeType = validateGraphName(edge.edgeType());
        Map<String, Object> edgeProps = new LinkedHashMap<>();
        edgeProps.put(KEY_ID, edge.id());
        edgeProps.put(KEY_EDGE_TYPE, edge.edgeType());
        edgeProps.put(KEY_SOURCE_ID, edge.sourceId());
        edgeProps.put(KEY_TARGET_ID, edge.targetId());
        edgeProps.put(KEY_SOURCE_ENTITY_TYPE, edge.sourceEntityType().name());
        edgeProps.put(KEY_TARGET_ENTITY_TYPE, edge.targetEntityType().name());
        edgeProps.putAll(edge.properties());

        ParamBuilder builder = new ParamBuilder("p_");
        builder.put(KEY_SOURCE_ID, edge.sourceId());
        builder.put(KEY_TARGET_ID, edge.targetId());
        // Re-use the same builder so all values share the single agtype params payload.
        ParamBuilder propBuilder = new ParamBuilder(builder, "pp_");
        String propClause = renderPropertyClause(edgeProps, propBuilder);
        String cypher = "MATCH (s {id: $p_source_id}), (t {id: $p_target_id}) " + "CREATE (s)-[:" + edgeType + " "
                + propClause + "]->(t)";
        executeCypher(graph, cypher, propBuilder.toJson());
    }

    /**
     * Build the SQL for a single-column-cypher() call. Graph name and cypher template are
     * concatenated as SQL literals — both come from constants and allowlisted identifiers, never
     * from user input. The third {@code agtype} parameter (carrying every user value) is bound
     * via JDBC as a bare {@code ?} placeholder, with the parameter typed as {@code agtype} via
     * a {@link PGobject} (AGE rejects {@code ?::agtype} SQL casts because its third-arg check
     * requires a bare {@code Param} parser node, not a wrapping {@code TypeCast}).
     */
    private static String buildCypherSql(String graph, String cypher) {
        return "SELECT * FROM ag_catalog.cypher('" + graph + "', $gc$" + cypher + "$gc$, ?) AS (v agtype)";
    }

    /** Same as {@link #buildCypherSql} but for path queries that return two agtype columns. */
    private static String buildCypherPathSql(String graph, String cypher) {
        return "SELECT * FROM ag_catalog.cypher('" + graph + "', $gc$" + cypher
                + "$gc$, ?) AS (nodes agtype, rels agtype)";
    }

    private void executeCypher(String graph, String cypher, String params) {
        // ag_catalog.cypher(...) always returns a SETOF agtype, even for write statements like
        // DETACH DELETE or CREATE. JdbcTemplate.update() rejects that with "A result was returned
        // when none was expected"; route through query() with a no-op handler instead.
        jdbcTemplate.query(buildCypherSql(graph, cypher), bindAgtypeParams(params), (RowCallbackHandler) rs -> {});
    }

    private List<String> queryUids(String graph, String cypher, String params) {
        List<String> results = new ArrayList<>();
        jdbcTemplate.query(buildCypherSql(graph, cypher), bindAgtypeParams(params), (RowCallbackHandler)
                rs -> results.add(stringValue(parseAgtypeValue(rs.getString(1)))));
        return results;
    }

    /**
     * Bind {@code paramsJson} as a single positional parameter typed as the AGE {@code agtype}
     * pseudotype. We can't use a SQL-level {@code ?::agtype} cast because AGE checks that the
     * third argument of {@code ag_catalog.cypher(...)} is a bare {@code Param} parser node;
     * wrapping it in a {@code TypeCast} fails that check with "third argument of cypher function
     * must be a parameter". Setting the parameter type via {@link PGobject} ensures PostgreSQL
     * knows the type at PREPARE time without rewriting the SQL.
     */
    private static PreparedStatementSetter bindAgtypeParams(String paramsJson) {
        return ps -> {
            PGobject obj = new PGobject();
            try {
                obj.setType("agtype");
                obj.setValue(paramsJson);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to bind agtype parameter", e);
            }
            ps.setObject(1, obj);
        };
    }

    /**
     * Render a Cypher property map clause where every value is bound through {@code builder}
     * rather than inlined as a Cypher literal. Property keys are validated against the strict
     * Cypher-property-key grammar (no hyphens, no leading digits) — AGE does not parameterize
     * property keys, so they must be safe SQL/Cypher tokens.
     */
    private static String renderPropertyClause(Map<String, Object> properties, ParamBuilder builder) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (var entry : properties.entrySet()) {
            String key = validateCypherKey(entry.getKey());
            String paramName = builder.put(entry.getValue());
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(key).append(": $").append(paramName);
        }
        out.append("}");
        return out.toString();
    }

    private GraphNode toGraphNode(Map<String, Object> props) {
        String id = stringValue(props.remove(KEY_ID));
        String domainId = stringValue(props.remove(KEY_DOMAIN_ID));
        GraphEntityType entityType = GraphEntityType.valueOf(stringValue(props.remove(KEY_ENTITY_TYPE)));
        String projectIdentifier = stringValue(props.remove(KEY_PROJECT_IDENTIFIER));
        String uid = stringValue(props.remove(KEY_UID));
        String label = stringValue(props.remove(KEY_LABEL));
        return new GraphNode(id, domainId, entityType, projectIdentifier, uid, label, props);
    }

    private GraphEdge toGraphEdge(Map<String, Object> props) {
        String id = stringValue(props.remove(KEY_ID));
        String edgeType = stringValue(props.remove(KEY_EDGE_TYPE));
        String sourceId = stringValue(props.remove(KEY_SOURCE_ID));
        String targetId = stringValue(props.remove(KEY_TARGET_ID));
        GraphEntityType sourceEntityType = GraphEntityType.valueOf(stringValue(props.remove(KEY_SOURCE_ENTITY_TYPE)));
        GraphEntityType targetEntityType = GraphEntityType.valueOf(stringValue(props.remove(KEY_TARGET_ENTITY_TYPE)));
        return new GraphEdge(id, edgeType, sourceId, targetId, sourceEntityType, targetEntityType, props);
    }

    private void setupSearchPath() {
        jdbcTemplate.execute("LOAD 'age'");
        jdbcTemplate.execute("SET search_path = ag_catalog, \"$user\", public");
    }

    /**
     * AGE returns vertex objects with a top-level {@code properties} map; pull the {@code uid}
     * out of each element of an agtype list. Used by {@link #findPaths} to extract the UID
     * sequence from a path's {@code nodes(path)} return.
     */
    @SuppressWarnings("unchecked")
    public static List<String> extractPathNodeUids(String agtypeValue) {
        Object parsed = parseAgtypeValue(agtypeValue);
        if (!(parsed instanceof List<?> values)) {
            return List.of();
        }
        List<String> uids = new ArrayList<>(values.size());
        for (Object element : values) {
            if (element instanceof Map<?, ?> vertex) {
                Object props = vertex.get("properties");
                if (props instanceof Map<?, ?> propMap) {
                    Object uid = propMap.get("uid");
                    if (uid != null) {
                        uids.add(uid.toString());
                    }
                }
            }
        }
        return uids;
    }

    /**
     * AGE edge objects carry the relationship label at the top level. Pull each label from
     * {@code relationships(path)}.
     */
    @SuppressWarnings("unchecked")
    public static List<String> extractPathEdgeLabels(String agtypeValue) {
        Object parsed = parseAgtypeValue(agtypeValue);
        if (!(parsed instanceof List<?> values)) {
            return List.of();
        }
        List<String> labels = new ArrayList<>(values.size());
        for (Object element : values) {
            if (element instanceof Map<?, ?> edge) {
                Object label = edge.get(KEY_LABEL);
                if (label != null) {
                    labels.add(label.toString());
                }
            }
        }
        return labels;
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

    private static Object parseAgtypeValue(String agtypeValue) {
        if (agtypeValue == null || agtypeValue.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(stripAgtypeTypeTags(agtypeValue), new TypeReference<>() {});
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to parse AGE agtype value: " + agtypeValue, exception);
        }
    }

    /**
     * AGE serializes vertex/edge/path values with a {@code ::vertex} / {@code ::edge} /
     * {@code ::path} type tag suffix that standard JSON parsers do not understand. Strip those
     * tags so the surrounding object literal parses as plain JSON.
     *
     * <p>A naive {@code String.replace("}::vertex", "}")} would also corrupt user-controlled
     * string values (a requirement title containing the literal sequence {@code }::vertex}
     * would lose its tag suffix). This walker tracks JSON-string state — including {@code \"}
     * escapes — and only rewrites type tags that appear in structural positions outside any
     * quoted string. Package-private for unit-test verification.
     */
    public static String stripAgtypeTypeTags(String agtypeValue) {
        StringBuilder out = new StringBuilder(agtypeValue.length());
        boolean inString = false;
        boolean escape = false;
        int i = 0;
        while (i < agtypeValue.length()) {
            char c = agtypeValue.charAt(i);
            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                i++;
            } else if (c == '"') {
                inString = true;
                out.append(c);
                i++;
            } else if (c == '}' && matchesTypeTagAt(agtypeValue, i + 1)) {
                out.append('}');
                i = i + 1 + lengthOfTypeTagAt(agtypeValue, i + 1);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean matchesTypeTagAt(String s, int pos) {
        return s.startsWith(AGTYPE_TAG_VERTEX, pos)
                || s.startsWith(AGTYPE_TAG_EDGE, pos)
                || s.startsWith(AGTYPE_TAG_PATH, pos);
    }

    private static int lengthOfTypeTagAt(String s, int pos) {
        if (s.startsWith(AGTYPE_TAG_VERTEX, pos)) {
            return AGTYPE_TAG_VERTEX.length();
        }
        if (s.startsWith(AGTYPE_TAG_EDGE, pos)) {
            return AGTYPE_TAG_EDGE.length();
        }
        return AGTYPE_TAG_PATH.length();
    }

    private static String encodeParams(Map<String, Object> params) {
        try {
            return OBJECT_MAPPER.writeValueAsString(params);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to encode AGE Cypher params", exception);
        }
    }

    private String getProjectIdentifier(UUID projectId) {
        return projectRepository
                .findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId))
                .getIdentifier();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Validate a token that will be embedded in the SQL/Cypher text as an identifier (graph
     * name, node label, edge type, snapshot graph name). Delegates to the canonical
     * {@link AgeIdentifiers} allowlist so this adapter and {@link AgeSnapshotCleaner} share one
     * policy. Identifiers reach AGE as part of a SQL literal — they cannot be parameter-bound —
     * so the allowlist is a hard requirement, not defense in depth.
     */
    private static String validateGraphName(String name) {
        return AgeIdentifiers.validateGraphName(name);
    }

    /**
     * Validate a Cypher property key. Two layers: (1) {@link #APPROVED_PROPERTY_KEYS} —
     * per ADR-032, keys must come from a fixed allowlist, not just match a syntactic pattern,
     * so contributors can't silently grow the AGE schema; (2) {@link #SAFE_PARAM_NAME} —
     * defense-in-depth syntactic check that the registry entry itself is a safe Cypher
     * identifier (no hyphens, no leading digits). The registry check is the primary contract.
     */
    private static String validateCypherKey(String key) {
        if (key == null || !APPROVED_PROPERTY_KEYS.contains(key)) {
            throw new DomainValidationException("Cypher property key not in approved AGE schema registry: " + key);
        }
        if (!SAFE_PARAM_NAME.matcher(key).matches()) {
            throw new DomainValidationException("Invalid Cypher property key syntax: " + key);
        }
        return key;
    }

    /**
     * Validate a requirement UID arriving at the AGE adapter. UIDs are now bound through the
     * agtype params payload, so injection is structurally impossible — this validator only
     * enforces the operational bounds the rest of the domain enforces (length matches the
     * {@code requirement.uid} column width; no control characters that would corrupt logs or
     * confuse downstream tooling). It deliberately does NOT enforce the
     * {@link AgeIdentifiers} graph-identifier grammar because importers (StrictDoc, ReqIF) accept
     * richer UID shapes and persisted requirements with such UIDs must remain queryable.
     */
    private static void validateUid(String uid) {
        if (uid == null || uid.isBlank()) {
            throw new DomainValidationException("Invalid UID for graph query: blank or null");
        }
        if (uid.length() > MAX_UID_LENGTH) {
            throw new DomainValidationException(
                    "Invalid UID for graph query: length " + uid.length() + " exceeds " + MAX_UID_LENGTH);
        }
        for (int i = 0; i < uid.length(); i++) {
            char c = uid.charAt(i);
            if (Character.isISOControl(c)) {
                throw new DomainValidationException("Invalid UID for graph query: contains control character");
            }
        }
    }

    private static void validateDepth(int depth) {
        if (depth < 1 || depth > MAX_GRAPH_TRAVERSAL_DEPTH) {
            throw new DomainValidationException(
                    "Invalid graph traversal depth: " + depth + " (must be 1.." + MAX_GRAPH_TRAVERSAL_DEPTH + ")");
        }
    }

    /**
     * Builder that emits unique Cypher parameter names AND collects the values into a single
     * agtype JSON payload. Parameter names are generated positionally (e.g., {@code p_0},
     * {@code p_1}) so they're decoupled from the property keys themselves. This means a future
     * graph contributor adding a hyphenated property key cannot accidentally produce an
     * invalid Cypher parameter name; the property-key validator catches the bad key separately.
     */
    private static final class ParamBuilder {
        private final Map<String, Object> values;
        private final String prefix;

        ParamBuilder(String prefix) {
            this.values = new LinkedHashMap<>();
            this.prefix = prefix;
        }

        ParamBuilder(ParamBuilder shared, String prefix) {
            this.values = shared.values;
            this.prefix = prefix;
        }

        String put(Object value) {
            String name = prefix + values.size();
            values.put(name, value);
            return name;
        }

        /**
         * Reserve a fixed-name parameter (used when the cypher template references a known
         * name like {@code $p_source_id}). The full parameter name is {@code prefix +
         * reservedName} and must satisfy {@link #SAFE_PARAM_NAME}.
         */
        String put(String reservedName, Object value) {
            String fullName = prefix + reservedName;
            if (!SAFE_PARAM_NAME.matcher(fullName).matches()) {
                throw new DomainValidationException("Invalid reserved Cypher parameter name: " + fullName);
            }
            values.put(fullName, value);
            return fullName;
        }

        String toJson() {
            return encodeParams(values);
        }
    }
}

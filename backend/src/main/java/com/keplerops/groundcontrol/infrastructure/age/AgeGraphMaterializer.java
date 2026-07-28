package com.keplerops.groundcontrol.infrastructure.age;

import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphService.GRAPH_PUBLICATION_ADVISORY_LOCK_KEY;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphService.ParamBuilder;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphService.SNAPSHOT_SCOPE_GLOBAL;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphService.log;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_DOMAIN_ID;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_EDGE_TYPE;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_ENTITY_TYPE;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_ID;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_LABEL;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_PROJECT_IDENTIFIER;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_SOURCE_ENTITY_TYPE;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_SOURCE_ID;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_TARGET_ENTITY_TYPE;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_TARGET_ID;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.KEY_UID;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.bindAgtypeParams;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.buildCypherSql;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.renderPropertyClause;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.validateGraphName;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.audit.service.AsOfRevisionResolver;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.graph.service.GraphProjectionRegistryService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes a graph snapshot and advances the active version (ADR-062).
 *
 * Split out of {@link AgeGraphService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class AgeGraphMaterializer {

    private final JdbcTemplate jdbcTemplate;
    private final AgeProperties ageProperties;
    private final GraphProjectionRegistryService graphProjectionRegistryService;
    private final AgeGraphSnapshotRepository snapshotRepository;
    private final AgeSnapshotCleaner snapshotCleaner;
    private final AsOfRevisionResolver asOfRevisionResolver;
    private final AgeGraphService service;

    AgeGraphMaterializer(
            JdbcTemplate jdbcTemplate,
            AgeProperties ageProperties,
            GraphProjectionRegistryService graphProjectionRegistryService,
            AgeGraphSnapshotRepository snapshotRepository,
            AgeSnapshotCleaner snapshotCleaner,
            AsOfRevisionResolver asOfRevisionResolver,
            AgeGraphService service) {
        this.jdbcTemplate = jdbcTemplate;
        this.ageProperties = ageProperties;
        this.graphProjectionRegistryService = graphProjectionRegistryService;
        this.snapshotRepository = snapshotRepository;
        this.snapshotCleaner = snapshotCleaner;
        this.asOfRevisionResolver = asOfRevisionResolver;
        this.service = service;
    }

    void materializeGraph() {
        if (!ageProperties.enabled()) {
            log.debug("graph_materialization_skipped: reason=disabled");
            return;
        }

        // Fail fast on a misconfigured base graph name before acquiring any lock or sequence value.
        String baseGraph = validateGraphName(ageProperties.graphName());
        service.setupSearchPath();

        // Serialize concurrent publishers: only one materialization at a time builds a snapshot and
        // advances the active version. Held until this transaction ends (ADR-062).
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + GRAPH_PUBLICATION_ADVISORY_LOCK_KEY + ")");

        // ADR-084 §5: resolve the as-of revision here, strictly after the advisory lock and
        // strictly before buildProjection() below, so this query and every contributor read
        // inside the projection share the same REPEATABLE_READ snapshot (established at the
        // transaction's first statement — the advisory lock above). Empty means no revision has
        // ever been created yet (a fresh database); that stays NULL, never a fabricated 0/-1.
        Integer sourceRevision = asOfRevisionResolver.currentRevision().orElse(null);

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
                sourceRevision,
                ActorHolder.get());

        log.info(
                "graph_snapshot_published: graph={} nodes={} edges={} version={} sourceRevision={}",
                snapshotGraph,
                projection.nodes().size(),
                projection.edges().size(),
                version,
                sourceRevision);

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

    private void executeCypher(String graph, String cypher, String params) {
        // ag_catalog.cypher(...) always returns a SETOF agtype, even for write statements like
        // DETACH DELETE or CREATE. JdbcTemplate.update() rejects that with "A result was returned
        // when none was expected"; route through query() with a no-op handler instead.
        jdbcTemplate.query(buildCypherSql(graph, cypher), bindAgtypeParams(params), (RowCallbackHandler) rs -> {});
    }
}

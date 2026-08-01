package com.keplerops.groundcontrol.infrastructure.age;

import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphService.APPROVED_PROPERTY_KEYS;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphService.MAX_GRAPH_TRAVERSAL_DEPTH;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphService.MAX_UID_LENGTH;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.graph.GraphTraversalLimits;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.graph.model.GraphProjection;
import com.keplerops.groundcontrol.infrastructure.age.AgeGraphService.ParamBuilder;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.PreparedStatementSetter;

/**
 * Stateless helpers split out of {@link AgeGraphService} under issue #1467
 * for the 500-LOC limit (docs/CODING_STANDARDS.md).
 *
 * Every method here touches no instance state, so it is static and the
 * original keeps a static import for each -- call sites are unchanged.
 */
final class AgeGraphServiceSupport {

    private AgeGraphServiceSupport() {}

    static final java.util.regex.Pattern SAFE_PARAM_NAME = java.util.regex.Pattern.compile("^[a-zA-Z_]\\w*$");

    // Implicit AGE property keys this adapter writes / reads. Centralized so
    // executeCreateNode/Edge, toGraphNode/Edge, getVisualization, and APPROVED_PROPERTY_KEYS
    // all reference the same string literal.
    static final String KEY_ID = "id";
    static final String KEY_DOMAIN_ID = "domain_id";
    static final String KEY_ENTITY_TYPE = "entity_type";
    static final String KEY_PROJECT_IDENTIFIER = "project_identifier";
    static final String KEY_UID = "uid";
    static final String KEY_LABEL = "label";
    static final String KEY_EDGE_TYPE = "edge_type";
    static final String KEY_SOURCE_ID = "source_id";
    static final String KEY_TARGET_ID = "target_id";
    static final String KEY_SOURCE_ENTITY_TYPE = "source_entity_type";
    static final String KEY_TARGET_ENTITY_TYPE = "target_entity_type";

    static final String AGTYPE_TAG_VERTEX = "::vertex";
    static final String AGTYPE_TAG_EDGE = "::edge";
    static final String AGTYPE_TAG_PATH = "::path";
    // Jackson mapper used for both the agtype params payload and for parsing AGE rows back. We
    // explicitly disable WRITE_DATES_AS_TIMESTAMPS so that Instant / LocalDate properties are
    // bound as ISO-8601 strings (matching the previous String.format-based path that called
    // instant.toString()). Without this, JavaTimeModule would default to Jackson's
    // big-decimal-seconds shape, and getVisualization() would silently emit numeric timestamps
    // when AGE is enabled while still emitting ISO strings when AGE is disabled — a
    // configuration-dependent API regression.
    static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Validate a requirement UID arriving at the AGE adapter. UIDs are now bound through the
     * agtype params payload, so injection is structurally impossible — this validator only
     * enforces the operational bounds the rest of the domain enforces (length matches the
     * {@code requirement.uid} column width; no control characters that would corrupt logs or
     * confuse downstream tooling). It deliberately does NOT enforce the
     * {@link AgeIdentifiers} graph-identifier grammar because importers (StrictDoc, ReqIF) accept
     * richer UID shapes and persisted requirements with such UIDs must remain queryable.
     */
    static void validateUid(String uid) {
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

    /**
     * Render a Cypher property map clause where every value is bound through {@code builder}
     * rather than inlined as a Cypher literal. Property keys are validated against the strict
     * Cypher-property-key grammar (no hyphens, no leading digits) — AGE does not parameterize
     * property keys, so they must be safe SQL/Cypher tokens.
     */
    static String renderPropertyClause(Map<String, Object> properties, ParamBuilder builder) {
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

    /**
     * Bind {@code paramsJson} as a single positional parameter typed as the AGE {@code agtype}
     * pseudotype. We can't use a SQL-level {@code ?::agtype} cast because AGE checks that the
     * third argument of {@code ag_catalog.cypher(...)} is a bare {@code Param} parser node;
     * wrapping it in a {@code TypeCast} fails that check with "third argument of cypher function
     * must be a parameter". Setting the parameter type via {@link PGobject} ensures PostgreSQL
     * knows the type at PREPARE time without rewriting the SQL.
     */
    static PreparedStatementSetter bindAgtypeParams(String paramsJson) {
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
     * Validate a Cypher property key. Two layers: (1) {@link #APPROVED_PROPERTY_KEYS} —
     * per ADR-032, keys must come from a fixed allowlist, not just match a syntactic pattern,
     * so contributors can't silently grow the AGE schema; (2) {@link #SAFE_PARAM_NAME} —
     * defense-in-depth syntactic check that the registry entry itself is a safe Cypher
     * identifier (no hyphens, no leading digits). The registry check is the primary contract.
     */
    static String validateCypherKey(String key) {
        if (key == null || !APPROVED_PROPERTY_KEYS.contains(key)) {
            throw new DomainValidationException("Cypher property key not in approved AGE schema registry: " + key);
        }
        if (!SAFE_PARAM_NAME.matcher(key).matches()) {
            throw new DomainValidationException("Invalid Cypher property key syntax: " + key);
        }
        return key;
    }

    static GraphProjection enforceProjectionSizeCap(GraphProjection projection) {
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

    static GraphProjection filterByEntityType(GraphProjection projection, Set<GraphEntityType> entityTypes) {
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

    /**
     * Build the SQL for a single-column-cypher() call. Graph name and cypher template are
     * concatenated as SQL literals — both come from constants and allowlisted identifiers, never
     * from user input. The third {@code agtype} parameter (carrying every user value) is bound
     * via JDBC as a bare {@code ?} placeholder, with the parameter typed as {@code agtype} via
     * a {@link PGobject} (AGE rejects {@code ?::agtype} SQL casts because its third-arg check
     * requires a bare {@code Param} parser node, not a wrapping {@code TypeCast}).
     */
    static String buildCypherSql(String graph, String cypher) {
        return "SELECT * FROM ag_catalog.cypher('" + graph + "', $gc$" + cypher + "$gc$, ?) AS (v agtype)";
    }

    /**
     * Validate a token that will be embedded in the SQL/Cypher text as an identifier (graph
     * name, node label, edge type, snapshot graph name). Delegates to the canonical
     * {@link AgeIdentifiers} allowlist so this adapter and {@link AgeSnapshotCleaner} share one
     * policy. Identifiers reach AGE as part of a SQL literal — they cannot be parameter-bound —
     * so the allowlist is a hard requirement, not defense in depth.
     */
    static String validateGraphName(String name) {
        return AgeIdentifiers.validateGraphName(name);
    }

    static GraphEdge toGraphEdge(Map<String, Object> props) {
        String id = stringValue(props.remove(KEY_ID));
        String edgeType = stringValue(props.remove(KEY_EDGE_TYPE));
        String sourceId = stringValue(props.remove(KEY_SOURCE_ID));
        String targetId = stringValue(props.remove(KEY_TARGET_ID));
        GraphEntityType sourceEntityType = GraphEntityType.valueOf(stringValue(props.remove(KEY_SOURCE_ENTITY_TYPE)));
        GraphEntityType targetEntityType = GraphEntityType.valueOf(stringValue(props.remove(KEY_TARGET_ENTITY_TYPE)));
        return new GraphEdge(id, edgeType, sourceId, targetId, sourceEntityType, targetEntityType, props);
    }

    static int lengthOfTypeTagAt(String s, int pos) {
        if (s.startsWith(AGTYPE_TAG_VERTEX, pos)) {
            return AGTYPE_TAG_VERTEX.length();
        }
        if (s.startsWith(AGTYPE_TAG_EDGE, pos)) {
            return AGTYPE_TAG_EDGE.length();
        }
        return AGTYPE_TAG_PATH.length();
    }

    static GraphNode toGraphNode(Map<String, Object> props) {
        String id = stringValue(props.remove(KEY_ID));
        String domainId = stringValue(props.remove(KEY_DOMAIN_ID));
        GraphEntityType entityType = GraphEntityType.valueOf(stringValue(props.remove(KEY_ENTITY_TYPE)));
        String projectIdentifier = stringValue(props.remove(KEY_PROJECT_IDENTIFIER));
        String uid = stringValue(props.remove(KEY_UID));
        String label = stringValue(props.remove(KEY_LABEL));
        return new GraphNode(id, domainId, entityType, projectIdentifier, uid, label, props);
    }

    static String encodeParams(Map<String, Object> params) {
        try {
            return OBJECT_MAPPER.writeValueAsString(params);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to encode AGE Cypher params", exception);
        }
    }

    static void validateDepth(int depth) {
        if (depth < 1 || depth > MAX_GRAPH_TRAVERSAL_DEPTH) {
            throw new DomainValidationException(
                    "Invalid graph traversal depth: " + depth + " (must be 1.." + MAX_GRAPH_TRAVERSAL_DEPTH + ")");
        }
    }

    static boolean matchesTypeTagAt(String s, int pos) {
        return s.startsWith(AGTYPE_TAG_VERTEX, pos)
                || s.startsWith(AGTYPE_TAG_EDGE, pos)
                || s.startsWith(AGTYPE_TAG_PATH, pos);
    }

    /** Same as {@link #buildCypherSql} but for path queries that return two agtype columns. */
    static String buildCypherPathSql(String graph, String cypher) {
        return "SELECT * FROM ag_catalog.cypher('" + graph + "', $gc$" + cypher
                + "$gc$, ?) AS (nodes agtype, rels agtype)";
    }

    static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

package com.keplerops.groundcontrol.infrastructure.age;

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
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.OBJECT_MAPPER;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.SAFE_PARAM_NAME;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.encodeParams;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.lengthOfTypeTagAt;
import static com.keplerops.groundcontrol.infrastructure.age.AgeGraphServiceSupport.matchesTypeTagAt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.keplerops.groundcontrol.domain.audit.service.AsOfRevisionResolver;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.graph.GraphTraversalLimits;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphProjection;
import com.keplerops.groundcontrol.domain.graph.service.GraphProjectionRegistryService;
import com.keplerops.groundcontrol.domain.graph.service.MixedGraphClient;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.service.GraphClient;
import com.keplerops.groundcontrol.domain.requirements.service.PathResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

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
// This is an infrastructure adapter; ArchitectureTest reserves @Service for ..service.. packages.
@SuppressWarnings("java:S5673")
@Component
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class AgeGraphService implements GraphClient, MixedGraphClient {

    static final Logger log = LoggerFactory.getLogger(AgeGraphService.class);
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
            "externalIdentifier",
            // Workflow reporting graph projection (ADR-061 amendment, #1311). Only the bounded
            // correlation, lifecycle, and phase-event fields needed for traversal are admitted.
            "repo",
            "issueNumber",
            "workflowType",
            "runtimeDriver",
            "finalState",
            "outcome",
            "provenance",
            "endedAt",
            "phase",
            "eventType",
            "cycleIndex",
            "occurredAt",
            "durationMs");
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
    static final String SNAPSHOT_SCOPE_GLOBAL = "GLOBAL";

    /**
     * Advisory-lock key serializing concurrent graph publications so two refreshes cannot
     * interleave their snapshot writes or race the active-snapshot version. Arbitrary stable
     * constant; held for the duration of the publishing transaction via {@code
     * pg_advisory_xact_lock}.
     */
    static final long GRAPH_PUBLICATION_ADVISORY_LOCK_KEY = 0x6763_6772_6170_68L; // "gcgraph"

    private final JdbcTemplate jdbcTemplate;
    private final AgeGraphReadOperations ageGraphReadOperations;
    private final AgeGraphMaterializer ageGraphMaterializer;

    public AgeGraphService(
            JdbcTemplate jdbcTemplate,
            AgeProperties ageProperties,
            GraphProjectionRegistryService graphProjectionRegistryService,
            ProjectRepository projectRepository,
            AgeGraphSnapshotRepository snapshotRepository,
            AgeSnapshotCleaner snapshotCleaner,
            AsOfRevisionResolver asOfRevisionResolver) {
        this.jdbcTemplate = jdbcTemplate;

        this.ageGraphReadOperations = new AgeGraphReadOperations(
                jdbcTemplate,
                ageProperties,
                graphProjectionRegistryService,
                projectRepository,
                snapshotRepository,
                this);

        this.ageGraphMaterializer = new AgeGraphMaterializer(
                jdbcTemplate,
                ageProperties,
                graphProjectionRegistryService,
                snapshotRepository,
                snapshotCleaner,
                asOfRevisionResolver,
                this);
    }

    void setupSearchPath() {
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

    static Object parseAgtypeValue(String agtypeValue) {
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

    /**
     * Builder that emits unique Cypher parameter names AND collects the values into a single
     * agtype JSON payload. Parameter names are generated positionally (e.g., {@code p_0},
     * {@code p_1}) so they're decoupled from the property keys themselves. This means a future
     * graph contributor adding a hyphenated property key cannot accidentally produce an
     * invalid Cypher parameter name; the property-key validator catches the bad key separately.
     */
    static final class ParamBuilder {
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

    @Override
    public List<String> getAncestors(UUID projectId, String uid, int depth) {
        return ageGraphReadOperations.getAncestors(projectId, uid, depth);
    }

    @Override
    public List<String> getDescendants(UUID projectId, String uid, int depth) {
        return ageGraphReadOperations.getDescendants(projectId, uid, depth);
    }

    @Override
    public List<PathResult> findPaths(UUID projectId, String sourceUid, String targetUid) {
        return ageGraphReadOperations.findPaths(projectId, sourceUid, targetUid);
    }

    @Override
    public GraphProjection getVisualization(UUID projectId, Set<GraphEntityType> entityTypes) {
        return ageGraphReadOperations.getVisualization(projectId, entityTypes);
    }

    @Override
    public void materializeGraph() {
        ageGraphMaterializer.materializeGraph();
    }
}

package com.keplerops.groundcontrol.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.graph.model.GraphProjection;
import com.keplerops.groundcontrol.domain.graph.service.GraphProjectionRegistryService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.infrastructure.age.AgeGraphService;
import com.keplerops.groundcontrol.infrastructure.age.AgeGraphSnapshotRepository;
import com.keplerops.groundcontrol.infrastructure.age.AgeProperties;
import com.keplerops.groundcontrol.infrastructure.age.AgeSnapshotCleaner;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;

@ExtendWith(MockitoExtension.class)
class AgeGraphServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private GraphProjectionRegistryService graphProjectionRegistryService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AgeGraphSnapshotRepository snapshotRepository;

    @Mock
    private AgeSnapshotCleaner snapshotCleaner;

    @Mock
    private com.keplerops.groundcontrol.domain.audit.service.AsOfRevisionResolver asOfRevisionResolver;

    private AgeGraphService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Project TEST_PROJECT = createTestProject();

    private static Project createTestProject() {
        var project = new Project("test-project", "Test Project");
        TestUtil.setField(project, "id", PROJECT_ID);
        return project;
    }

    @BeforeEach
    void setUp() {
        var disabledProperties = new AgeProperties(false, "requirements");
        service = new AgeGraphService(
                jdbcTemplate,
                disabledProperties,
                graphProjectionRegistryService,
                projectRepository,
                snapshotRepository,
                snapshotCleaner,
                asOfRevisionResolver);
    }

    @Nested
    class WhenDisabled {

        @Test
        void materializeGraph_isNoOp() {
            service.materializeGraph();

            verifyNoInteractions(
                    jdbcTemplate,
                    graphProjectionRegistryService,
                    projectRepository,
                    snapshotRepository,
                    snapshotCleaner);
        }

        @Test
        void getAncestors_returnsEmpty() {
            var result = service.getAncestors(PROJECT_ID, "REQ-001", 10);

            assertThat(result).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void getDescendants_returnsEmpty() {
            var result = service.getDescendants(PROJECT_ID, "REQ-001", 10);

            assertThat(result).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void findPaths_returnsEmpty() {
            var result = service.findPaths(PROJECT_ID, "REQ-001", "REQ-002");

            assertThat(result).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void getVisualization_appliesFilterBeforeCapInFallback() {
            var requirement = new GraphNode(
                    "REQUIREMENT:req-1", "req-1", GraphEntityType.REQUIREMENT, "p", "U-REQ", "REQ", Map.of());
            var asset = new GraphNode(
                    "OPERATIONAL_ASSET:asset-1",
                    "asset-1",
                    GraphEntityType.OPERATIONAL_ASSET,
                    "p",
                    "U-AS",
                    "AS",
                    Map.of());
            var edgeBetween = new GraphEdge(
                    "e1",
                    "ASSOCIATED",
                    requirement.id(),
                    asset.id(),
                    GraphEntityType.REQUIREMENT,
                    GraphEntityType.OPERATIONAL_ASSET,
                    Map.of());
            when(graphProjectionRegistryService.buildProjectionForProject(PROJECT_ID))
                    .thenReturn(new GraphProjection(List.of(requirement, asset), List.of(edgeBetween)));

            var filtered = service.getVisualization(PROJECT_ID, java.util.Set.of(GraphEntityType.REQUIREMENT));

            assertThat(filtered.nodes()).containsExactly(requirement);
            // Edge endpoints not both in the filter set → edge is pruned.
            assertThat(filtered.edges()).isEmpty();
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void getVisualization_rejectsWhenFilteredProjectionExceedsCapInFallback() {
            // Even after filter, if the result still exceeds MAX_PROJECTION_NODES we reject —
            // belt-and-suspenders for the AGE-disabled path where contributors materialize
            // everything before we can filter.
            int oversize = com.keplerops.groundcontrol.domain.graph.GraphTraversalLimits.MAX_PROJECTION_NODES + 1;
            java.util.List<GraphNode> nodes = new java.util.ArrayList<>(oversize);
            for (int i = 0; i < oversize; i++) {
                nodes.add(new GraphNode(
                        "REQUIREMENT:r-" + i,
                        "r-" + i,
                        GraphEntityType.REQUIREMENT,
                        "p",
                        "U-" + i,
                        "L-" + i,
                        Map.of()));
            }
            when(graphProjectionRegistryService.buildProjectionForProject(PROJECT_ID))
                    .thenReturn(new GraphProjection(nodes, List.of()));
            var emptyFilter = java.util.Set.<GraphEntityType>of();

            assertThatThrownBy(() -> service.getVisualization(PROJECT_ID, emptyFilter))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("projection node count");
        }
    }

    @Nested
    class WhenEnabled {

        private AgeGraphService enabledService;

        @BeforeEach
        void setUp() {
            var enabledProperties = new AgeProperties(true, "test_graph");
            enabledService = new AgeGraphService(
                    jdbcTemplate,
                    enabledProperties,
                    graphProjectionRegistryService,
                    projectRepository,
                    snapshotRepository,
                    snapshotCleaner,
                    asOfRevisionResolver);
            // An active snapshot exists (reads resolve it) and publication gets version 1; lenient
            // so the subset of tests that exercise only one of read/materialize don't trip strict stubs.
            lenient().when(snapshotRepository.findActiveGraphName()).thenReturn(Optional.of("test_graph"));
            lenient().when(snapshotRepository.nextVersion()).thenReturn(1L);
            lenient().when(asOfRevisionResolver.currentRevision()).thenReturn(Optional.of(5));
        }

        @Test
        void materializeGraph_buildsNewSnapshotGraphWithoutDestroyingActive() {
            when(graphProjectionRegistryService.buildProjection())
                    .thenReturn(new GraphProjection(List.of(), List.of()));

            enabledService.materializeGraph();

            // setupSearchPath: LOAD + SET via execute(String).
            verify(jdbcTemplate).execute("LOAD 'age'");
            verify(jdbcTemplate).execute("SET search_path = ag_catalog, \"$user\", public");
            // The publish is serialized by an advisory lock and builds into a NEW versioned graph
            // (create_graph, with the snapshot name bound as a parameter). Crucially, the live graph
            // is NEVER DETACH DELETEd — that was the destructive rebuild ADR-062 removes.
            ArgumentCaptor<String> execCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, atLeast(1)).execute(execCaptor.capture());
            assertThat(execCaptor.getAllValues())
                    .anyMatch(sql -> sql.contains("pg_advisory_xact_lock"))
                    .noneMatch(sql -> sql.contains("DETACH DELETE"));
            ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, atLeast(1))
                    .query(queryCaptor.capture(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
            assertThat(queryCaptor.getAllValues())
                    .anyMatch(sql -> sql.contains("create_graph(?"))
                    .noneMatch(sql -> sql.contains("DETACH DELETE"));
            // Publication records the new snapshot — the atomic active-version swap on commit.
            verify(snapshotRepository)
                    .insertSnapshot(eq(1L), eq("test_graph_v1"), eq("GLOBAL"), eq(0), eq(0), eq(5), any());
        }

        @Test
        void materializeGraph_withRequirements_createsNodesInSnapshotGraph() {
            var req = new Requirement(TEST_PROJECT, "GC-A001", "Test Req", "Statement");
            when(graphProjectionRegistryService.buildProjection())
                    .thenReturn(new GraphProjection(
                            List.of(new GraphNode(
                                    "REQUIREMENT:" + UUID.randomUUID(),
                                    req.getId() != null
                                            ? req.getId().toString()
                                            : UUID.randomUUID().toString(),
                                    GraphEntityType.REQUIREMENT,
                                    TEST_PROJECT.getIdentifier(),
                                    req.getUid(),
                                    req.getUid(),
                                    Map.of("title", req.getTitle()))),
                            List.of()));

            enabledService.materializeGraph();

            // create_graph (snapshot name bound as a parameter) and the node CREATE both go through
            // query(sql, pss, callback). Confirm a CREATE was emitted into the NEW snapshot graph
            // (the snapshot name reaches the cypher() graph argument via the existing ADR-032 path,
            // while user values stay bound) and that no DETACH DELETE was issued anywhere.
            ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, atLeast(2))
                    .query(queryCaptor.capture(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
            assertThat(queryCaptor.getAllValues())
                    .anyMatch(sql -> sql.contains("create_graph(?"))
                    .anyMatch(sql -> sql.contains("CREATE (:")
                            && sql.contains("REQUIREMENT")
                            && sql.contains("cypher('test_graph_v1'"))
                    .noneMatch(sql -> sql.contains("DETACH DELETE"));
            verify(snapshotRepository)
                    .insertSnapshot(eq(1L), eq("test_graph_v1"), eq("GLOBAL"), eq(1), eq(0), eq(5), any());
        }

        @Test
        void materializeGraph_capturesRevisionBetweenAdvisoryLockAndProjectionBuild() {
            // ADR-084 §5: the resolver query and every contributor read inside buildProjection()
            // must share the same REPEATABLE_READ snapshot, so the revision capture must happen
            // strictly after the advisory lock acquisition (serializes concurrent publishers) and
            // strictly before buildProjection() (the first contributor read). Capturing it earlier
            // (e.g. before the lock) or later (e.g. after the projection is built) would let the
            // recorded source_revision diverge from what the projection actually reflects.
            when(graphProjectionRegistryService.buildProjection())
                    .thenReturn(new GraphProjection(List.of(), List.of()));

            enabledService.materializeGraph();

            var inOrder =
                    org.mockito.Mockito.inOrder(jdbcTemplate, asOfRevisionResolver, graphProjectionRegistryService);
            inOrder.verify(jdbcTemplate).execute(org.mockito.ArgumentMatchers.contains("pg_advisory_xact_lock"));
            inOrder.verify(asOfRevisionResolver).currentRevision();
            inOrder.verify(graphProjectionRegistryService).buildProjection();
        }

        @Test
        void materializeGraph_recordsNullSourceRevisionWhenNoRevisionResolvedYet() {
            // A fresh database with nothing ever audited: the resolver honestly returns empty, and
            // that must reach the snapshot row as NULL, never a fabricated 0/-1 coordinate.
            when(asOfRevisionResolver.currentRevision()).thenReturn(Optional.empty());
            when(graphProjectionRegistryService.buildProjection())
                    .thenReturn(new GraphProjection(List.of(), List.of()));

            enabledService.materializeGraph();

            verify(snapshotRepository)
                    .insertSnapshot(
                            eq(1L),
                            eq("test_graph_v1"),
                            eq("GLOBAL"),
                            eq(0),
                            eq(0),
                            org.mockito.ArgumentMatchers.isNull(),
                            any());
        }

        @Test
        void getVisualization_fallsBackToConfiguredBaseGraphWhenNoSnapshotPublished() {
            // Upgrade/bootstrap path (codex F1): with no snapshot row yet, reads target the
            // configured base graph so an already-populated AGE graph keeps serving reads after a
            // deploy instead of going empty.
            when(snapshotRepository.findActiveGraphName()).thenReturn(Optional.empty());
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));

            enabledService.getVisualization(PROJECT_ID, java.util.Set.of());

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, times(2))
                    .query(sqlCaptor.capture(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
            assertThat(sqlCaptor.getAllValues()).allMatch(sql -> sql.contains("cypher('test_graph'"));
        }

        @Test
        void getAncestors_fallsBackToConfiguredBaseGraphWhenNoSnapshotPublished() {
            when(snapshotRepository.findActiveGraphName()).thenReturn(Optional.empty());
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));

            enabledService.getAncestors(PROJECT_ID, "REQ-001", 5);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate)
                    .query(sqlCaptor.capture(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
            // The fallback issues a real query against the base graph rather than returning empty.
            assertThat(sqlCaptor.getValue()).contains("cypher('test_graph'");
        }

        @Test
        void getAncestors_queriesGraph() throws SQLException {
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));
            // Feed a synthetic agtype string through the RowCallbackHandler
            // so the callback actually runs — without this, the mock keeps
            // the handler unexecuted and the test would pass even if
            // queryUids stopped reading column 1 or stopped calling
            // stringValue (test-quality review #906).
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString(1)).thenReturn("\"REQ-PARENT\"");
            org.mockito.Mockito.doAnswer(invocation -> {
                        RowCallbackHandler handler = invocation.getArgument(2);
                        handler.processRow(rs);
                        return null;
                    })
                    .when(jdbcTemplate)
                    .query(anyString(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));

            List<String> result = enabledService.getAncestors(PROJECT_ID, "REQ-001", 5);

            assertThat(result).containsExactly("REQ-PARENT");
            // setupSearchPath: LOAD + SET
            verify(jdbcTemplate, times(2)).execute(anyString());
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate)
                    .query(sqlCaptor.capture(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
            // Ancestor traversal uses outgoing PARENT edges (n-[:PARENT*..]->a). Pinning the
            // outgoing-arrow shape makes the assertion direction-specific, so a regression that
            // swapped getAncestors to the incoming-edge Cypher (used by getDescendants) would
            // fail here rather than silently pass on the looser "contains PARENT" check.
            assertThat(sqlCaptor.getValue()).contains("-[:PARENT*").doesNotContain("<-[:PARENT");
        }

        @Test
        void getDescendants_queriesGraph() throws SQLException {
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));
            // Same doAnswer pattern as getAncestors — exercise the
            // RowCallbackHandler so a regression in the descendant column
            // index or in stringValue surfaces here rather than at the
            // higher integration layer (test-quality review #906).
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString(1)).thenReturn("\"REQ-CHILD\"");
            org.mockito.Mockito.doAnswer(invocation -> {
                        RowCallbackHandler handler = invocation.getArgument(2);
                        handler.processRow(rs);
                        return null;
                    })
                    .when(jdbcTemplate)
                    .query(anyString(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));

            List<String> result = enabledService.getDescendants(PROJECT_ID, "REQ-001", 5);

            assertThat(result).containsExactly("REQ-CHILD");
            verify(jdbcTemplate, times(2)).execute(anyString());
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate)
                    .query(sqlCaptor.capture(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
            // Descendant traversal uses incoming PARENT edges (n<-[:PARENT*..]-d). The
            // incoming-arrow shape is unique to getDescendants and distinguishes it from
            // getAncestors; the prior "contains PARENT" assertion would silently pass if the
            // two Cypher generators were ever swapped.
            assertThat(sqlCaptor.getValue()).contains("<-[:PARENT");
        }

        @Test
        void findPaths_queriesGraphWithRelationships() throws SQLException {
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));
            // findPaths reads TWO columns per row: column 1 is
            // nodes(path) as an agtype vertex list, column 2 is
            // relationships(path) as an agtype edge list. Without this
            // pair feeding the callback, the test could not catch a
            // regression that swapped the two extract calls (test-quality
            // review #906).
            String nodesAgtype = "[{\"label\":\"REQUIREMENT\",\"properties\":{\"uid\":\"REQ-001\"}}::vertex, "
                    + "{\"label\":\"REQUIREMENT\",\"properties\":{\"uid\":\"REQ-002\"}}::vertex]";
            String edgesAgtype = "[{\"label\":\"PARENT\"}::edge]";
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString(1)).thenReturn(nodesAgtype);
            when(rs.getString(2)).thenReturn(edgesAgtype);
            org.mockito.Mockito.doAnswer(invocation -> {
                        RowCallbackHandler handler = invocation.getArgument(2);
                        handler.processRow(rs);
                        return null;
                    })
                    .when(jdbcTemplate)
                    .query(anyString(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));

            var result = enabledService.findPaths(PROJECT_ID, "REQ-001", "REQ-002");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).nodeUids()).containsExactly("REQ-001", "REQ-002");
            assertThat(result.get(0).edgeLabels()).containsExactly("PARENT");
            verify(jdbcTemplate, times(2)).execute(anyString());
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate)
                    .query(sqlCaptor.capture(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
            assertThat(sqlCaptor.getValue()).contains("relationships(path)");
            assertThat(sqlCaptor.getValue()).contains("nodes(path)");
        }
    }

    @Nested
    class Sanitization {

        private static final ObjectMapper TEST_OBJECT_MAPPER = new ObjectMapper();
        private AgeGraphService enabledService;

        @BeforeEach
        void setUp() {
            var enabledProperties = new AgeProperties(true, "test_graph");
            enabledService = new AgeGraphService(
                    jdbcTemplate,
                    enabledProperties,
                    graphProjectionRegistryService,
                    projectRepository,
                    snapshotRepository,
                    snapshotCleaner,
                    asOfRevisionResolver);
            // An active snapshot exists (reads resolve it) and publication gets version 1; lenient
            // so the subset of tests that exercise only one of read/materialize don't trip strict stubs.
            lenient().when(snapshotRepository.findActiveGraphName()).thenReturn(Optional.of("test_graph"));
            lenient().when(snapshotRepository.nextVersion()).thenReturn(1L);
            lenient().when(asOfRevisionResolver.currentRevision()).thenReturn(Optional.of(5));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> parseParams(String json) {
            try {
                return TEST_OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Drive a captured {@link PreparedStatementSetter} against a mock {@link PreparedStatement}
         * to extract the bound agtype payload. This is how the production code's bind path is
         * exercised in unit tests without a real database.
         */
        private static String capturedAgtypeParam(PreparedStatementSetter pss) {
            try {
                PreparedStatement mockPs = mock(PreparedStatement.class);
                pss.setValues(mockPs);
                ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
                verify(mockPs).setObject(eq(1), captor.capture());
                Object obj = captor.getValue();
                return obj instanceof PGobject pgo ? pgo.getValue() : (obj == null ? null : obj.toString());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        void getAncestors_userValuesAreParameterizedNotInlined() {
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));

            enabledService.getAncestors(PROJECT_ID, "REQ-001", 5);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<PreparedStatementSetter> pssCaptor = ArgumentCaptor.forClass(PreparedStatementSetter.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), pssCaptor.capture(), any(RowCallbackHandler.class));

            String sql = sqlCaptor.getValue();
            // User-controlled values must NOT appear in the SQL string (which AGE parses at SQL
            // parse time and which embeds the cypher template). They must appear in the bound
            // agtype params payload, set via the PreparedStatementSetter.
            assertThat(sql).doesNotContain("REQ-001").doesNotContain("test-project");
            String paramsJson = capturedAgtypeParam(pssCaptor.getValue());
            assertThat(paramsJson).contains("REQ-001").contains("test-project");
        }

        @Test
        void getDescendants_userValuesAreParameterizedNotInlined() {
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));

            enabledService.getDescendants(PROJECT_ID, "REQ-001", 5);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<PreparedStatementSetter> pssCaptor = ArgumentCaptor.forClass(PreparedStatementSetter.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), pssCaptor.capture(), any(RowCallbackHandler.class));

            String sql = sqlCaptor.getValue();
            assertThat(sql).doesNotContain("REQ-001").doesNotContain("test-project");
            String paramsJson = capturedAgtypeParam(pssCaptor.getValue());
            assertThat(paramsJson).contains("REQ-001").contains("test-project");
        }

        @Test
        void findPaths_userValuesAreParameterizedNotInlined() {
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));

            enabledService.findPaths(PROJECT_ID, "REQ-001", "REQ-002");

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<PreparedStatementSetter> pssCaptor = ArgumentCaptor.forClass(PreparedStatementSetter.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), pssCaptor.capture(), any(RowCallbackHandler.class));

            String sql = sqlCaptor.getValue();
            assertThat(sql).doesNotContain("REQ-001").doesNotContain("REQ-002").doesNotContain("test-project");
            String paramsJson = capturedAgtypeParam(pssCaptor.getValue());
            assertThat(paramsJson).contains("REQ-001").contains("REQ-002").contains("test-project");
        }

        @Test
        void getVisualization_filtersEntityTypesInCypherAndBindsAsParam() {
            // The entityTypes filter MUST land in AGE Cypher as a parameter-bound IN list — the
            // label string itself must never reach the SQL text — and the cap on the filtered
            // node/edge sets MUST be expressed as the LIMIT (MAX + 1) idiom so the AGE engine
            // stops materializing past the bound.
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));

            enabledService.getVisualization(
                    PROJECT_ID, java.util.Set.of(GraphEntityType.REQUIREMENT, GraphEntityType.OPERATIONAL_ASSET));

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<PreparedStatementSetter> pssCaptor = ArgumentCaptor.forClass(PreparedStatementSetter.class);
            verify(jdbcTemplate, times(2))
                    .query(sqlCaptor.capture(), pssCaptor.capture(), any(RowCallbackHandler.class));

            String nodeSql = sqlCaptor.getAllValues().get(0);
            String edgeSql = sqlCaptor.getAllValues().get(1);
            /*
             * Cypher shape: filter is expressed as a parameter-bound IN clause; the LIMIT is the
             * canonical MAX_PROJECTION_* + 1 cap. Caller-supplied entityType names must NOT be
             * inlined into the SQL text — they reach AGE through the bound agtype params payload.
             */
            assertThat(nodeSql)
                    .contains("WHERE n.entity_type IN $entity_types")
                    .contains("LIMIT "
                            + (com.keplerops.groundcontrol.domain.graph.GraphTraversalLimits.MAX_PROJECTION_NODES + 1))
                    .doesNotContain("REQUIREMENT")
                    .doesNotContain("OPERATIONAL_ASSET");
            assertThat(edgeSql)
                    .contains("WHERE s.entity_type IN $entity_types AND t.entity_type IN $entity_types")
                    .contains("LIMIT "
                            + (com.keplerops.groundcontrol.domain.graph.GraphTraversalLimits.MAX_PROJECTION_EDGES + 1))
                    .doesNotContain("REQUIREMENT")
                    .doesNotContain("OPERATIONAL_ASSET");
            String nodeParams = capturedAgtypeParam(pssCaptor.getAllValues().get(0));
            String edgeParams = capturedAgtypeParam(pssCaptor.getAllValues().get(1));
            assertThat(nodeParams).contains("REQUIREMENT").contains("OPERATIONAL_ASSET");
            assertThat(edgeParams).contains("REQUIREMENT").contains("OPERATIONAL_ASSET");
        }

        @Test
        void getVisualization_omitsFilterClauseWhenEntityTypesIsEmpty() {
            // No filter supplied → no WHERE clause; the LIMIT (MAX + 1) cap still applies because
            // it is the canonical adapter-level bound on database work, regardless of filtering.
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));

            enabledService.getVisualization(PROJECT_ID, java.util.Set.of());

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, times(2))
                    .query(sqlCaptor.capture(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));

            for (String sql : sqlCaptor.getAllValues()) {
                assertThat(sql).doesNotContain("WHERE n.entity_type").doesNotContain("$entity_types");
            }
            assertThat(sqlCaptor.getAllValues().get(0))
                    .contains("LIMIT "
                            + (com.keplerops.groundcontrol.domain.graph.GraphTraversalLimits.MAX_PROJECTION_NODES + 1));
            assertThat(sqlCaptor.getAllValues().get(1))
                    .contains("LIMIT "
                            + (com.keplerops.groundcontrol.domain.graph.GraphTraversalLimits.MAX_PROJECTION_EDGES + 1));
        }

        @Test
        void getVisualization_rejectsWhenNodeCapExceeded() throws SQLException {
            // Simulate AGE returning MAX_PROJECTION_NODES + 1 vertex rows: that +1 row signals
            // overflow at the adapter, and the service must convert it into a 422 envelope rather
            // than serializing the bounded-but-partial result.
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));
            int rowsToReturn = com.keplerops.groundcontrol.domain.graph.GraphTraversalLimits.MAX_PROJECTION_NODES + 1;
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString(1))
                    .thenReturn("{\"id\": \"REQUIREMENT:x\", \"domain_id\": \"x\", \"entity_type\": \"REQUIREMENT\", "
                            + "\"project_identifier\": \"p\", \"uid\": \"U\", \"label\": \"L\"}");
            org.mockito.Mockito.doAnswer(invocation -> {
                        RowCallbackHandler handler = invocation.getArgument(2);
                        for (int i = 0; i < rowsToReturn; i++) {
                            handler.processRow(rs);
                        }
                        return null;
                    })
                    .when(jdbcTemplate)
                    .query(anyString(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
            var emptyFilter = java.util.Set.<GraphEntityType>of();

            assertThatThrownBy(() -> enabledService.getVisualization(PROJECT_ID, emptyFilter))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("projection node count");
        }

        @Test
        void getVisualization_userValuesAreParameterizedNotInlined() {
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));

            enabledService.getVisualization(PROJECT_ID, java.util.Set.of());

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<PreparedStatementSetter> pssCaptor = ArgumentCaptor.forClass(PreparedStatementSetter.class);
            verify(jdbcTemplate, times(2))
                    .query(sqlCaptor.capture(), pssCaptor.capture(), any(RowCallbackHandler.class));

            for (int i = 0; i < sqlCaptor.getAllValues().size(); i++) {
                String sql = sqlCaptor.getAllValues().get(i);
                assertThat(sql).doesNotContain("test-project");
                String paramsJson = capturedAgtypeParam(pssCaptor.getAllValues().get(i));
                assertThat(paramsJson).contains("test-project");
            }
        }

        @Test
        void materializeGraph_freeFormPropertyValuesAreParameterizedNotInlined() {
            // Adversarial title with $gc$ delimiter, single quotes, backslashes, and SQL keywords.
            // None of these may appear in the SQL string sent to JdbcTemplate; they must appear
            // only in the bound agtype params payload (round-tripped through Jackson, since the
            // JSON encoding escapes backslashes/quotes).
            String adversarialTitle = "Evil $gc$); DROP TABLE requirement; --";
            String adversarialStatement = "Stmt with 'quotes' and \\backslashes\\ and $$delimiters$$";
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("title", adversarialTitle);
            properties.put("statement", adversarialStatement);

            var node = new GraphNode(
                    "REQUIREMENT:" + UUID.randomUUID(),
                    UUID.randomUUID().toString(),
                    GraphEntityType.REQUIREMENT,
                    TEST_PROJECT.getIdentifier(),
                    "GC-A001",
                    "GC-A001",
                    properties);
            when(graphProjectionRegistryService.buildProjection())
                    .thenReturn(new GraphProjection(List.of(node), List.of()));

            enabledService.materializeGraph();

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<PreparedStatementSetter> pssCaptor = ArgumentCaptor.forClass(PreparedStatementSetter.class);
            verify(jdbcTemplate, atLeast(1))
                    .query(sqlCaptor.capture(), pssCaptor.capture(), any(RowCallbackHandler.class));

            boolean foundCreate = false;
            for (int i = 0; i < sqlCaptor.getAllValues().size(); i++) {
                String sql = sqlCaptor.getAllValues().get(i);
                if (sql.contains("CREATE (:")) {
                    foundCreate = true;
                    assertThat(sql).doesNotContain(adversarialTitle);
                    assertThat(sql).doesNotContain(adversarialStatement);
                    assertThat(sql).doesNotContain("DROP TABLE");
                    String paramsJson =
                            capturedAgtypeParam(pssCaptor.getAllValues().get(i));
                    Map<String, Object> params = parseParams(paramsJson);
                    assertThat(params).containsValue(adversarialTitle).containsValue(adversarialStatement);
                }
            }
            assertThat(foundCreate)
                    .as("CREATE statement should be issued for the requirement")
                    .isTrue();
        }

        @Test
        void materializeGraph_edgePropertyValuesAreParameterizedNotInlined() {
            String adversarialSourceUid = "REQ-EVIL$gc$);DROP--";
            var edge = new GraphEdge(
                    UUID.randomUUID().toString(),
                    "PARENT",
                    "REQUIREMENT:" + UUID.randomUUID(),
                    "REQUIREMENT:" + UUID.randomUUID(),
                    GraphEntityType.REQUIREMENT,
                    GraphEntityType.REQUIREMENT,
                    Map.of("sourceUid", adversarialSourceUid));
            when(graphProjectionRegistryService.buildProjection())
                    .thenReturn(new GraphProjection(List.of(), List.of(edge)));

            enabledService.materializeGraph();

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<PreparedStatementSetter> pssCaptor = ArgumentCaptor.forClass(PreparedStatementSetter.class);
            verify(jdbcTemplate, atLeast(1))
                    .query(sqlCaptor.capture(), pssCaptor.capture(), any(RowCallbackHandler.class));

            boolean foundEdgeCreate = false;
            for (int i = 0; i < sqlCaptor.getAllValues().size(); i++) {
                String sql = sqlCaptor.getAllValues().get(i);
                if (sql.contains("MATCH") && sql.contains("CREATE")) {
                    foundEdgeCreate = true;
                    assertThat(sql).doesNotContain(adversarialSourceUid);
                    String paramsJson =
                            capturedAgtypeParam(pssCaptor.getAllValues().get(i));
                    Map<String, Object> params = parseParams(paramsJson);
                    assertThat(params).containsValue(adversarialSourceUid);
                }
            }
            assertThat(foundEdgeCreate)
                    .as("MATCH/CREATE edge statement should be issued")
                    .isTrue();
        }

        @Test
        void validateUid_rejectsBlankInput() {
            assertThatThrownBy(() -> enabledService.getAncestors(PROJECT_ID, "", 5))
                    .isInstanceOf(DomainValidationException.class);
        }

        @Test
        void validateUid_rejectsControlCharacters() {
            assertThatThrownBy(() -> enabledService.getAncestors(PROJECT_ID, "REQ\n001", 5))
                    .isInstanceOf(DomainValidationException.class);
        }

        @Test
        void validateUid_rejectsOverlongValues() {
            String overlong = "X".repeat(51);
            assertThatThrownBy(() -> enabledService.getAncestors(PROJECT_ID, overlong, 5))
                    .isInstanceOf(DomainValidationException.class);
        }

        @Test
        void getAncestors_acceptsAdversarialUidThroughParameterization() {
            // UIDs containing $gc$, single quotes, and backslashes are no longer rejected at
            // the AGE-adapter validator — parameter binding makes them structurally safe — but
            // they MUST still be bound through the params payload, never inlined into the SQL.
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));
            String adversarialUid = "REQ$gc$';DROP";

            enabledService.getAncestors(PROJECT_ID, adversarialUid, 5);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<PreparedStatementSetter> pssCaptor = ArgumentCaptor.forClass(PreparedStatementSetter.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), pssCaptor.capture(), any(RowCallbackHandler.class));
            assertThat(sqlCaptor.getValue()).doesNotContain(adversarialUid);
            String paramsJson = capturedAgtypeParam(pssCaptor.getValue());
            Map<String, Object> params = parseParams(paramsJson);
            assertThat(params).containsEntry("uid", adversarialUid);
        }

        @Test
        void validateGraphName_rejectsPayloadsContainingDollarSigns() {
            var dangerousProps = new AgeProperties(true, "graph$gc$");
            var dangerousService = new AgeGraphService(
                    jdbcTemplate,
                    dangerousProps,
                    graphProjectionRegistryService,
                    projectRepository,
                    snapshotRepository,
                    snapshotCleaner,
                    asOfRevisionResolver);
            // No buildProjection stub: validateGraphName fails before the projection is read.

            assertThatThrownBy(dangerousService::materializeGraph).isInstanceOf(DomainValidationException.class);
        }

        @Test
        void getAncestors_rejectsDepthBelowMin() {
            assertThatThrownBy(() -> enabledService.getAncestors(PROJECT_ID, "REQ-001", 0))
                    .isInstanceOf(DomainValidationException.class);
        }

        @Test
        void getAncestors_rejectsDepthAboveMax() {
            // MAX_GRAPH_TRAVERSAL_DEPTH = 20; anything above must be rejected before the
            // variable-length-path bound is interpolated.
            assertThatThrownBy(() -> enabledService.getAncestors(PROJECT_ID, "REQ-001", 21))
                    .isInstanceOf(DomainValidationException.class);
        }

        @Test
        void getAncestors_rejectsNegativeDepth() {
            assertThatThrownBy(() -> enabledService.getAncestors(PROJECT_ID, "REQ-001", -1))
                    .isInstanceOf(DomainValidationException.class);
        }

        @Test
        void getDescendants_rejectsDepthBelowMin() {
            assertThatThrownBy(() -> enabledService.getDescendants(PROJECT_ID, "REQ-001", 0))
                    .isInstanceOf(DomainValidationException.class);
        }

        @Test
        void getDescendants_rejectsDepthAboveMax() {
            assertThatThrownBy(() -> enabledService.getDescendants(PROJECT_ID, "REQ-001", 21))
                    .isInstanceOf(DomainValidationException.class);
        }

        @Test
        void findPaths_cypherIncludesHardDepthCap() {
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));

            enabledService.findPaths(PROJECT_ID, "REQ-001", "REQ-002");

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate)
                    .query(sqlCaptor.capture(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
            // Confirm the variable-length path is bounded; an unbounded `[*]->` would let a single
            // findPaths call enumerate every path in a cyclic graph.
            assertThat(sqlCaptor.getValue()).contains("[*1..20]");
            assertThat(sqlCaptor.getValue()).doesNotContain("[*]->");
        }

        @Test
        void findPaths_cypherIncludesResultLimitInsideCypherBlock() {
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(TEST_PROJECT));

            enabledService.findPaths(PROJECT_ID, "REQ-001", "REQ-002");

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate)
                    .query(sqlCaptor.capture(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
            // The LIMIT must live inside the $gc$...$gc$ Cypher block so AGE itself bounds path
            // enumeration. An outer-SQL LIMIT only truncates rows after AGE materializes them,
            // which doesn't bound expansion on a cyclic graph.
            String sql = sqlCaptor.getValue();
            int gcStart = sql.indexOf("$gc$");
            int gcEnd = sql.lastIndexOf("$gc$");
            assertThat(gcStart).isPositive();
            assertThat(gcEnd).isGreaterThan(gcStart);
            String cypherBlock = sql.substring(gcStart, gcEnd);
            assertThat(cypherBlock).contains("LIMIT 50");
        }
    }

    @Nested
    class PropertyKeyRegistry {

        private AgeGraphService enabledService;

        @BeforeEach
        void setUp() {
            var enabledProperties = new AgeProperties(true, "test_graph");
            enabledService = new AgeGraphService(
                    jdbcTemplate,
                    enabledProperties,
                    graphProjectionRegistryService,
                    projectRepository,
                    snapshotRepository,
                    snapshotCleaner,
                    asOfRevisionResolver);
            // An active snapshot exists (reads resolve it) and publication gets version 1; lenient
            // so the subset of tests that exercise only one of read/materialize don't trip strict stubs.
            lenient().when(snapshotRepository.findActiveGraphName()).thenReturn(Optional.of("test_graph"));
            lenient().when(snapshotRepository.nextVersion()).thenReturn(1L);
            lenient().when(asOfRevisionResolver.currentRevision()).thenReturn(Optional.of(5));
        }

        @Test
        void materializeGraph_rejectsUnknownPropertyKey() {
            // Per ADR-032, AGE property keys must come from APPROVED_PROPERTY_KEYS — a future
            // contributor cannot silently introduce a new key just by satisfying the syntactic
            // allowlist.
            var node = new GraphNode(
                    "REQUIREMENT:" + UUID.randomUUID(),
                    UUID.randomUUID().toString(),
                    GraphEntityType.REQUIREMENT,
                    "test-project",
                    "GC-A001",
                    "GC-A001",
                    Map.of("unknown_property_not_in_registry", "value"));
            when(graphProjectionRegistryService.buildProjection())
                    .thenReturn(new GraphProjection(List.of(node), List.of()));

            assertThatThrownBy(enabledService::materializeGraph)
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("not in approved AGE schema registry");
        }

        @Test
        void approvedPropertyKeysSetIsImmutable() {
            // Defense in depth: the registry must be a Set.of(...) that throws on mutation,
            // not a mutable HashSet a future caller could grow at runtime.
            assertThatThrownBy(() -> AgeGraphService.APPROVED_PROPERTY_KEYS.add("evil"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        /**
         * GC-M018: AssetGraphProjectionContributor emits knowledgeState on
         * OPERATIONAL_ASSET nodes AND on AssetRelation edges. Both emissions
         * flow through validatePropertyKey, so if the key ever drops out of
         * the registry, AGE materialization throws on any partial-knowledge
         * asset or unknown-dependency edge. Pins both emission sites at
         * once: the registry key drives both, so a single approved-set
         * assertion guards the whole class shape.
         */
        @Test
        void approvedPropertyKeysIncludesKnowledgeStateForAssetNodeAndRelationEdge() {
            assertThat(AgeGraphService.APPROVED_PROPERTY_KEYS).contains("knowledgeState");
        }
    }

    @Nested
    class AgtypeParsing {

        @Test
        void stripAgtypeTypeTags_stripsTagsInStructuralPositions() {
            String input = "[{\"id\": 1}::vertex, {\"id\": 2}::vertex]";
            assertThat(AgeGraphService.stripAgtypeTypeTags(input)).isEqualTo("[{\"id\": 1}, {\"id\": 2}]");
        }

        @Test
        void stripAgtypeTypeTags_leavesTagsInsideStringsAlone() {
            // A user-controlled property value containing the literal "}::vertex" must NOT be
            // mutated. Without quote-aware processing, a naive replace would corrupt the value.
            String input = "{\"properties\": {\"title\": \"Evil }::vertex marker\"}}::vertex";
            String expected = "{\"properties\": {\"title\": \"Evil }::vertex marker\"}}";
            assertThat(AgeGraphService.stripAgtypeTypeTags(input)).isEqualTo(expected);
        }

        @Test
        void stripAgtypeTypeTags_handlesEscapedQuotesInsideStrings() {
            // A string value containing an escaped quote followed by }::vertex must not split
            // the string-state tracking.
            String input = "{\"title\": \"weird \\\"quoted\\\" }::vertex stuff\"}::vertex";
            String expected = "{\"title\": \"weird \\\"quoted\\\" }::vertex stuff\"}";
            assertThat(AgeGraphService.stripAgtypeTypeTags(input)).isEqualTo(expected);
        }

        @Test
        void stripAgtypeTypeTags_handlesEdgeAndPathTags() {
            String input = "[{\"label\": \"R\"}::edge, {\"length\": 2}::path]";
            assertThat(AgeGraphService.stripAgtypeTypeTags(input)).isEqualTo("[{\"label\": \"R\"}, {\"length\": 2}]");
        }

        @Test
        void extractPathNodeUids_pullsUidFromVertexProperties() {
            String agtype = "[{\"id\": 1, \"label\": \"REQUIREMENT\", \"properties\": {\"uid\": \"REQ-1\"}}::vertex,"
                    + " {\"id\": 2, \"label\": \"REQUIREMENT\", \"properties\": {\"uid\": \"REQ-2\"}}::vertex]";
            assertThat(AgeGraphService.extractPathNodeUids(agtype)).containsExactly("REQ-1", "REQ-2");
        }

        @Test
        void extractPathNodeUids_returnsEmptyForNonListInput() {
            assertThat(AgeGraphService.extractPathNodeUids("\"not a list\"")).isEmpty();
        }

        @Test
        void extractPathNodeUids_skipsVerticesWithoutPropertiesOrUid() {
            String agtype = "[{\"id\": 1}::vertex,"
                    + " {\"id\": 2, \"properties\": {}}::vertex,"
                    + " {\"id\": 3, \"properties\": {\"uid\": \"REQ-3\"}}::vertex]";
            assertThat(AgeGraphService.extractPathNodeUids(agtype)).containsExactly("REQ-3");
        }

        @Test
        void extractPathEdgeLabels_pullsLabelFromEdges() {
            String agtype =
                    "[{\"id\": 10, \"label\": \"PARENT\"}::edge, {\"id\": 11, \"label\": \"DEPENDS_ON\"}::edge]";
            assertThat(AgeGraphService.extractPathEdgeLabels(agtype)).containsExactly("PARENT", "DEPENDS_ON");
        }

        @Test
        void extractPathEdgeLabels_returnsEmptyForNonListInput() {
            assertThat(AgeGraphService.extractPathEdgeLabels("{}")).isEmpty();
        }

        @Test
        void extractPathEdgeLabels_skipsEntriesWithoutLabel() {
            String agtype = "[{\"id\": 10}::edge, {\"id\": 11, \"label\": \"PARENT\"}::edge]";
            assertThat(AgeGraphService.extractPathEdgeLabels(agtype)).containsExactly("PARENT");
        }
    }

    // GC-G007 / ADR-032 regression: every property key emitted by
    // DocumentGraphProjectionContributor must appear in APPROVED_PROPERTY_KEYS.
    // If any key is missing, AGE materialization throws DomainValidationException at
    // write time. This test pins the invariant so a future edit to the contributor
    // cannot silently introduce an unapproved key.
    @Nested
    class DocumentContributorPropertyKeyRegression {

        @Test
        void titleIsApproved() {
            assertThat(AgeGraphService.APPROVED_PROPERTY_KEYS).contains("title");
        }

        @Test
        void versionIsApproved() {
            assertThat(AgeGraphService.APPROVED_PROPERTY_KEYS).contains("version");
        }

        @Test
        void descriptionIsApproved() {
            assertThat(AgeGraphService.APPROVED_PROPERTY_KEYS).contains("description");
        }

        @Test
        void createdByIsApproved() {
            assertThat(AgeGraphService.APPROVED_PROPERTY_KEYS).contains("createdBy");
        }

        @Test
        void createdAtIsApproved() {
            assertThat(AgeGraphService.APPROVED_PROPERTY_KEYS).contains("createdAt");
        }

        @Test
        void updatedAtIsApproved() {
            assertThat(AgeGraphService.APPROVED_PROPERTY_KEYS).contains("updatedAt");
        }
    }

    // ADR-070 / #1003 regression: every property key emitted by
    // ResearchGraphProjectionContributor must appear in APPROVED_PROPERTY_KEYS,
    // or AGE materialization throws DomainValidationException at write time. This
    // pins the bounded-property contract so a future contributor edit cannot
    // silently widen the AGE schema (e.g. by emitting summary or locator).
    @Nested
    class ResearchContributorPropertyKeyRegression {

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(
                strings = {
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
                    "status"
                })
        void researchProjectionKeyIsApproved(String key) {
            var approvedKeys = AgeGraphService.APPROVED_PROPERTY_KEYS;
            assertThat(approvedKeys).contains(key);
        }
    }
}

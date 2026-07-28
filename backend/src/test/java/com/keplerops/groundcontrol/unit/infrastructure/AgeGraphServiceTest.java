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
import java.sql.SQLException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Split from AgeGraphServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
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
}

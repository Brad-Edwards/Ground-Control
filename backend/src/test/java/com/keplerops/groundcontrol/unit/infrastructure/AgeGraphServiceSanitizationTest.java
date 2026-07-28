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

/** Split from AgeGraphServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class AgeGraphServiceSanitizationTest {
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
    }
}

package com.keplerops.groundcontrol.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
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
class AgeGraphServiceSanitization2Test {
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

    @Nested
    class WorkflowContributorPropertyKeyRegression {

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(
                strings = {
                    "repo",
                    "issueNumber",
                    "workflowType",
                    "runtimeDriver",
                    "finalState",
                    "outcome",
                    "provenance",
                    "startedAt",
                    "endedAt",
                    "phase",
                    "eventType",
                    "cycleIndex",
                    "occurredAt",
                    "durationMs"
                })
        void workflowProjectionKeyIsApproved(String key) {
            assertThat(key).isIn(AgeGraphService.APPROVED_PROPERTY_KEYS);
        }
    }
}

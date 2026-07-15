package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.graph.service.MixedGraphClient;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.GraphClient;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AgeGraphServiceIntegrationTest extends BaseAgeIntegrationTest {

    @Autowired
    private GraphClient graphClient;

    @Autowired
    private MixedGraphClient mixedGraphClient;

    @Autowired
    private RequirementRepository requirementRepository;

    @Autowired
    private RequirementRelationRepository relationRepository;

    @Autowired
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Project testProject;

    @BeforeEach
    void setUp() {
        testProject = projectRepository.findByIdentifier("ground-control").orElseThrow();
    }

    @Test
    void materializeAndQueryAncestors() {
        var grandparent =
                requirementRepository.save(new Requirement(testProject, "AGE-GP", "Grandparent", "GP statement"));
        var parent = requirementRepository.save(new Requirement(testProject, "AGE-P", "Parent", "P statement"));
        var child = requirementRepository.save(new Requirement(testProject, "AGE-C", "Child", "C statement"));

        relationRepository.save(new RequirementRelation(parent, grandparent, RelationType.PARENT));
        relationRepository.save(new RequirementRelation(child, parent, RelationType.PARENT));

        graphClient.materializeGraph();

        var ancestors = graphClient.getAncestors(testProject.getId(), "AGE-C", 10);
        assertThat(ancestors).contains("AGE-P", "AGE-GP");
    }

    @Test
    void materializeAndQueryDescendants() {
        var root = requirementRepository.save(new Requirement(testProject, "AGE-ROOT", "Root", "Root statement"));
        var leaf = requirementRepository.save(new Requirement(testProject, "AGE-LEAF", "Leaf", "Leaf statement"));

        relationRepository.save(new RequirementRelation(leaf, root, RelationType.PARENT));

        graphClient.materializeGraph();

        var descendants = graphClient.getDescendants(testProject.getId(), "AGE-ROOT", 10);
        assertThat(descendants).contains("AGE-LEAF");
    }

    @Test
    void getVisualization_filtersByEntityTypeAgainstRealAge() {
        // Round-trip the entityTypes filter through real AGE 1.6. To prove the filter is actually
        // applied at the database layer (not just a no-op that happens to return the same set as
        // the unfiltered call), materialize a REQUIREMENT then call getVisualization with a
        // filter to OPERATIONAL_ASSET — a *different* entity type. If the AGE-side WHERE clause
        // is broken, the REQUIREMENT would still appear in the result; with the filter working,
        // the REQUIREMENT must be excluded and every returned node (if any) must be an
        // OPERATIONAL_ASSET.
        requirementRepository.save(new Requirement(testProject, "AGE-FILT-A", "Filter A", "stmt"));
        graphClient.materializeGraph();

        var filtered = mixedGraphClient.getVisualization(
                testProject.getId(),
                java.util.Set.of(com.keplerops.groundcontrol.domain.graph.model.GraphEntityType.OPERATIONAL_ASSET));

        assertThat(filtered.nodes())
                .as("AGE-side filter must exclude REQUIREMENT-typed nodes when filter is OPERATIONAL_ASSET")
                .noneMatch(n -> "AGE-FILT-A".equals(n.uid()))
                .allMatch(n -> n.entityType()
                        == com.keplerops.groundcontrol.domain.graph.model.GraphEntityType.OPERATIONAL_ASSET);

        // Sanity check: the unfiltered call MUST include the requirement, so the regression check
        // above is asymmetric and meaningful (it isn't just "result is empty in both cases").
        var unfiltered = mixedGraphClient.getVisualization(testProject.getId(), java.util.Set.of());
        assertThat(unfiltered.nodes())
                .as("control: unfiltered visualization includes the seeded REQUIREMENT")
                .anyMatch(n -> "AGE-FILT-A".equals(n.uid()));
    }

    @Test
    void materializeAndFindPaths() {
        var a = requirementRepository.save(new Requirement(testProject, "AGE-A", "A", "A statement"));
        var b = requirementRepository.save(new Requirement(testProject, "AGE-B", "B", "B statement"));
        var c = requirementRepository.save(new Requirement(testProject, "AGE-C2", "C", "C statement"));

        relationRepository.save(new RequirementRelation(a, b, RelationType.DEPENDS_ON));
        relationRepository.save(new RequirementRelation(b, c, RelationType.DEPENDS_ON));

        graphClient.materializeGraph();

        var paths = graphClient.findPaths(testProject.getId(), "AGE-A", "AGE-C2");
        assertThat(paths).isNotEmpty();
        var firstPath = paths.get(0);
        assertThat(firstPath.nodeUids()).containsExactly("AGE-A", "AGE-B", "AGE-C2");
        assertThat(firstPath.edgeLabels()).containsExactly("DEPENDS_ON", "DEPENDS_ON");
    }

    @Test
    void materializeGraph_handlesAdversarialTitleAndStatement() {
        // Free-form fields cannot be allowlisted at the AGE adapter — they accept arbitrary user
        // text. The adapter must bind them as Cypher parameters so they cannot influence query
        // structure even with $gc$, $$, single quotes, backslashes, or SQL keywords embedded.
        String adversarialTitle = "Evil $gc$); DROP TABLE requirement; --";
        String adversarialStatement = "Stmt with 'quotes' and \\backslashes\\ and $$delimiters$$";
        String requirementCountSql = "SELECT COUNT(*) FROM requirement";
        Long beforeCount = jdbcTemplate.queryForObject(requirementCountSql, Long.class);

        var req = requirementRepository.save(
                new Requirement(testProject, "AGE-EVIL", adversarialTitle, adversarialStatement));

        graphClient.materializeGraph();

        // The requirement table is intact (the DROP TABLE in the title was stored, not executed).
        Long afterCount = jdbcTemplate.queryForObject(requirementCountSql, Long.class);
        assertThat(afterCount).isEqualTo(beforeCount + 1);

        // The materialized graph round-trips the malicious values verbatim as property data.
        var projection = mixedGraphClient.getVisualization(testProject.getId(), java.util.Set.of());
        var matched = projection.nodes().stream()
                .filter(n -> "AGE-EVIL".equals(n.uid()))
                .findFirst();
        assertThat(matched).isPresent();
        assertThat(matched.get().properties())
                .containsEntry("title", adversarialTitle)
                .containsEntry("statement", adversarialStatement);

        // Quick sanity-check: the requirement is still readable through normal JPA after materialization.
        var reloaded = requirementRepository.findById(req.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo(adversarialTitle);
        assertThat(reloaded.getStatement()).isEqualTo(adversarialStatement);
    }

    @Test
    void materializeGraphProjectsTraceabilityEdgeToBoundArtifactReference() {
        String adversarialIdentifier = "src/$gc$/'quoted'/}::vertex/Trace.java";
        var requirement = requirementRepository.save(
                new Requirement(testProject, "AGE-TRACE", "Traceability", "Traceability must be traversable"));
        var link = traceabilityLinkRepository.save(
                new TraceabilityLink(requirement, ArtifactType.CODE_FILE, adversarialIdentifier, LinkType.IMPLEMENTS));

        graphClient.materializeGraph();

        var projection = mixedGraphClient.getVisualization(testProject.getId(), java.util.Set.of());
        var requirementNode = projection.nodes().stream()
                .filter(node -> "AGE-TRACE".equals(node.uid()))
                .findFirst()
                .orElseThrow();
        var artifactNode = projection.nodes().stream()
                .filter(node -> adversarialIdentifier.equals(node.uid()))
                .findFirst()
                .orElseThrow();
        assertThat(artifactNode.entityType().name()).isEqualTo("ARTIFACT_REFERENCE");
        assertThat(artifactNode.properties())
                .containsEntry("artifactType", "CODE_FILE")
                .containsEntry("artifactIdentifier", adversarialIdentifier);
        assertThat(projection.edges())
                .filteredOn(edge -> edge.id().equals(link.getId().toString()))
                .singleElement()
                .satisfies(edge -> {
                    assertThat(edge.edgeType()).isEqualTo("IMPLEMENTS");
                    assertThat(edge.sourceId()).isEqualTo(requirementNode.id());
                    assertThat(edge.targetId()).isEqualTo(artifactNode.id());
                });
    }

    @Test
    void materializeGraphOmitsArchivedRequirementTraceability() {
        var requirement = requirementRepository.save(
                new Requirement(testProject, "AGE-TRACE-OLD", "Archived trace", "Archived requirements stay out"));
        var link = traceabilityLinkRepository.save(
                new TraceabilityLink(requirement, ArtifactType.CODE_FILE, "src/retired.java", LinkType.IMPLEMENTS));
        requirement.transitionStatus(Status.DEPRECATED);
        requirement.archive();
        requirementRepository.save(requirement);

        graphClient.materializeGraph();

        var projection = mixedGraphClient.getVisualization(testProject.getId(), java.util.Set.of());
        assertThat(projection.nodes()).noneMatch(node -> "AGE-TRACE-OLD".equals(node.uid()));
        assertThat(projection.edges())
                .noneMatch(edge -> edge.id().equals(link.getId().toString()));
    }

    @Test
    void getAncestors_acceptsAdversarialUidWithoutInjection() {
        // The adapter-level UID validator no longer rejects shapes like $gc$, single quotes, or
        // backslashes — those are bound through the agtype params payload and cannot affect
        // query structure. Verify the call returns cleanly (empty result, no SQL error).
        var ancestors = graphClient.getAncestors(testProject.getId(), "REQ-$gc$);DROP--", 5);
        assertThat(ancestors).isEmpty();
    }

    @Test
    void getDescendants_acceptsAdversarialUidWithoutInjection() {
        var descendants = graphClient.getDescendants(testProject.getId(), "REQ-'OR'1'='1", 5);
        assertThat(descendants).isEmpty();
    }

    @Test
    void findPaths_acceptsAdversarialUidWithoutInjection() {
        var paths = graphClient.findPaths(testProject.getId(), "REQ-001", "REQ-\\evil");
        assertThat(paths).isEmpty();
    }

    @Test
    void getAncestors_stillRejectsControlCharactersInUid() {
        // Control characters are not an injection vector under parameter binding, but they
        // would corrupt logs and confuse operators; the adapter still rejects them as an
        // operational sanity check.
        var projectId = testProject.getId();
        assertThatThrownBy(() -> graphClient.getAncestors(projectId, "REQ\n001", 5))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void getAncestors_rejectsOutOfRangeDepth() {
        var projectId = testProject.getId();
        assertThatThrownBy(() -> graphClient.getAncestors(projectId, "AGE-C", 0))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> graphClient.getAncestors(projectId, "AGE-C", 999))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void materializeGraph_preservesInstantPropertiesAsIsoStrings() {
        // RequirementRelation projection contributes a `createdAt` Instant property. Without an
        // explicit Jackson configuration, the JSON-bound params would serialize Instants as
        // numeric timestamps, silently changing the API shape of getVisualization() responses
        // when AGE is enabled. Verify the round-trip preserves an ISO-8601 string.
        var src = requirementRepository.save(new Requirement(testProject, "AGE-INST-S", "Src", "S statement"));
        var tgt = requirementRepository.save(new Requirement(testProject, "AGE-INST-T", "Tgt", "T statement"));
        relationRepository.save(new RequirementRelation(src, tgt, RelationType.DEPENDS_ON));

        graphClient.materializeGraph();

        var projection = mixedGraphClient.getVisualization(testProject.getId(), java.util.Set.of());
        var matchedEdge = projection.edges().stream()
                .filter(e -> "DEPENDS_ON".equals(e.edgeType()))
                .findFirst();
        assertThat(matchedEdge).isPresent();
        Object createdAt = matchedEdge.get().properties().get("createdAt");
        // ISO-8601 starts with a 4-digit year; numeric epoch seconds would not.
        assertThat(createdAt)
                .as("createdAt must round-trip as an ISO-8601 string, not a numeric timestamp")
                .isInstanceOf(String.class);
        assertThat((String) createdAt).matches("^\\d{4}-\\d{2}-\\d{2}T.*");
    }

    @Test
    void materializeGraph_preservesAgtypeTypeTagSequencesInsideTitleVerbatim() {
        // Defense against the regression where stripAgtypeTypeTags() naively rewrote
        // }::vertex inside user-controlled string properties. Persist a requirement whose
        // title literally contains the AGE type-tag suffixes, materialize, round-trip
        // through getVisualization, and confirm the title comes back verbatim.
        String trickyTitle = "edge case }::vertex and }::edge inside title }::path";
        var req = requirementRepository.save(new Requirement(testProject, "AGE-TAG", trickyTitle, "stmt"));

        graphClient.materializeGraph();

        var projection = mixedGraphClient.getVisualization(testProject.getId(), java.util.Set.of());
        var matched = projection.nodes().stream()
                .filter(n -> "AGE-TAG".equals(n.uid()))
                .findFirst();
        assertThat(matched).isPresent();
        assertThat(matched.get().properties()).containsEntry("title", trickyTitle);

        // Sanity: the JPA row also has the original title (no DB-side corruption).
        var reloaded = requirementRepository.findById(req.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo(trickyTitle);
    }
}

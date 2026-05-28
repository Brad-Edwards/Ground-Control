package com.keplerops.groundcontrol.unit.domain.graph;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.documents.model.Document;
import com.keplerops.groundcontrol.domain.documents.repository.DocumentRepository;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.service.DocumentGraphProjectionContributor;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentGraphProjectionContributorTest {

    @Mock
    private DocumentRepository repository;

    @InjectMocks
    private DocumentGraphProjectionContributor contributor;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    @Test
    void emitsOneNodePerDocumentForProject() {
        var doc1 = buildDocument("SRS", "1.0.0");
        var doc2 = buildDocument("SAD", "2.0.0");
        setField(doc1, "id", UUID.randomUUID());
        setField(doc2, "id", UUID.randomUUID());
        setField(doc1, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        setField(doc2, "createdAt", Instant.parse("2026-01-02T00:00:00Z"));
        when(repository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(doc1, doc2));

        var nodes = contributor.contributeNodes(projectId);

        assertThat(nodes).hasSize(2);
        assertThat(nodes).allMatch(n -> n.entityType() == GraphEntityType.DOCUMENT);
    }

    @Test
    void nodePropertiesIncludeRequiredFields() {
        var docId = UUID.randomUUID();
        var doc = buildDocument("SRS", "1.0.0");
        setField(doc, "id", docId);
        setField(doc, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(doc));

        var nodes = contributor.contributeNodes(projectId);

        assertThat(nodes).hasSize(1);
        var node = nodes.get(0);
        assertThat(node.id()).isEqualTo(GraphIds.nodeId(GraphEntityType.DOCUMENT, docId));
        assertThat(node.label()).isEqualTo("SRS");
        assertThat(node.entityType()).isEqualTo(GraphEntityType.DOCUMENT);
        assertThat(node.properties()).containsKey("title");
        assertThat(node.properties()).containsEntry("title", "SRS");
        assertThat(node.properties()).containsEntry("version", "1.0.0");
        assertThat(node.properties()).containsKey("createdAt");
    }

    @Test
    void nodePropertiesIncludeNullableFieldsWhenPresent() {
        var docId = UUID.randomUUID();
        var doc = new Document(project, "SRS", "1.0.0", "A description", "alice");
        setField(doc, "id", docId);
        setField(doc, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        setField(doc, "updatedAt", Instant.parse("2026-01-02T00:00:00Z"));
        when(repository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(doc));

        var nodes = contributor.contributeNodes(projectId);

        var props = nodes.get(0).properties();
        assertThat(props).containsKey("description");
        assertThat(props).containsKey("createdBy");
        assertThat(props).containsKey("updatedAt");
    }

    @Test
    void nodePropertiesOmitNullableFieldsWhenAbsent() {
        var docId = UUID.randomUUID();
        var doc = new Document(project, "SRS", "1.0.0", null, null);
        setField(doc, "id", docId);
        setField(doc, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(doc));

        var nodes = contributor.contributeNodes(projectId);

        var props = nodes.get(0).properties();
        assertThat(props).doesNotContainKey("description");
        assertThat(props).doesNotContainKey("createdBy");
    }

    @Test
    void nodePropertiesDoNotIncludeGrammar() {
        var docId = UUID.randomUUID();
        var doc = new Document(project, "SRS", "1.0.0", null, null);
        doc.setGrammar("{\"fields\":[]}");
        setField(doc, "id", docId);
        setField(doc, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(doc));

        var nodes = contributor.contributeNodes(projectId);

        assertThat(nodes.get(0).properties()).doesNotContainKey("grammar");
    }

    @Test
    void projectIsolationExcludesOtherProjectDocuments() {
        when(repository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of());

        var nodes = contributor.contributeNodes(projectId);

        assertThat(nodes).isEmpty();
    }

    @Test
    void contributeEdgesReturnsEmptyList() {
        var edges = contributor.contributeEdges(projectId);

        assertThat(edges).isEmpty();
    }

    private Document buildDocument(String title, String version) {
        return new Document(project, title, version, null, null);
    }
}

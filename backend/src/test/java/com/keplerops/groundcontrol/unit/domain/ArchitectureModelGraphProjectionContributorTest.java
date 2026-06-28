package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElement;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelSnapshot;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelElementStateRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureFlowDirection;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementStateCommand;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelGraphProjectionContributor;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelProvenanceSource;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArchitectureModelGraphProjectionContributorTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111119");
    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";

    @Mock
    private ArchitectureModelElementStateRepository stateRepository;

    @Test
    void contributesArchitectureModelElementNodesAndDataFlowEdgesFromLatestSnapshotStates() {
        var contributor = new ArchitectureModelGraphProjectionContributor(stateRepository);
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var snapshot = new ArchitectureModelSnapshot(project, null, "architecture-model/v1", COMMIT, "MANUAL", "codex");
        setField(snapshot, "id", UUID.randomUUID());
        var api = state(project, snapshot, "component:api", ArchitectureModelElementKind.COMPONENT, "API", null, null);
        var db = state(project, snapshot, "store:db", ArchitectureModelElementKind.DATA_STORE, "DB", null, null);
        var flow = state(
                project,
                snapshot,
                "flow:api-db",
                ArchitectureModelElementKind.DATA_FLOW,
                "API to DB",
                "component:api",
                "store:db");
        when(stateRepository.findLatestSnapshotStatesByProjectId(PROJECT_ID)).thenReturn(List.of(api, db, flow));

        var nodes = contributor.contributeNodes(PROJECT_ID);
        var edges = contributor.contributeEdges(PROJECT_ID);

        assertThat(nodes)
                .hasSize(3)
                .allSatisfy(node -> assertThat(node.entityType()).isEqualTo(GraphEntityType.ARCHITECTURE_MODEL_ELEMENT))
                .extracting(node -> node.properties().get("elementKind"))
                .contains("COMPONENT", "DATA_STORE", "DATA_FLOW");
        assertThat(edges)
                .singleElement()
                .returns("DATA_FLOW", GraphEdge::edgeType)
                .returns(GraphEntityType.ARCHITECTURE_MODEL_ELEMENT, GraphEdge::sourceEntityType)
                .returns(GraphEntityType.ARCHITECTURE_MODEL_ELEMENT, GraphEdge::targetEntityType)
                .matches(edge -> "UNIDIRECTIONAL".equals(edge.properties().get("flowDirection")));
    }

    private static ArchitectureModelElementState state(
            Project project,
            ArchitectureModelSnapshot snapshot,
            String stableKey,
            ArchitectureModelElementKind kind,
            String label,
            String sourceKey,
            String targetKey) {
        var element = new ArchitectureModelElement(project, stableKey, kind);
        setField(element, "id", UUID.randomUUID());
        return new ArchitectureModelElementState(
                project,
                snapshot,
                element,
                new ArchitectureModelElementStateCommand(
                        stableKey,
                        kind,
                        label,
                        "summary",
                        "backend/src/main/java/App.java",
                        "backend",
                        "internal",
                        sourceKey,
                        targetKey,
                        sourceKey == null ? null : ArchitectureFlowDirection.UNIDIRECTIONAL,
                        ArchitectureModelProvenanceSource.ADAPTER,
                        stableKey,
                        "adapter-a",
                        "tool",
                        "1.0.0",
                        "rules",
                        "2026.06",
                        null,
                        COMMIT,
                        Map.of()));
    }
}

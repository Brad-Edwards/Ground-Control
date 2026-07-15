package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.graph.GraphTraversalLimits;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GraphIdsTest {

    @Test
    void artifactReferenceIdentityIsDeterministicExactAndBounded() {
        UUID projectId = UUID.randomUUID();
        String longIdentifier = "x".repeat(500);

        String first = GraphIds.artifactReferenceNodeId(projectId, ArtifactType.CODE_FILE, longIdentifier);
        String again = GraphIds.artifactReferenceNodeId(projectId, ArtifactType.CODE_FILE, longIdentifier);

        assertThat(first)
                .isEqualTo(again)
                .startsWith("ARTIFACT_REFERENCE:")
                .hasSizeLessThanOrEqualTo(GraphTraversalLimits.MAX_NODE_IDENTIFIER_LENGTH);
        assertThat(GraphIds.artifactReferenceNodeId(projectId, ArtifactType.CODE_FILE, longIdentifier + " "))
                .isNotEqualTo(first);
        assertThat(GraphIds.artifactReferenceNodeId(projectId, ArtifactType.TEST, longIdentifier))
                .isNotEqualTo(first);
        assertThat(GraphIds.artifactReferenceNodeId(UUID.randomUUID(), ArtifactType.CODE_FILE, longIdentifier))
                .isNotEqualTo(first);
    }
}

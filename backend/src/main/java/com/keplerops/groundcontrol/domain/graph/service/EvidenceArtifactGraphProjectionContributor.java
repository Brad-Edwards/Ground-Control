package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Projects {@link EvidenceArtifact} nodes and their derivation edges into the
 * mixed-graph view. Internal source kinds map to existing {@link
 * GraphEntityType} nodes; external kinds (ATTESTATION, EXTERNAL) carry only a
 * canonical identifier and do not produce graph edges. The
 * {@code superseded_by} edge points from the prior artifact to the newer one
 * when {@code supersededByArtifactId} is set.
 */
@Component
public class EvidenceArtifactGraphProjectionContributor implements GraphProjectionContributor {

    private static final String EDGE_HAS_SOURCE = "HAS_SOURCE";
    private static final String EDGE_SUPERSEDED_BY = "SUPERSEDED_BY";

    private final EvidenceArtifactRepository repository;

    public EvidenceArtifactGraphProjectionContributor(EvidenceArtifactRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        return repository.findByProjectIdOrderByDerivedAtDesc(projectId).stream()
                .map(EvidenceArtifactGraphProjectionContributor::toNode)
                .toList();
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        var artifacts = repository.findByProjectIdOrderByDerivedAtDesc(projectId);
        var edges = new ArrayList<GraphEdge>();
        for (var artifact : artifacts) {
            edges.addAll(toSourceEdges(artifact));
            var supersededEdge = toSupersededEdge(artifact);
            if (supersededEdge != null) {
                edges.add(supersededEdge);
            }
        }
        return edges;
    }

    private static GraphNode toNode(EvidenceArtifact artifact) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", artifact.getTitle());
        properties.put("evidenceType", artifact.getEvidenceType().name());
        properties.put("derivationMethod", artifact.getDerivationMethod());
        properties.put("derivedAt", artifact.getDerivedAt().toString());
        if (artifact.getDerivedBy() != null) {
            properties.put("derivedBy", artifact.getDerivedBy());
        }
        if (artifact.getAssuranceLevel() != null) {
            properties.put("assuranceLevel", artifact.getAssuranceLevel().name());
        }
        if (artifact.getConfidence() != null) {
            properties.put("confidence", artifact.getConfidence());
        }
        if (artifact.getSupersededByArtifactId() != null) {
            properties.put(
                    "supersededByArtifactId",
                    artifact.getSupersededByArtifactId().toString());
        }
        return new GraphNode(
                GraphIds.nodeId(GraphEntityType.EVIDENCE_ARTIFACT, artifact.getId()),
                artifact.getId().toString(),
                GraphEntityType.EVIDENCE_ARTIFACT,
                artifact.getProject().getIdentifier(),
                artifact.getUid(),
                artifact.getTitle(),
                properties);
    }

    private static List<GraphEdge> toSourceEdges(EvidenceArtifact artifact) {
        var sources = artifact.getSources();
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        var edges = new ArrayList<GraphEdge>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            var ref = sources.get(i);
            var edge = toSourceEdge(artifact.getId(), i, ref);
            if (edge != null) {
                edges.add(edge);
            }
        }
        return edges;
    }

    private static GraphEdge toSourceEdge(UUID artifactId, int index, EvidenceSourceRef ref) {
        var targetEntityType = mapSourceKind(ref.sourceKind());
        if (targetEntityType == null || ref.sourceEntityId() == null) {
            return null;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sourceKind", ref.sourceKind().name());
        if (ref.role() != null) {
            properties.put("role", ref.role());
        }
        return new GraphEdge(
                artifactId + ":source:" + index,
                EDGE_HAS_SOURCE,
                GraphIds.nodeId(GraphEntityType.EVIDENCE_ARTIFACT, artifactId),
                GraphIds.nodeId(targetEntityType, ref.sourceEntityId()),
                GraphEntityType.EVIDENCE_ARTIFACT,
                targetEntityType,
                properties);
    }

    private static GraphEdge toSupersededEdge(EvidenceArtifact artifact) {
        if (artifact.getSupersededByArtifactId() == null) {
            return null;
        }
        return new GraphEdge(
                artifact.getId() + ":superseded-by",
                EDGE_SUPERSEDED_BY,
                GraphIds.nodeId(GraphEntityType.EVIDENCE_ARTIFACT, artifact.getId()),
                GraphIds.nodeId(GraphEntityType.EVIDENCE_ARTIFACT, artifact.getSupersededByArtifactId()),
                GraphEntityType.EVIDENCE_ARTIFACT,
                GraphEntityType.EVIDENCE_ARTIFACT,
                Map.of());
    }

    private static GraphEntityType mapSourceKind(EvidenceSourceKind kind) {
        return switch (kind) {
            case OBSERVATION -> GraphEntityType.OBSERVATION;
            case CONTROL_TEST -> GraphEntityType.CONTROL_TEST;
            case VERIFICATION_RESULT -> GraphEntityType.VERIFICATION_RESULT;
            case FINDING -> GraphEntityType.FINDING;
                // CONTROL_EFFECTIVENESS_ASSESSMENT and RISK_ASSESSMENT_RESULT are retired source
                // kinds (ADR-089) with no backing graph node; never emit an edge for them.
            case CONTROL_EFFECTIVENESS_ASSESSMENT, RISK_ASSESSMENT_RESULT, ATTESTATION, EXTERNAL -> null;
        };
    }
}

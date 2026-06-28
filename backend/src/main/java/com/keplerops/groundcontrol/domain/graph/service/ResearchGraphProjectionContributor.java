package com.keplerops.groundcontrol.domain.graph.service;

import com.keplerops.groundcontrol.domain.graph.model.GraphEdge;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.graph.model.GraphNode;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceRecordStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceEdge;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceNode;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.repository.ResearchProvenanceEdgeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchProvenanceNodeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Projects research runs, their artifact manifest rows, and their provenance
 * nodes/edges into the mixed Ground Control graph (ADR-070). The relational
 * research ledger (ADR-064 / ADR-069) remains the source of truth; this is a
 * read-only projection over {@code ACTIVE} rows of the current reproducibility
 * chain.
 *
 * <p>Three aggregate-level entity types participate: {@link
 * GraphEntityType#RESEARCH_RUN}, {@link GraphEntityType#RESEARCH_ARTIFACT}, and
 * {@link GraphEntityType#RESEARCH_PROVENANCE_NODE}. Artifact type, lifecycle
 * stage, and provenance kind are bounded node properties, not separate graph
 * types (ADR-070 §2).
 *
 * <p>Security (GC-TM-009 / GC-RS-009, ADR-070 §5–§6): all reads are scoped to the
 * owning run's project so a project-blind query can never surface another
 * project's research; {@code FAILED} runs and everything hanging off them stay
 * out of the default projection; and only bounded identifiers, enum names,
 * hashes, counts, and timestamps are projected — never {@code summary},
 * {@code locator}, {@code subjectKey}, or any other raw research content.
 */
@Component
public class ResearchGraphProjectionContributor implements GraphProjectionContributor {

    private static final String EDGE_HAS_RESEARCH_ARTIFACT = "HAS_RESEARCH_ARTIFACT";
    private static final String EDGE_ARTIFACT_HAS_PROVENANCE = "ARTIFACT_HAS_PROVENANCE";
    private static final String KEY_STATUS = "status";

    private final ResearchRunRepository runRepository;
    private final ResearchRunArtifactRepository artifactRepository;
    private final ResearchProvenanceNodeRepository provenanceNodeRepository;
    private final ResearchProvenanceEdgeRepository provenanceEdgeRepository;

    public ResearchGraphProjectionContributor(
            ResearchRunRepository runRepository,
            ResearchRunArtifactRepository artifactRepository,
            ResearchProvenanceNodeRepository provenanceNodeRepository,
            ResearchProvenanceEdgeRepository provenanceEdgeRepository) {
        this.runRepository = runRepository;
        this.artifactRepository = artifactRepository;
        this.provenanceNodeRepository = provenanceNodeRepository;
        this.provenanceEdgeRepository = provenanceEdgeRepository;
    }

    @Override
    public List<GraphNode> contributeNodes(UUID projectId) {
        var projectedRunIds = projectedRunIds(projectId);
        var nodes = new ArrayList<GraphNode>();
        for (var run : projectedRuns(projectId)) {
            nodes.add(toRunNode(run));
        }
        for (var artifact : projectedArtifacts(projectId, projectedRunIds)) {
            nodes.add(toArtifactNode(artifact));
        }
        for (var node : projectedNodes(projectId, projectedRunIds)) {
            nodes.add(toProvenanceNode(node));
        }
        return nodes;
    }

    @Override
    public List<GraphEdge> contributeEdges(UUID projectId) {
        var projectedRunIds = projectedRunIds(projectId);
        var artifacts = projectedArtifacts(projectId, projectedRunIds);
        var nodes = projectedNodes(projectId, projectedRunIds);
        var artifactIds = artifacts.stream().map(ResearchRunArtifact::getId).collect(Collectors.toSet());
        var nodeIds = nodes.stream().map(ResearchProvenanceNode::getId).collect(Collectors.toSet());

        var edges = new ArrayList<GraphEdge>();
        for (var artifact : artifacts) {
            edges.add(hasArtifactEdge(artifact));
        }
        for (var node : nodes) {
            var artifactId = node.getArtifactId();
            if (artifactId != null && artifactIds.contains(artifactId)) {
                edges.add(artifactHasProvenanceEdge(artifactId, node.getId()));
            }
        }
        for (var edge : provenanceEdgeRepository.findByProjectIdAndStatus(projectId, ProvenanceRecordStatus.ACTIVE)) {
            // Drop edges whose endpoints are not both in the projected node set
            // (e.g. an edge onto a SUPERSEDED node) so the graph never carries a
            // dangling reference.
            if (nodeIds.contains(edge.getFromNodeId()) && nodeIds.contains(edge.getToNodeId())) {
                edges.add(provenanceEdge(edge));
            }
        }
        return edges;
    }

    private List<ResearchRun> projectedRuns(UUID projectId) {
        return runRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(run -> run.getStatus() != ResearchRunStatus.FAILED)
                .toList();
    }

    private Set<UUID> projectedRunIds(UUID projectId) {
        return projectedRuns(projectId).stream().map(ResearchRun::getId).collect(Collectors.toSet());
    }

    private List<ResearchRunArtifact> projectedArtifacts(UUID projectId, Set<UUID> projectedRunIds) {
        return artifactRepository.findByProjectIdAndStatus(projectId, ResearchArtifactStatus.ACTIVE).stream()
                .filter(artifact ->
                        projectedRunIds.contains(artifact.getResearchRun().getId()))
                .toList();
    }

    private List<ResearchProvenanceNode> projectedNodes(UUID projectId, Set<UUID> projectedRunIds) {
        return provenanceNodeRepository.findByProjectIdAndStatus(projectId, ProvenanceRecordStatus.ACTIVE).stream()
                .filter(node -> projectedRunIds.contains(node.getResearchRun().getId()))
                .toList();
    }

    private static GraphNode toRunNode(ResearchRun run) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(KEY_STATUS, run.getStatus().name());
        properties.put("currentStage", run.getCurrentStage().name());
        properties.put("autonomyLevel", run.getAutonomyLevel().name());
        properties.put("startedAt", run.getStartedAt().toString());
        if (run.getStoppedAt() != null) {
            properties.put("stoppedAt", run.getStoppedAt().toString());
        }
        return new GraphNode(
                GraphIds.nodeId(GraphEntityType.RESEARCH_RUN, run.getId()),
                run.getId().toString(),
                GraphEntityType.RESEARCH_RUN,
                run.getProject().getIdentifier(),
                run.getUid(),
                run.getUid(),
                properties);
    }

    private static GraphNode toArtifactNode(ResearchRunArtifact artifact) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("artifactType", artifact.getArtifactType().name());
        properties.put("stage", artifact.getStage().name());
        properties.put(KEY_STATUS, artifact.getStatus().name());
        properties.put("attemptNo", artifact.getAttemptNo());
        if (artifact.getContentHash() != null) {
            properties.put("contentHash", artifact.getContentHash());
        }
        return new GraphNode(
                GraphIds.nodeId(GraphEntityType.RESEARCH_ARTIFACT, artifact.getId()),
                artifact.getId().toString(),
                GraphEntityType.RESEARCH_ARTIFACT,
                artifact.getResearchRun().getProject().getIdentifier(),
                artifact.getId().toString(),
                artifact.getArtifactType().name(),
                properties);
    }

    private static GraphNode toProvenanceNode(ResearchProvenanceNode node) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("kind", node.getKind().name());
        properties.put(KEY_STATUS, node.getStatus().name());
        if (node.getStage() != null) {
            properties.put("stage", node.getStage().name());
        }
        if (node.getArtifactType() != null) {
            properties.put("artifactType", node.getArtifactType().name());
        }
        if (node.getAttemptNo() != null) {
            properties.put("attemptNo", node.getAttemptNo());
        }
        if (node.getContentHash() != null) {
            properties.put("contentHash", node.getContentHash());
        }
        if (node.getExternalIdentifier() != null) {
            properties.put("externalIdentifier", node.getExternalIdentifier());
        }
        return new GraphNode(
                GraphIds.nodeId(GraphEntityType.RESEARCH_PROVENANCE_NODE, node.getId()),
                node.getId().toString(),
                GraphEntityType.RESEARCH_PROVENANCE_NODE,
                node.getResearchRun().getProject().getIdentifier(),
                node.getId().toString(),
                node.getKind().name(),
                properties);
    }

    private static GraphEdge hasArtifactEdge(ResearchRunArtifact artifact) {
        var runId = artifact.getResearchRun().getId();
        return new GraphEdge(
                runId + ":has-artifact:" + artifact.getId(),
                EDGE_HAS_RESEARCH_ARTIFACT,
                GraphIds.nodeId(GraphEntityType.RESEARCH_RUN, runId),
                GraphIds.nodeId(GraphEntityType.RESEARCH_ARTIFACT, artifact.getId()),
                GraphEntityType.RESEARCH_RUN,
                GraphEntityType.RESEARCH_ARTIFACT,
                Map.of());
    }

    private static GraphEdge artifactHasProvenanceEdge(UUID artifactId, UUID nodeId) {
        return new GraphEdge(
                artifactId + ":has-provenance:" + nodeId,
                EDGE_ARTIFACT_HAS_PROVENANCE,
                GraphIds.nodeId(GraphEntityType.RESEARCH_ARTIFACT, artifactId),
                GraphIds.nodeId(GraphEntityType.RESEARCH_PROVENANCE_NODE, nodeId),
                GraphEntityType.RESEARCH_ARTIFACT,
                GraphEntityType.RESEARCH_PROVENANCE_NODE,
                Map.of());
    }

    private static GraphEdge provenanceEdge(ResearchProvenanceEdge edge) {
        // Edge type is the closed ProvenanceEdgeRelation enum; direction is the
        // ADR-069 upstream input -> downstream output that the ledger persists.
        return new GraphEdge(
                edge.getId().toString(),
                edge.getRelation().name(),
                GraphIds.nodeId(GraphEntityType.RESEARCH_PROVENANCE_NODE, edge.getFromNodeId()),
                GraphIds.nodeId(GraphEntityType.RESEARCH_PROVENANCE_NODE, edge.getToNodeId()),
                GraphEntityType.RESEARCH_PROVENANCE_NODE,
                GraphEntityType.RESEARCH_PROVENANCE_NODE,
                Map.of());
    }
}

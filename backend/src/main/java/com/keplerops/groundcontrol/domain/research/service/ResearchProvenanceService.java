package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceNodeKind;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceRecordStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceEdge;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceNode;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.repository.ResearchProvenanceEdgeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchProvenanceNodeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-RSCH-R004 / GC-RSCH-N002 / GC-RSCH-N004 / ADR-069 — application service for
 * the research provenance ledger: a run-scoped, append-only directed derivation
 * graph of {@link ResearchProvenanceNode}s and {@link ResearchProvenanceEdge}s.
 *
 * <p>Sole authority for provenance write legality and read traversal. Every
 * lookup is project- and run-scoped, and a cross-project (or cross-run) reference
 * is concealed as {@link NotFoundException} so a probing caller cannot learn
 * another project's runs or nodes exist (GC-RS-009 / GC-TM-009).
 *
 * <p>Writes are idempotent on a run-scoped {@code idempotencyKey} and rework-aware:
 * re-recording the same logical node (kind + subjectKey) or edge (from + to +
 * relation) supersedes the prior ACTIVE record and inserts a replacement rather
 * than mutating in place. Self-edges and cycles are rejected. Persisted records
 * carry bounded, low-cardinality metadata only — the service validates lengths
 * and logs only IDs/enums/counts, never raw queries, full text, charting rows,
 * manuscripts, prompts, provider payloads, secrets, or absolute paths.
 */
@Service
@Transactional
public class ResearchProvenanceService {

    private static final Logger log = LoggerFactory.getLogger(ResearchProvenanceService.class);

    private static final int SUBJECT_KEY_MAX = 200;
    private static final int LOCATOR_MAX = 500;
    private static final int HASH_MAX = 128;
    private static final int EXTERNAL_ID_MAX = 200;
    private static final int SUMMARY_MAX = 2000;
    private static final int TOOL_NAME_MAX = 200;
    private static final int TOOL_VERSION_MAX = 100;
    private static final int ACTION_ID_MAX = 200;
    private static final int ROLE_MAX = 200;
    private static final int IDEMPOTENCY_KEY_MAX = 200;

    /** Hard ceiling on traversal depth regardless of caller request (ADR-069 §6). */
    private static final int MAX_CHAIN_DEPTH = 50;

    private static final int DEFAULT_CHAIN_DEPTH = 25;

    /** Hard ceiling on total nodes a single chain read may return. */
    private static final int CHAIN_NODE_CAP = 1000;

    private static final String INVALID_NODE = "invalid_provenance_node";
    private static final String INVALID_EDGE = "invalid_provenance_edge";
    private static final String FIELD = "field";
    private static final String IDEMPOTENCY_FIELD = "idempotencyKey";
    private static final String IDEMPOTENCY_CONFLICT = "provenance_idempotency_conflict";

    private final ResearchRunRepository runRepository;
    private final ResearchProvenanceNodeRepository nodeRepository;
    private final ResearchProvenanceEdgeRepository edgeRepository;
    private final ResearchRunArtifactRepository artifactRepository;

    public ResearchProvenanceService(
            ResearchRunRepository runRepository,
            ResearchProvenanceNodeRepository nodeRepository,
            ResearchProvenanceEdgeRepository edgeRepository,
            ResearchRunArtifactRepository artifactRepository) {
        this.runRepository = runRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.artifactRepository = artifactRepository;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Record (or rework) a provenance node. Idempotent on {@code idempotencyKey};
     * otherwise a re-record of the same (kind, subjectKey) supersedes the prior
     * ACTIVE node.
     */
    public ResearchProvenanceNode recordNode(UUID projectId, UUID runId, RecordProvenanceNodeCommand command) {
        var run = requireRun(projectId, runId);
        var candidate = buildNodeCandidate(run, runId, command);
        var replay = replayIfPresent(
                candidate.getIdempotencyKey(),
                k -> nodeRepository.findByResearchRunIdAndIdempotencyKey(runId, k),
                existing -> nodesEquivalent(existing, candidate));
        if (replay.isPresent()) {
            return replay.get();
        }
        var prior = findActiveNode(runId, candidate.getKind(), candidate.getSubjectKey());
        supersedeNode(prior);
        var saved = nodeRepository.save(candidate);
        relinkNode(prior, saved.getId());
        log.info(
                "research_provenance_node_recorded: project={} run={} kind={} rework={}",
                run.getProject().getIdentifier(),
                runId,
                candidate.getKind(),
                prior != null);
        return saved;
    }

    /**
     * Validate, resolve the artifact reference, and assemble an unsaved candidate
     * node. The node's stage / artifactType / attemptNo describe the referenced
     * artifact attempt, so they are backfilled from the manifest row and any
     * caller-supplied value must agree with it (ADR-069 §2/§5). The recording
     * actor comes from the authenticated server context, never the command.
     */
    private ResearchProvenanceNode buildNodeCandidate(
            ResearchRun run, UUID runId, RecordProvenanceNodeCommand command) {
        if (command == null || command.kind() == null) {
            throw new DomainValidationException("kind must not be null", INVALID_NODE, Map.of(FIELD, "kind"));
        }
        var subjectKey = emptyToNull(command.subjectKey());
        if (subjectKey == null) {
            throw new DomainValidationException(
                    "subjectKey must not be blank", INVALID_NODE, Map.of(FIELD, "subjectKey"));
        }
        validateNodeLengths(command, subjectKey);
        var artifact = resolveArtifactReference(runId, command);
        var node = new ResearchProvenanceNode(run, command.kind(), subjectKey);
        node.setStage(command.stage() != null ? command.stage() : stageOf(artifact));
        node.setArtifactType(command.artifactType() != null ? command.artifactType() : artifactTypeOf(artifact));
        node.setAttemptNo(command.attemptNo() != null ? command.attemptNo() : attemptNoOf(artifact));
        node.setArtifactId(command.artifactId());
        node.setLocator(emptyToNull(command.locator()));
        node.setContentHash(emptyToNull(command.contentHash()));
        node.setExternalIdentifier(emptyToNull(command.externalIdentifier()));
        node.setSummary(emptyToNull(command.summary()));
        node.setToolName(emptyToNull(command.toolName()));
        node.setToolVersion(emptyToNull(command.toolVersion()));
        node.setSourceActionId(emptyToNull(command.sourceActionId()));
        node.setIdempotencyKey(emptyToNull(command.idempotencyKey()));
        node.setActor(currentActor());
        return node;
    }

    private void validateNodeLengths(RecordProvenanceNodeCommand command, String subjectKey) {
        requireUnder(subjectKey, SUBJECT_KEY_MAX, "subjectKey");
        requireUnder(command.locator(), LOCATOR_MAX, "locator");
        requireUnder(command.contentHash(), HASH_MAX, "contentHash");
        requireUnder(command.externalIdentifier(), EXTERNAL_ID_MAX, "externalIdentifier");
        requireUnder(command.summary(), SUMMARY_MAX, "summary");
        requireUnder(command.toolName(), TOOL_NAME_MAX, "toolName");
        requireUnder(command.toolVersion(), TOOL_VERSION_MAX, "toolVersion");
        requireUnder(command.sourceActionId(), ACTION_ID_MAX, "sourceActionId");
        requireUnder(command.idempotencyKey(), IDEMPOTENCY_KEY_MAX, IDEMPOTENCY_FIELD);
    }

    private ResearchRunStage stageOf(ResearchRunArtifact artifact) {
        return artifact != null ? artifact.getStage() : null;
    }

    private ResearchArtifactType artifactTypeOf(ResearchRunArtifact artifact) {
        return artifact != null ? artifact.getArtifactType() : null;
    }

    private Integer attemptNoOf(ResearchRunArtifact artifact) {
        return artifact != null ? artifact.getAttemptNo() : null;
    }

    private ResearchProvenanceNode findActiveNode(UUID runId, ProvenanceNodeKind kind, String subjectKey) {
        return nodeRepository
                .findByResearchRunIdAndStatusOrderByCreatedAtAsc(runId, ProvenanceRecordStatus.ACTIVE)
                .stream()
                .filter(n -> n.getKind() == kind && n.getSubjectKey().equals(subjectKey))
                .findFirst()
                .orElse(null);
    }

    private void supersedeNode(ResearchProvenanceNode prior) {
        if (prior != null) {
            // Flush the SUPERSEDED status before inserting the replacement so the
            // single-active partial unique index is never transiently violated.
            prior.markSuperseded();
            nodeRepository.saveAndFlush(prior);
        }
    }

    private void relinkNode(ResearchProvenanceNode prior, UUID replacementId) {
        if (prior != null) {
            prior.linkSuperseder(replacementId);
            nodeRepository.save(prior);
        }
    }

    /**
     * Record (or rework) a provenance edge. Validates that both endpoints belong
     * to the run, rejects self-edges and cycles, is idempotent on
     * {@code idempotencyKey}, and otherwise supersedes a prior ACTIVE edge with
     * the same (from, to, relation).
     */
    public ResearchProvenanceEdge recordEdge(UUID projectId, UUID runId, RecordProvenanceEdgeCommand command) {
        var run = requireRun(projectId, runId);
        validateEdgeCommand(runId, command);
        var candidate = buildEdgeCandidate(run, command);
        var replay = replayIfPresent(
                candidate.getIdempotencyKey(),
                k -> edgeRepository.findByResearchRunIdAndIdempotencyKey(runId, k),
                existing -> edgesEquivalent(existing, candidate));
        if (replay.isPresent()) {
            return replay.get();
        }
        requireNoCycle(runId, command);
        var prior = findActiveEdge(runId, command);
        supersedeEdge(prior);
        var saved = edgeRepository.save(candidate);
        relinkEdge(prior, saved.getId());
        log.info(
                "research_provenance_edge_recorded: project={} run={} relation={} rework={}",
                run.getProject().getIdentifier(),
                runId,
                command.relation(),
                prior != null);
        return saved;
    }

    private void validateEdgeCommand(UUID runId, RecordProvenanceEdgeCommand command) {
        if (command == null
                || command.fromNodeId() == null
                || command.toNodeId() == null
                || command.relation() == null) {
            throw new DomainValidationException(
                    "fromNodeId, toNodeId, and relation are required", INVALID_EDGE, Map.of());
        }
        if (command.fromNodeId().equals(command.toNodeId())) {
            throw new DomainValidationException(
                    "An edge must not connect a node to itself", INVALID_EDGE, Map.of(FIELD, "toNodeId"));
        }
        requireUnder(command.role(), ROLE_MAX, "role");
        requireUnder(command.summary(), SUMMARY_MAX, "summary");
        requireUnder(command.idempotencyKey(), IDEMPOTENCY_KEY_MAX, IDEMPOTENCY_FIELD);
        // Conceal cross-run / cross-project endpoints as not-found.
        requireNodeInRun(runId, command.fromNodeId());
        requireNodeInRun(runId, command.toNodeId());
    }

    private void requireNodeInRun(UUID runId, UUID nodeId) {
        if (!nodeRepository.existsByIdAndResearchRunId(nodeId, runId)) {
            throw new NotFoundException("Provenance node not found: " + nodeId);
        }
    }

    private ResearchProvenanceEdge buildEdgeCandidate(ResearchRun run, RecordProvenanceEdgeCommand command) {
        var edge = new ResearchProvenanceEdge(run, command.fromNodeId(), command.toNodeId(), command.relation());
        edge.setRole(emptyToNull(command.role()));
        edge.setSummary(emptyToNull(command.summary()));
        edge.setIdempotencyKey(emptyToNull(command.idempotencyKey()));
        edge.setActor(currentActor());
        return edge;
    }

    private void requireNoCycle(UUID runId, RecordProvenanceEdgeCommand command) {
        if (reaches(runId, command.toNodeId(), command.fromNodeId())) {
            throw new ConflictException(
                    "Edge would introduce a provenance cycle",
                    "provenance_cycle",
                    Map.of(
                            "from",
                            command.fromNodeId().toString(),
                            "to",
                            command.toNodeId().toString()));
        }
    }

    private ResearchProvenanceEdge findActiveEdge(UUID runId, RecordProvenanceEdgeCommand command) {
        return edgeRepository
                .findByResearchRunIdAndStatusOrderByCreatedAtAsc(runId, ProvenanceRecordStatus.ACTIVE)
                .stream()
                .filter(e -> e.getFromNodeId().equals(command.fromNodeId())
                        && e.getToNodeId().equals(command.toNodeId())
                        && e.getRelation() == command.relation())
                .findFirst()
                .orElse(null);
    }

    private void supersedeEdge(ResearchProvenanceEdge prior) {
        if (prior != null) {
            prior.markSuperseded();
            edgeRepository.saveAndFlush(prior);
        }
    }

    private void relinkEdge(ResearchProvenanceEdge prior, UUID replacementId) {
        if (prior != null) {
            prior.linkSuperseder(replacementId);
            edgeRepository.save(prior);
        }
    }

    /**
     * Shared idempotent-replay gate. Returns the existing record when the key
     * matches a compatible payload; throws {@link ConflictException} when the key
     * was reused with a different payload (never a silent no-op that could
     * suppress or poison the durable provenance chain); returns empty when no key
     * or no existing record (the caller then inserts a new record).
     */
    private <T> Optional<T> replayIfPresent(String key, Function<String, Optional<T>> lookup, Predicate<T> compatible) {
        if (key == null) {
            return Optional.empty();
        }
        var existing = lookup.apply(key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (!compatible.test(existing.get())) {
            throw new ConflictException(
                    "Idempotency key reused with a different payload",
                    IDEMPOTENCY_CONFLICT,
                    Map.of(FIELD, IDEMPOTENCY_FIELD));
        }
        return existing;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public List<ResearchProvenanceNode> listNodes(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        return nodeRepository.findByResearchRunIdOrderByCreatedAtAsc(runId);
    }

    public List<ResearchProvenanceEdge> listEdges(UUID projectId, UUID runId) {
        requireRun(projectId, runId);
        return edgeRepository.findByResearchRunIdOrderByCreatedAtAsc(runId);
    }

    /**
     * GC-RSCH-R004 — bounded backward-provenance traversal from {@code nodeId}.
     * Walks ACTIVE incoming edges to collect the upstream nodes and edges that
     * support the root node, up to {@code requestedDepth} (clamped to
     * {@link #MAX_CHAIN_DEPTH}) and {@link #CHAIN_NODE_CAP} nodes.
     */
    public ProvenanceChain getProvenanceChain(UUID projectId, UUID runId, UUID nodeId, Integer requestedDepth) {
        requireRun(projectId, runId);
        var root = nodeRepository
                .findByIdAndResearchRunId(nodeId, runId)
                .orElseThrow(() -> new NotFoundException("Provenance node not found: " + nodeId));

        var maxDepth = clampDepth(requestedDepth);
        var nodesById = new LinkedHashMap<UUID, ResearchProvenanceNode>();
        var edges = new ArrayList<ResearchProvenanceEdge>();
        var seenEdges = new HashSet<UUID>();
        nodesById.put(root.getId(), root);

        // BFS over incoming ACTIVE edges, level by level, bounded by depth and cap.
        var frontier = new ArrayDeque<UUID>();
        frontier.add(root.getId());
        boolean truncated = false;
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            var next = new ArrayDeque<UUID>();
            while (!frontier.isEmpty()) {
                if (expandIncoming(runId, frontier.poll(), nodesById, edges, seenEdges, next)) {
                    truncated = true;
                }
            }
            if (depth == maxDepth - 1 && !next.isEmpty()) {
                truncated = true;
            }
            frontier = next;
        }

        return new ProvenanceChain(root.getId(), maxDepth, truncated, new ArrayList<>(nodesById.values()), edges);
    }

    /**
     * Walk the ACTIVE incoming edges of {@code current}: record each edge, and for
     * each newly seen upstream node load it (within the cap) and queue it onto
     * {@code next}. Returns true when the node cap was hit (a partial chain).
     */
    private boolean expandIncoming(
            UUID runId,
            UUID current,
            Map<UUID, ResearchProvenanceNode> nodesById,
            List<ResearchProvenanceEdge> edges,
            Set<UUID> seenEdges,
            Deque<UUID> next) {
        boolean hitCap = false;
        for (var edge :
                edgeRepository.findByResearchRunIdAndToNodeIdAndStatus(runId, current, ProvenanceRecordStatus.ACTIVE)) {
            if (seenEdges.add(edge.getId())) {
                edges.add(edge);
            }
            var upstreamId = edge.getFromNodeId();
            if (!nodesById.containsKey(upstreamId)) {
                if (nodesById.size() >= CHAIN_NODE_CAP) {
                    hitCap = true;
                } else {
                    nodeRepository
                            .findByIdAndResearchRunId(upstreamId, runId)
                            .ifPresent(n -> nodesById.put(n.getId(), n));
                    next.add(upstreamId);
                }
            }
        }
        return hitCap;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * True when {@code targetId} is reachable from {@code startId} by following
     * ACTIVE edges forward (from -> to). Bounded by {@link #CHAIN_NODE_CAP}.
     */
    private boolean reaches(UUID runId, UUID startId, UUID targetId) {
        Set<UUID> visited = new HashSet<>();
        Deque<UUID> stack = new ArrayDeque<>();
        stack.push(startId);
        while (!stack.isEmpty()) {
            var current = stack.pop();
            if (current.equals(targetId)) {
                return true;
            }
            if (!visited.add(current) || visited.size() > CHAIN_NODE_CAP) {
                continue;
            }
            for (var edge : edgeRepository.findByResearchRunIdAndFromNodeIdAndStatus(
                    runId, current, ProvenanceRecordStatus.ACTIVE)) {
                stack.push(edge.getToNodeId());
            }
        }
        return false;
    }

    private int clampDepth(Integer requested) {
        if (requested == null) {
            return DEFAULT_CHAIN_DEPTH;
        }
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_CHAIN_DEPTH);
    }

    /**
     * Resolve the optional artifact reference against the same run and validate
     * that any caller-supplied stage / artifactType / attemptNo agrees with the
     * manifest row. Returns null when no artifact is referenced. A cross-run or
     * nonexistent artifact is concealed as {@link NotFoundException}.
     */
    private ResearchRunArtifact resolveArtifactReference(UUID runId, RecordProvenanceNodeCommand command) {
        if (command.artifactId() == null) {
            return null;
        }
        var artifact = artifactRepository
                .findByIdAndResearchRunId(command.artifactId(), runId)
                .orElseThrow(() -> new NotFoundException("Research artifact not found: " + command.artifactId()));
        if (command.artifactType() != null && command.artifactType() != artifact.getArtifactType()) {
            throw new DomainValidationException(
                    "artifactType does not match the referenced artifact", INVALID_NODE, Map.of(FIELD, "artifactType"));
        }
        if (command.attemptNo() != null && !command.attemptNo().equals(artifact.getAttemptNo())) {
            throw new DomainValidationException(
                    "attemptNo does not match the referenced artifact", INVALID_NODE, Map.of(FIELD, "attemptNo"));
        }
        if (command.stage() != null && command.stage() != artifact.getStage()) {
            throw new DomainValidationException(
                    "stage does not match the referenced artifact", INVALID_NODE, Map.of(FIELD, "stage"));
        }
        return artifact;
    }

    /** True when two nodes carry the same provenance payload (excludes id, status, audit, actor). */
    private boolean nodesEquivalent(ResearchProvenanceNode a, ResearchProvenanceNode b) {
        return a.getKind() == b.getKind()
                && Objects.equals(a.getSubjectKey(), b.getSubjectKey())
                && a.getStage() == b.getStage()
                && a.getArtifactType() == b.getArtifactType()
                && Objects.equals(a.getArtifactId(), b.getArtifactId())
                && Objects.equals(a.getAttemptNo(), b.getAttemptNo())
                && Objects.equals(a.getLocator(), b.getLocator())
                && Objects.equals(a.getContentHash(), b.getContentHash())
                && Objects.equals(a.getExternalIdentifier(), b.getExternalIdentifier())
                && Objects.equals(a.getSummary(), b.getSummary())
                && Objects.equals(a.getToolName(), b.getToolName())
                && Objects.equals(a.getToolVersion(), b.getToolVersion())
                && Objects.equals(a.getSourceActionId(), b.getSourceActionId());
    }

    /** True when two edges carry the same provenance payload (excludes id, status, audit, actor). */
    private boolean edgesEquivalent(ResearchProvenanceEdge a, ResearchProvenanceEdge b) {
        return Objects.equals(a.getFromNodeId(), b.getFromNodeId())
                && Objects.equals(a.getToNodeId(), b.getToNodeId())
                && a.getRelation() == b.getRelation()
                && Objects.equals(a.getRole(), b.getRole())
                && Objects.equals(a.getSummary(), b.getSummary());
    }

    private ResearchRun requireRun(UUID projectId, UUID runId) {
        return runRepository
                .findByIdAndProjectId(runId, projectId)
                .orElseThrow(() -> new NotFoundException("Research run not found: " + runId));
    }

    private void requireUnder(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new DomainValidationException(
                    "Field " + field + " exceeds max length", INVALID_NODE, Map.of(FIELD, field, "max", max));
        }
    }

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String currentActor() {
        return emptyToNull(ActorHolder.get());
    }
}

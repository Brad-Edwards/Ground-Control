package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceEdgeRelation;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceNodeKind;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceRecordStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceEdge;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceNode;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.repository.ResearchProvenanceEdgeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchProvenanceNodeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import com.keplerops.groundcontrol.domain.research.service.RecordProvenanceEdgeCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordProvenanceNodeCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchProvenanceService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Split from ResearchProvenanceServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchProvenanceServiceTest {
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Mock
    private ResearchRunRepository runRepository;

    @Mock
    private ResearchProvenanceNodeRepository nodeRepository;

    @Mock
    private ResearchProvenanceEdgeRepository edgeRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository artifactRepository;

    private ResearchProvenanceService service;
    private Project project;
    private ResearchRun run;

    @BeforeEach
    void setUp() {
        service = new ResearchProvenanceService(runRepository, nodeRepository, edgeRepository, artifactRepository);
        project = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        TestUtil.setField(project, "id", PROJECT_ID);
        run = new ResearchRun(project, "RUN-1", AutonomyLevel.COPILOT);
        TestUtil.setField(run, "id", RUN_ID);

        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(nodeRepository.save(any())).thenAnswer(inv -> {
            ResearchProvenanceNode n = inv.getArgument(0);
            if (n.getId() == null) {
                TestUtil.setField(n, "id", UUID.randomUUID());
            }
            return n;
        });
        when(nodeRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(edgeRepository.save(any())).thenAnswer(inv -> {
            ResearchProvenanceEdge e = inv.getArgument(0);
            if (e.getId() == null) {
                TestUtil.setField(e, "id", UUID.randomUUID());
            }
            return e;
        });
        when(edgeRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        ActorHolder.set("tester@keplerops");
    }

    @AfterEach
    void tearDown() {
        ActorHolder.clear();
    }

    private ResearchProvenanceNode node(
            ProvenanceNodeKind kind, String subjectKey, UUID id, ProvenanceRecordStatus status) {
        var n = new ResearchProvenanceNode(run, kind, subjectKey);
        TestUtil.setField(n, "id", id);
        TestUtil.setField(n, "status", status);
        return n;
    }

    /** A node command carrying only kind + subjectKey, all optional fields null. */
    private RecordProvenanceNodeCommand nodeCommand(ProvenanceNodeKind kind, String subjectKey) {
        return new RecordProvenanceNodeCommand(
                kind, subjectKey, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    // ---- recordNode -------------------------------------------------------

    @Test
    void recordNode_persistsBoundedMetadataAndServerActor() {
        when(nodeRepository.findByResearchRunIdAndStatusOrderByCreatedAtAsc(RUN_ID, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of());

        var saved = service.recordNode(
                PROJECT_ID,
                RUN_ID,
                new RecordProvenanceNodeCommand(
                        ProvenanceNodeKind.SYNTHESIS_CLAIM,
                        "claim-7",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "10.1000/xyz",
                        "A short bounded summary",
                        "lit-review",
                        "1.2.3",
                        "action-42",
                        null));

        assertThat(saved.getKind()).isEqualTo(ProvenanceNodeKind.SYNTHESIS_CLAIM);
        assertThat(saved.getSubjectKey()).isEqualTo("claim-7");
        assertThat(saved.getExternalIdentifier()).isEqualTo("10.1000/xyz");
        assertThat(saved.getActor()).isEqualTo("tester@keplerops");
        assertThat(saved.getStatus()).isEqualTo(ProvenanceRecordStatus.ACTIVE);
        // No artifact referenced, so the artifact-attempt pin stays unset.
        assertThat(saved.getAttemptNo()).isNull();
    }

    @Test
    void recordNode_replaysIdempotentlyWithoutDuplicating() {
        var existing = node(ProvenanceNodeKind.QUERY, "q-1", UUID.randomUUID(), ProvenanceRecordStatus.ACTIVE);
        when(nodeRepository.findByResearchRunIdAndIdempotencyKey(RUN_ID, "idem-1"))
                .thenReturn(Optional.of(existing));

        var result = service.recordNode(
                PROJECT_ID,
                RUN_ID,
                new RecordProvenanceNodeCommand(
                        ProvenanceNodeKind.QUERY,
                        "q-1",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "idem-1"));

        assertThat(result).isSameAs(existing);
        verify(nodeRepository, never()).save(any());
    }

    @Test
    void recordNode_reworkSupersedesPriorActiveSameKindAndSubject() {
        var prior = node(ProvenanceNodeKind.CHARTING_CELL, "cell-3", UUID.randomUUID(), ProvenanceRecordStatus.ACTIVE);
        when(nodeRepository.findByResearchRunIdAndStatusOrderByCreatedAtAsc(RUN_ID, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of(prior));

        var saved = service.recordNode(
                PROJECT_ID,
                RUN_ID,
                new RecordProvenanceNodeCommand(
                        ProvenanceNodeKind.CHARTING_CELL,
                        "cell-3",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "reworked",
                        null,
                        null,
                        null,
                        null));

        assertThat(prior.getStatus()).isEqualTo(ProvenanceRecordStatus.SUPERSEDED);
        assertThat(prior.getSupersededByNodeId()).isEqualTo(saved.getId());
        assertThat(saved.getStatus()).isEqualTo(ProvenanceRecordStatus.ACTIVE);
        verify(nodeRepository).saveAndFlush(prior);
    }

    @Test
    void recordNode_rejectsBlankSubjectKey() {
        var command = nodeCommand(ProvenanceNodeKind.USER_GOAL, "  ");
        assertThatThrownBy(() -> service.recordNode(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void recordNode_rejectsOversizeSummaryAsContentLeakGuard() {
        var command = new RecordProvenanceNodeCommand(
                ProvenanceNodeKind.FINAL_PROSE,
                "para-1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "x".repeat(2001),
                null,
                null,
                null,
                null);
        assertThatThrownBy(() -> service.recordNode(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(DomainValidationException.class);
        verify(nodeRepository, never()).save(any());
    }

    @Test
    void recordNode_concealsCrossProjectRunAsNotFound() {
        var command = nodeCommand(ProvenanceNodeKind.USER_GOAL, "goal");
        assertThatThrownBy(() -> service.recordNode(OTHER_PROJECT_ID, RUN_ID, command))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void recordNode_rejectsIdempotencyKeyReuseWithDifferentPayload() {
        var existing = node(ProvenanceNodeKind.QUERY, "q-1", UUID.randomUUID(), ProvenanceRecordStatus.ACTIVE);
        when(nodeRepository.findByResearchRunIdAndIdempotencyKey(RUN_ID, "idem-1"))
                .thenReturn(Optional.of(existing));

        // Same key, different subjectKey → a real conflict, not a silent replay.
        var command = new RecordProvenanceNodeCommand(
                ProvenanceNodeKind.QUERY,
                "q-2",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "idem-1");
        assertThatThrownBy(() -> service.recordNode(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(ConflictException.class);
        verify(nodeRepository, never()).save(any());
    }

    @Test
    void recordNode_concealsArtifactFromAnotherRunAsNotFound() {
        var artifactId = UUID.randomUUID();
        when(nodeRepository.findByResearchRunIdAndStatusOrderByCreatedAtAsc(RUN_ID, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of());
        when(artifactRepository.findByIdAndResearchRunId(artifactId, RUN_ID)).thenReturn(Optional.empty());

        var command = new RecordProvenanceNodeCommand(
                ProvenanceNodeKind.CHARTING_CELL,
                "cell-1",
                null,
                null,
                artifactId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        assertThatThrownBy(() -> service.recordNode(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(NotFoundException.class);
        verify(nodeRepository, never()).save(any());
    }

    @Test
    void recordNode_rejectsArtifactTypeMismatchAgainstReferencedArtifact() {
        var artifactId = UUID.randomUUID();
        var artifact = new ResearchRunArtifact(run, ResearchArtifactType.CHARTING_DATA, 1);
        TestUtil.setField(artifact, "id", artifactId);
        when(artifactRepository.findByIdAndResearchRunId(artifactId, RUN_ID)).thenReturn(Optional.of(artifact));

        // Caller pins a charting cell to a CHARTING_DATA artifact but declares the
        // wrong artifactType → rejected as an inconsistent reference.
        var command = new RecordProvenanceNodeCommand(
                ProvenanceNodeKind.CHARTING_CELL,
                "cell-1",
                null,
                ResearchArtifactType.SYNTHESIS,
                artifactId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        assertThatThrownBy(() -> service.recordNode(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(DomainValidationException.class);
        verify(nodeRepository, never()).save(any());
    }

    @Test
    void recordNode_backfillsArtifactAttemptFromReferencedArtifact() {
        var artifactId = UUID.randomUUID();
        var artifact = new ResearchRunArtifact(run, ResearchArtifactType.CHARTING_DATA, 3);
        TestUtil.setField(artifact, "id", artifactId);
        when(artifactRepository.findByIdAndResearchRunId(artifactId, RUN_ID)).thenReturn(Optional.of(artifact));
        when(nodeRepository.findByResearchRunIdAndStatusOrderByCreatedAtAsc(RUN_ID, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of());

        var saved = service.recordNode(
                PROJECT_ID,
                RUN_ID,
                new RecordProvenanceNodeCommand(
                        ProvenanceNodeKind.CHARTING_CELL,
                        "cell-1",
                        null,
                        null,
                        artifactId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        assertThat(saved.getArtifactId()).isEqualTo(artifactId);
        assertThat(saved.getArtifactType()).isEqualTo(ResearchArtifactType.CHARTING_DATA);
        assertThat(saved.getAttemptNo()).isEqualTo(3);
    }

    // ---- recordEdge -------------------------------------------------------

    @Test
    void recordEdge_persistsEdgeBetweenRunNodes() {
        var from = UUID.randomUUID();
        var to = UUID.randomUUID();
        when(nodeRepository.existsByIdAndResearchRunId(from, RUN_ID)).thenReturn(true);
        when(nodeRepository.existsByIdAndResearchRunId(to, RUN_ID)).thenReturn(true);
        when(edgeRepository.findByResearchRunIdAndFromNodeIdAndStatus(RUN_ID, to, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of());
        when(edgeRepository.findByResearchRunIdAndStatusOrderByCreatedAtAsc(RUN_ID, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of());

        var saved = service.recordEdge(
                PROJECT_ID,
                RUN_ID,
                new RecordProvenanceEdgeCommand(from, to, ProvenanceEdgeRelation.SUPPORTS, "primary", null, null));

        assertThat(saved.getFromNodeId()).isEqualTo(from);
        assertThat(saved.getToNodeId()).isEqualTo(to);
        assertThat(saved.getRelation()).isEqualTo(ProvenanceEdgeRelation.SUPPORTS);
        assertThat(saved.getActor()).isEqualTo("tester@keplerops");
    }

    @Test
    void recordEdge_rejectsSelfEdge() {
        var n = UUID.randomUUID();
        var command = new RecordProvenanceEdgeCommand(n, n, ProvenanceEdgeRelation.DERIVED_FROM, null, null, null);
        assertThatThrownBy(() -> service.recordEdge(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(DomainValidationException.class);
        verify(edgeRepository, never()).save(any());
    }

    @Test
    void recordEdge_concealsEndpointOutsideRunAsNotFound() {
        var from = UUID.randomUUID();
        var to = UUID.randomUUID();
        when(nodeRepository.existsByIdAndResearchRunId(from, RUN_ID)).thenReturn(true);
        when(nodeRepository.existsByIdAndResearchRunId(to, RUN_ID)).thenReturn(false);

        var command = new RecordProvenanceEdgeCommand(from, to, ProvenanceEdgeRelation.DERIVED_FROM, null, null, null);
        assertThatThrownBy(() -> service.recordEdge(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void recordEdge_rejectsCycle() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(nodeRepository.existsByIdAndResearchRunId(a, RUN_ID)).thenReturn(true);
        when(nodeRepository.existsByIdAndResearchRunId(b, RUN_ID)).thenReturn(true);
        // b already reaches a (edge b -> a), so adding a -> b would close a cycle.
        var bToA = new ResearchProvenanceEdge(run, b, a, ProvenanceEdgeRelation.DERIVED_FROM);
        when(edgeRepository.findByResearchRunIdAndFromNodeIdAndStatus(RUN_ID, b, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of(bToA));
        when(edgeRepository.findByResearchRunIdAndFromNodeIdAndStatus(RUN_ID, a, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of());

        var command = new RecordProvenanceEdgeCommand(a, b, ProvenanceEdgeRelation.SUPPORTS, null, null, null);
        assertThatThrownBy(() -> service.recordEdge(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(ConflictException.class);
        verify(edgeRepository, never()).save(any());
    }

    @Test
    void recordEdge_replaysIdempotently() {
        var from = UUID.randomUUID();
        var to = UUID.randomUUID();
        var existing = new ResearchProvenanceEdge(run, from, to, ProvenanceEdgeRelation.CITED);
        TestUtil.setField(existing, "id", UUID.randomUUID());
        when(nodeRepository.existsByIdAndResearchRunId(from, RUN_ID)).thenReturn(true);
        when(nodeRepository.existsByIdAndResearchRunId(to, RUN_ID)).thenReturn(true);
        when(edgeRepository.findByResearchRunIdAndFromNodeIdAndStatus(RUN_ID, to, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of());
        when(edgeRepository.findByResearchRunIdAndIdempotencyKey(RUN_ID, "e-idem"))
                .thenReturn(Optional.of(existing));

        var result = service.recordEdge(
                PROJECT_ID,
                RUN_ID,
                new RecordProvenanceEdgeCommand(from, to, ProvenanceEdgeRelation.CITED, null, null, "e-idem"));

        assertThat(result).isSameAs(existing);
        verify(edgeRepository, never()).save(any());
    }

    @Test
    void recordEdge_rejectsIdempotencyKeyReuseWithDifferentPayload() {
        var from = UUID.randomUUID();
        var to = UUID.randomUUID();
        var existing = new ResearchProvenanceEdge(run, from, to, ProvenanceEdgeRelation.CITED);
        TestUtil.setField(existing, "id", UUID.randomUUID());
        when(nodeRepository.existsByIdAndResearchRunId(from, RUN_ID)).thenReturn(true);
        when(nodeRepository.existsByIdAndResearchRunId(to, RUN_ID)).thenReturn(true);
        when(edgeRepository.findByResearchRunIdAndIdempotencyKey(RUN_ID, "e-idem"))
                .thenReturn(Optional.of(existing));

        // Same key, different relation → conflict, not a silent replay.
        var command = new RecordProvenanceEdgeCommand(from, to, ProvenanceEdgeRelation.SUPPORTS, null, null, "e-idem");
        assertThatThrownBy(() -> service.recordEdge(PROJECT_ID, RUN_ID, command))
                .isInstanceOf(ConflictException.class);
        verify(edgeRepository, never()).save(any());
    }

    // ---- reads ------------------------------------------------------------

    @Test
    void listNodes_concealsCrossProjectRunAsNotFound() {
        assertThatThrownBy(() -> service.listNodes(OTHER_PROJECT_ID, RUN_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getProvenanceChain_walksIncomingEdgesBackToSupportingNodes() {
        var rootId = UUID.randomUUID();
        var upstreamId = UUID.randomUUID();
        var root = node(ProvenanceNodeKind.FINAL_PROSE, "para-1", rootId, ProvenanceRecordStatus.ACTIVE);
        var upstream = node(ProvenanceNodeKind.CANDIDATE_SOURCE, "src-9", upstreamId, ProvenanceRecordStatus.ACTIVE);
        var edge = new ResearchProvenanceEdge(run, upstreamId, rootId, ProvenanceEdgeRelation.SUPPORTS);
        TestUtil.setField(edge, "id", UUID.randomUUID());

        when(nodeRepository.findByIdAndResearchRunId(rootId, RUN_ID)).thenReturn(Optional.of(root));
        when(nodeRepository.findByIdAndResearchRunId(upstreamId, RUN_ID)).thenReturn(Optional.of(upstream));
        when(edgeRepository.findByResearchRunIdAndToNodeIdAndStatus(RUN_ID, rootId, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of(edge));
        when(edgeRepository.findByResearchRunIdAndToNodeIdAndStatus(RUN_ID, upstreamId, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of());

        var chain = service.getProvenanceChain(PROJECT_ID, RUN_ID, rootId, null);

        assertThat(chain.rootNodeId()).isEqualTo(rootId);
        assertThat(chain.nodes())
                .extracting(ResearchProvenanceNode::getId)
                .containsExactlyInAnyOrder(rootId, upstreamId);
        assertThat(chain.edges()).hasSize(1);
        assertThat(chain.truncated()).isFalse();
    }
}

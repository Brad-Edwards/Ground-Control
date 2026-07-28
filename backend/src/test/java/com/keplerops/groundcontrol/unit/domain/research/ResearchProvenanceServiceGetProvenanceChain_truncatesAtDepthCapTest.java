package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceEdgeRelation;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceNodeKind;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceRecordStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceEdge;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceNode;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.repository.ResearchProvenanceEdgeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchProvenanceNodeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
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
class ResearchProvenanceServiceGetProvenanceChain_truncatesAtDepthCapTest {
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

    @Test
    void getProvenanceChain_truncatesAtDepthCap() {
        // Linear chain root <- n1 <- n2 ... ; with depth=1 only the first hop is walked.
        var rootId = UUID.randomUUID();
        var n1 = UUID.randomUUID();
        var root = node(ProvenanceNodeKind.SYNTHESIS_CLAIM, "claim-1", rootId, ProvenanceRecordStatus.ACTIVE);
        var node1 = node(ProvenanceNodeKind.CHARTING_CELL, "cell-1", n1, ProvenanceRecordStatus.ACTIVE);
        var e1 = new ResearchProvenanceEdge(run, n1, rootId, ProvenanceEdgeRelation.SUPPORTS);
        TestUtil.setField(e1, "id", UUID.randomUUID());

        when(nodeRepository.findByIdAndResearchRunId(rootId, RUN_ID)).thenReturn(Optional.of(root));
        when(nodeRepository.findByIdAndResearchRunId(n1, RUN_ID)).thenReturn(Optional.of(node1));
        when(edgeRepository.findByResearchRunIdAndToNodeIdAndStatus(RUN_ID, rootId, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of(e1));
        // n1 has further upstream, so depth=1 must report truncation.
        var n2 = UUID.randomUUID();
        var e2 = new ResearchProvenanceEdge(run, n2, n1, ProvenanceEdgeRelation.SUPPORTS);
        TestUtil.setField(e2, "id", UUID.randomUUID());
        when(edgeRepository.findByResearchRunIdAndToNodeIdAndStatus(RUN_ID, n1, ProvenanceRecordStatus.ACTIVE))
                .thenReturn(List.of(e2));

        var chain = service.getProvenanceChain(PROJECT_ID, RUN_ID, rootId, 1);

        assertThat(chain.maxDepth()).isEqualTo(1);
        assertThat(chain.truncated()).isTrue();
        assertThat(chain.nodes()).extracting(ResearchProvenanceNode::getId).contains(rootId, n1);
    }

    @Test
    void getProvenanceChain_rejectsUnknownRootNode() {
        var missing = UUID.randomUUID();
        when(nodeRepository.findByIdAndResearchRunId(missing, RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProvenanceChain(PROJECT_ID, RUN_ID, missing, null))
                .isInstanceOf(NotFoundException.class);
    }
}

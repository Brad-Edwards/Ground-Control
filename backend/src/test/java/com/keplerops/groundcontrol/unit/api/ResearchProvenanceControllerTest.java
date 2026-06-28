package com.keplerops.groundcontrol.unit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.api.research.ResearchProvenanceController;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceEdgeRelation;
import com.keplerops.groundcontrol.domain.research.model.ProvenanceNodeKind;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceEdge;
import com.keplerops.groundcontrol.domain.research.model.ResearchProvenanceNode;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.service.ProvenanceChain;
import com.keplerops.groundcontrol.domain.research.service.RecordProvenanceEdgeCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordProvenanceNodeCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchProvenanceService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller slice for {@link ResearchProvenanceController} (GC-RSCH-R004 /
 * GC-RSCH-N002 / GC-RSCH-N004, ADR-069). Verifies status codes, DTO validation,
 * and that each request DTO's {@code toCommand()} is forwarded to the service
 * with the request-derived fields intact.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ResearchProvenanceController.class)
class ResearchProvenanceControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearchProvenanceService provenanceService;

    @MockitoBean
    private ProjectService projectService;

    private final Project project = project();
    private final ResearchRun run = run();

    @Test
    void recordNode_returns201AndForwardsCommand() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        var node = node(ProvenanceNodeKind.SYNTHESIS_CLAIM, "claim-7");
        when(provenanceService.recordNode(eq(PROJECT_ID), eq(RUN_ID), any())).thenReturn(node);

        mockMvc.perform(post("/api/v1/research-runs/{runId}/provenance/nodes", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SYNTHESIS_CLAIM\",\"subjectKey\":\"claim-7\","
                                + "\"externalIdentifier\":\"10.1000/xyz\",\"summary\":\"bounded\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("SYNTHESIS_CLAIM"))
                .andExpect(jsonPath("$.subjectKey").value("claim-7"));

        ArgumentCaptor<RecordProvenanceNodeCommand> captor = ArgumentCaptor.forClass(RecordProvenanceNodeCommand.class);
        verify(provenanceService).recordNode(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        assertThat(captor.getValue().kind()).isEqualTo(ProvenanceNodeKind.SYNTHESIS_CLAIM);
        assertThat(captor.getValue().subjectKey()).isEqualTo("claim-7");
        assertThat(captor.getValue().externalIdentifier()).isEqualTo("10.1000/xyz");
    }

    @Test
    void recordNode_rejectsMissingKindWith422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(post("/api/v1/research-runs/{runId}/provenance/nodes", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectKey\":\"claim-7\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void recordEdge_returns201AndForwardsCommand() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        var from = UUID.randomUUID();
        var to = UUID.randomUUID();
        var edge = new ResearchProvenanceEdge(run, from, to, ProvenanceEdgeRelation.SUPPORTS);
        TestUtil.setField(edge, "id", UUID.randomUUID());
        when(provenanceService.recordEdge(eq(PROJECT_ID), eq(RUN_ID), any())).thenReturn(edge);

        mockMvc.perform(post("/api/v1/research-runs/{runId}/provenance/edges", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromNodeId\":\"" + from + "\",\"toNodeId\":\"" + to
                                + "\",\"relation\":\"SUPPORTS\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relation").value("SUPPORTS"))
                .andExpect(jsonPath("$.fromNodeId").value(from.toString()))
                .andExpect(jsonPath("$.toNodeId").value(to.toString()));

        ArgumentCaptor<RecordProvenanceEdgeCommand> captor = ArgumentCaptor.forClass(RecordProvenanceEdgeCommand.class);
        verify(provenanceService).recordEdge(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        assertThat(captor.getValue().fromNodeId()).isEqualTo(from);
        assertThat(captor.getValue().toNodeId()).isEqualTo(to);
        assertThat(captor.getValue().relation()).isEqualTo(ProvenanceEdgeRelation.SUPPORTS);
    }

    @Test
    void listNodes_returns200() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(provenanceService.listNodes(PROJECT_ID, RUN_ID))
                .thenReturn(List.of(node(ProvenanceNodeKind.QUERY, "q-1")));

        mockMvc.perform(get("/api/v1/research-runs/{runId}/provenance/nodes", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("QUERY"));
    }

    @Test
    void chain_returns200WithRootAndNodes() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        var rootId = UUID.randomUUID();
        var root = node(ProvenanceNodeKind.FINAL_PROSE, "para-1");
        TestUtil.setField(root, "id", rootId);
        when(provenanceService.getProvenanceChain(eq(PROJECT_ID), eq(RUN_ID), eq(rootId), any()))
                .thenReturn(new ProvenanceChain(rootId, 25, false, List.of(root), List.of()));

        mockMvc.perform(get("/api/v1/research-runs/{runId}/provenance/nodes/{nodeId}/chain", RUN_ID, rootId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootNodeId").value(rootId.toString()))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    private ResearchProvenanceNode node(ProvenanceNodeKind kind, String subjectKey) {
        var n = new ResearchProvenanceNode(run, kind, subjectKey);
        TestUtil.setField(n, "id", UUID.randomUUID());
        return n;
    }

    private Project project() {
        var p = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        TestUtil.setField(p, "id", PROJECT_ID);
        return p;
    }

    private ResearchRun run() {
        var r = new ResearchRun(project, "RUN-1", AutonomyLevel.COPILOT);
        TestUtil.setField(r, "id", RUN_ID);
        return r;
    }
}

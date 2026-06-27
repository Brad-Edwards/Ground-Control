package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.research.ResearchRunController;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.service.AdvanceStageCommand;
import com.keplerops.groundcontrol.domain.research.service.FailRunCommand;
import com.keplerops.groundcontrol.domain.research.service.GateDecisionCommand;
import com.keplerops.groundcontrol.domain.research.service.RecordArtifactCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunSnapshot;
import com.keplerops.groundcontrol.domain.research.service.StartResearchRunCommand;
import java.time.Instant;
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
 * Controller slice for {@link ResearchRunController} (GC-RSCH-R001/R003, ADR-064 / ADR-065).
 * Verifies status codes, DTO validation, error envelopes, and that each request DTO's
 * {@code toCommand(...)} is forwarded to the service with the request-derived fields intact.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ResearchRunController.class)
class ResearchRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearchRunService researchRunService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final Instant NOW = Instant.parse("2026-06-25T00:00:00Z");

    private ResearchRun makeRun(ResearchRunStage stage, ResearchRunStatus status) {
        var project = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        setField(project, "id", PROJECT_ID);
        var run = new ResearchRun(project, "RUN-1", AutonomyLevel.COPILOT);
        setField(run, "id", RUN_ID);
        setField(run, "currentStage", stage);
        setField(run, "status", status);
        setField(run, "createdAt", NOW);
        setField(run, "updatedAt", NOW);
        return run;
    }

    @Test
    void start_returns201AndRunBody() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.start(any(StartResearchRunCommand.class)))
                .thenReturn(makeRun(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS));

        mockMvc.perform(post("/api/v1/research-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uid\":\"RUN-1\",\"autonomyLevel\":\"COPILOT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").value("RUN-1"))
                .andExpect(jsonPath("$.currentStage").value("METHODOLOGY_SELECTION"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        var captor = ArgumentCaptor.forClass(StartResearchRunCommand.class);
        verify(researchRunService).start(captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.projectId()).isEqualTo(PROJECT_ID);
        assertThat(cmd.uid()).isEqualTo("RUN-1");
        assertThat(cmd.autonomyLevel()).isEqualTo(AutonomyLevel.COPILOT);
    }

    @Test
    void start_blankUid_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/research-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uid\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getById_returnsRun() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.getById(PROJECT_ID, RUN_ID))
                .thenReturn(makeRun(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS));

        mockMvc.perform(get("/api/v1/research-runs/{id}", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("SOURCE_SEARCH"));
    }

    @Test
    void recordArtifact_returns201AndForwardsCommand() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        var run = makeRun(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS);
        var artifact = new ResearchRunArtifact(run, ResearchArtifactType.SEARCH_LOG, 1);
        setField(artifact, "id", UUID.randomUUID());
        setField(artifact, "createdAt", NOW);
        setField(artifact, "updatedAt", NOW);
        when(researchRunService.recordArtifact(eq(PROJECT_ID), eq(RUN_ID), any(RecordArtifactCommand.class)))
                .thenReturn(artifact);

        mockMvc.perform(post("/api/v1/research-runs/{id}/artifacts", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artifactType\":\"SEARCH_LOG\",\"candidateSources\":12}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.artifactType").value("SEARCH_LOG"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        var captor = ArgumentCaptor.forClass(RecordArtifactCommand.class);
        verify(researchRunService).recordArtifact(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.artifactType()).isEqualTo(ResearchArtifactType.SEARCH_LOG);
        assertThat(cmd.candidateSources()).isEqualTo(12);
    }

    @Test
    void advance_forwardsTargetStage() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.advanceStage(
                        PROJECT_ID, RUN_ID, new AdvanceStageCommand(ResearchRunStage.PROTOCOL_PLANNING)))
                .thenReturn(makeRun(ResearchRunStage.PROTOCOL_PLANNING, ResearchRunStatus.IN_PROGRESS));

        mockMvc.perform(post("/api/v1/research-runs/{id}/advance", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStage\":\"PROTOCOL_PLANNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("PROTOCOL_PLANNING"));
        verify(researchRunService)
                .advanceStage(PROJECT_ID, RUN_ID, new AdvanceStageCommand(ResearchRunStage.PROTOCOL_PLANNING));
    }

    @Test
    void decideGate_forwardsGatePointFromPath() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        var run = makeRun(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS);
        var gate = new com.keplerops.groundcontrol.domain.research.model.ResearchRunGate(
                run,
                ResearchGatePoint.METHOD_DECISION,
                com.keplerops.groundcontrol.domain.research.model.ResearchGateBehavior.REQUIRE_HUMAN,
                "test");
        setField(gate, "id", UUID.randomUUID());
        setField(gate, "createdAt", NOW);
        setField(gate, "updatedAt", NOW);
        when(researchRunService.resolveGate(eq(PROJECT_ID), eq(RUN_ID), any(GateDecisionCommand.class)))
                .thenReturn(gate);

        mockMvc.perform(post("/api/v1/research-runs/{id}/gates/decision", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatePoint\":\"METHOD_DECISION\",\"outcome\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gatePoint").value("METHOD_DECISION"));

        var captor = ArgumentCaptor.forClass(GateDecisionCommand.class);
        verify(researchRunService).resolveGate(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.gatePoint()).isEqualTo(ResearchGatePoint.METHOD_DECISION);
        assertThat(cmd.outcome()).isEqualTo(ResearchGateDecisionOutcome.APPROVED);
    }

    @Test
    void snapshot_returnsObservabilityView() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        var snapshot = new ResearchRunSnapshot(
                RUN_ID,
                "research-p",
                "RUN-1",
                ResearchRunStage.SCREENING,
                ResearchRunStatus.IN_PROGRESS,
                List.of(new ResearchRunSnapshot.ArtifactReadiness(
                        ResearchRunStage.SOURCE_SEARCH,
                        ResearchArtifactType.SEARCH_LOG,
                        com.keplerops.groundcontrol.domain.research.model.ResearchArtifactReadiness.READY)),
                List.of(new ResearchRunSnapshot.PendingGate(
                        ResearchGatePoint.SEARCH_DECISION, ResearchRunStage.SOURCE_SEARCH)),
                new ResearchRunSnapshot.SourceCounts(20, 8, 12, 5, 2),
                new ResearchRunSnapshot.Cost(null, null, null, 0, 0),
                null);
        when(researchRunService.getSnapshot(PROJECT_ID, RUN_ID)).thenReturn(snapshot);

        mockMvc.perform(get("/api/v1/research-runs/{id}/snapshot", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("SCREENING"))
                .andExpect(jsonPath("$.sourceCounts.candidateSources").value(20))
                .andExpect(jsonPath("$.pendingGates[0].gatePoint").value("SEARCH_DECISION"))
                .andExpect(jsonPath("$.artifactReadiness[0].readiness").value("READY"));
    }

    @Test
    void resume_returnsRun() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.resume(PROJECT_ID, RUN_ID))
                .thenReturn(makeRun(ResearchRunStage.SCREENING, ResearchRunStatus.IN_PROGRESS));
        mockMvc.perform(post("/api/v1/research-runs/{id}/resume", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void fail_recordsFailure() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.fail(eq(PROJECT_ID), eq(RUN_ID), any()))
                .thenReturn(makeRun(ResearchRunStage.SCREENING, ResearchRunStatus.FAILED));
        mockMvc.perform(post("/api/v1/research-runs/{id}/fail", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorCode\":\"x\",\"errorClass\":\"RETRYABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        var captor = ArgumentCaptor.forClass(FailRunCommand.class);
        verify(researchRunService).fail(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.errorCode()).isEqualTo("x");
        assertThat(cmd.errorClass()).isEqualTo("RETRYABLE");
    }

    @Test
    void decideGate_unknownOutcome_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        mockMvc.perform(post("/api/v1/research-runs/{id}/gates/decision", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatePoint\":\"METHOD_DECISION\",\"outcome\":\"NOPE\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}

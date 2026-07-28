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
import com.keplerops.groundcontrol.domain.research.model.GateRecommendationProvenance;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGateDecisionOutcome;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGateDecisionLog;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunReviewComment;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentProvenance;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentTarget;
import com.keplerops.groundcontrol.domain.research.service.AddReviewCommentCommand;
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

/** Split from ResearchRunControllerTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
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
    private static final UUID COMMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
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

    private ResearchRunGateDecisionLog makeDecisionLog() {
        var run = makeRun(ResearchRunStage.METHODOLOGY_SELECTION, ResearchRunStatus.IN_PROGRESS);
        var log = new ResearchRunGateDecisionLog(
                run,
                ResearchGatePoint.METHOD_DECISION,
                ResearchRunStage.METHODOLOGY_SELECTION,
                ResearchGateDecisionOutcome.APPROVED,
                "test-actor",
                NOW);
        setField(log, "id", UUID.randomUUID());
        setField(log, "createdAt", NOW);
        setField(log, "updatedAt", NOW);
        return log;
    }

    private ResearchRunReviewComment makeReviewComment() {
        var run = makeRun(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS);
        var comment = new ResearchRunReviewComment(
                run, ReviewCommentTarget.RUN, "This needs revision.", ReviewCommentProvenance.HUMAN_REVIEW, "actor");
        setField(comment, "id", COMMENT_ID);
        setField(comment, "createdAt", NOW);
        setField(comment, "updatedAt", NOW);
        return comment;
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
    void decideGate_withRecommendationFields_forwardsAllFields() throws Exception {
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
                        .content("{\"gatePoint\":\"METHOD_DECISION\",\"outcome\":\"APPROVED\","
                                + "\"recommendationOptionId\":\"opt-1\","
                                + "\"recommendationSummary\":\"Agent recommends option A.\","
                                + "\"recommendationProvenance\":\"AGENT\","
                                + "\"questionKey\":\"q-method-1\","
                                + "\"sourceActionId\":\"action-42\"}"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(GateDecisionCommand.class);
        verify(researchRunService).resolveGate(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.recommendationOptionId()).isEqualTo("opt-1");
        assertThat(cmd.recommendationSummary()).isEqualTo("Agent recommends option A.");
        assertThat(cmd.recommendationProvenance()).isEqualTo(GateRecommendationProvenance.AGENT);
        assertThat(cmd.questionKey()).isEqualTo("q-method-1");
        assertThat(cmd.sourceActionId()).isEqualTo("action-42");
    }

    @Test
    void decideGate_recommendationOptionIdTooLong_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        var oversized = "x".repeat(201);
        mockMvc.perform(post("/api/v1/research-runs/{id}/gates/decision", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatePoint\":\"METHOD_DECISION\",\"outcome\":\"APPROVED\","
                                + "\"recommendationOptionId\":\"" + oversized + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void decideGate_badRecommendationProvenance_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        mockMvc.perform(post("/api/v1/research-runs/{id}/gates/decision", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatePoint\":\"METHOD_DECISION\",\"outcome\":\"APPROVED\","
                                + "\"recommendationProvenance\":\"UNKNOWN_PROV\"}"))
                .andExpect(status().isUnprocessableEntity());
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

    @Test
    void list_returnsRuns() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.listByProject(PROJECT_ID))
                .thenReturn(List.of(makeRun(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS)));

        mockMvc.perform(get("/api/v1/research-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uid").value("RUN-1"))
                .andExpect(jsonPath("$[0].currentStage").value("SOURCE_SEARCH"));
    }

    @Test
    void getByUid_returnsRun() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.getByUid(PROJECT_ID, "RUN-1"))
                .thenReturn(makeRun(ResearchRunStage.SCREENING, ResearchRunStatus.IN_PROGRESS));

        mockMvc.perform(get("/api/v1/research-runs/uid/{uid}", "RUN-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("SCREENING"));
    }

    @Test
    void listArtifacts_returnsArtifacts() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        var run = makeRun(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS);
        var artifact = new ResearchRunArtifact(run, ResearchArtifactType.SEARCH_LOG, 1);
        setField(artifact, "id", UUID.randomUUID());
        setField(artifact, "createdAt", NOW);
        setField(artifact, "updatedAt", NOW);
        when(researchRunService.listArtifacts(PROJECT_ID, RUN_ID)).thenReturn(List.of(artifact));

        mockMvc.perform(get("/api/v1/research-runs/{id}/artifacts", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].artifactType").value("SEARCH_LOG"));
    }

    @Test
    void listGates_returnsGates() throws Exception {
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
        when(researchRunService.listGates(PROJECT_ID, RUN_ID)).thenReturn(List.of(gate));

        mockMvc.perform(get("/api/v1/research-runs/{id}/gates", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].gatePoint").value("METHOD_DECISION"));
    }

    @Test
    void stop_returnsStoppedRun() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.stop(PROJECT_ID, RUN_ID))
                .thenReturn(makeRun(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.STOPPED));

        mockMvc.perform(post("/api/v1/research-runs/{id}/stop", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));
    }

    @Test
    void complete_returnsCompletedRun() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.complete(PROJECT_ID, RUN_ID))
                .thenReturn(makeRun(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.COMPLETED));

        mockMvc.perform(post("/api/v1/research-runs/{id}/complete", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void recordUsage_forwardsTokensAndCost() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.recordUsage(PROJECT_ID, RUN_ID, 100L, 250L))
                .thenReturn(makeRun(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS));

        mockMvc.perform(post("/api/v1/research-runs/{id}/usage", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokens\":100,\"costUsdMicros\":250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        verify(researchRunService).recordUsage(PROJECT_ID, RUN_ID, 100L, 250L);
    }

    // ---- Gate Decision Log (ADR-066) ----

    @Test
    void listGateDecisionLog_returnsDecisionLog() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.listGateDecisionLog(PROJECT_ID, RUN_ID)).thenReturn(List.of(makeDecisionLog()));

        mockMvc.perform(get("/api/v1/research-runs/{id}/gates/decision-log", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].gatePoint").value("METHOD_DECISION"))
                .andExpect(jsonPath("$[0].decisionOutcome").value("APPROVED"));
    }

    // ---- Review Comments (ADR-067) ----

    @Test
    void addReviewComment_returns201AndForwardsCommand() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.addReviewComment(eq(PROJECT_ID), eq(RUN_ID), any(AddReviewCommentCommand.class)))
                .thenReturn(makeReviewComment());

        mockMvc.perform(post("/api/v1/research-runs/{id}/review-comments", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"RUN\","
                                + "\"body\":\"This needs revision.\","
                                + "\"provenance\":\"HUMAN_REVIEW\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetType").value("RUN"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        var captor = ArgumentCaptor.forClass(AddReviewCommentCommand.class);
        verify(researchRunService).addReviewComment(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.targetType()).isEqualTo(ReviewCommentTarget.RUN);
        assertThat(cmd.body()).isEqualTo("This needs revision.");
        assertThat(cmd.provenance()).isEqualTo(ReviewCommentProvenance.HUMAN_REVIEW);
    }

    @Test
    void addReviewComment_missingTargetType_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        mockMvc.perform(post("/api/v1/research-runs/{id}/review-comments", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"missing target\",\"provenance\":\"HUMAN_REVIEW\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void addReviewComment_missingBody_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        mockMvc.perform(post("/api/v1/research-runs/{id}/review-comments", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"RUN\",\"provenance\":\"HUMAN_REVIEW\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void addReviewComment_badTargetType_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        mockMvc.perform(post("/api/v1/research-runs/{id}/review-comments", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"INVALID\",\"body\":\"x\",\"provenance\":\"HUMAN_REVIEW\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}

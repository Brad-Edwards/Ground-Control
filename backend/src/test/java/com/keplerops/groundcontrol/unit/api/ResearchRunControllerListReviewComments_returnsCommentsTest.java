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
import com.keplerops.groundcontrol.domain.research.model.DisclosureEntryFamily;
import com.keplerops.groundcontrol.domain.research.model.DisclosureUncertaintyCategory;
import com.keplerops.groundcontrol.domain.research.model.RationaleEntryKind;
import com.keplerops.groundcontrol.domain.research.model.RationaleEvidenceBasis;
import com.keplerops.groundcontrol.domain.research.model.RationaleProvenance;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosure;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosureEntry;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunRationaleEntry;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunReviewComment;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentProvenance;
import com.keplerops.groundcontrol.domain.research.model.ReviewCommentTarget;
import com.keplerops.groundcontrol.domain.research.service.AddDisclosureEntryCommand;
import com.keplerops.groundcontrol.domain.research.service.AddRationaleEntryCommand;
import com.keplerops.groundcontrol.domain.research.service.CreateDisclosureCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import com.keplerops.groundcontrol.domain.research.service.ResolveReviewCommentCommand;
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
class ResearchRunControllerListReviewComments_returnsCommentsTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearchRunService researchRunService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID COMMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID DISCLOSURE_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID ARTIFACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
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

    private ResearchRunReviewComment makeReviewComment() {
        var run = makeRun(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS);
        var comment = new ResearchRunReviewComment(
                run, ReviewCommentTarget.RUN, "This needs revision.", ReviewCommentProvenance.HUMAN_REVIEW, "actor");
        setField(comment, "id", COMMENT_ID);
        setField(comment, "createdAt", NOW);
        setField(comment, "updatedAt", NOW);
        return comment;
    }

    private ResearchRunRationaleEntry makeRationaleEntry() {
        var run = makeRun(ResearchRunStage.SOURCE_SEARCH, ResearchRunStatus.IN_PROGRESS);
        var entry = new ResearchRunRationaleEntry(
                run,
                ResearchRunStage.SOURCE_SEARCH,
                RationaleEntryKind.SEARCH_DECISION,
                RationaleEvidenceBasis.USER_DECISION,
                RationaleProvenance.HUMAN,
                "search-query-1",
                "Chose this query because it covers the domain adequately.",
                "actor",
                NOW);
        setField(entry, "id", UUID.randomUUID());
        setField(entry, "createdAt", NOW);
        setField(entry, "updatedAt", NOW);
        return entry;
    }

    private ResearchRunDisclosure makeDisclosure() {
        var run = makeRun(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS);
        var disclosure = new ResearchRunDisclosure(run, ARTIFACT_ID, 1, false, false, false, "actor");
        setField(disclosure, "id", DISCLOSURE_ID);
        setField(disclosure, "createdAt", NOW);
        setField(disclosure, "updatedAt", NOW);
        return disclosure;
    }

    private ResearchRunDisclosureEntry makeDisclosureEntry() {
        var disclosure = makeDisclosure();
        var entry = new ResearchRunDisclosureEntry(
                disclosure, DisclosureEntryFamily.AI_GENERATED_PART, "Section 3 was drafted by AI.", "actor");
        setField(entry, "id", UUID.randomUUID());
        setField(entry, "createdAt", NOW);
        setField(entry, "updatedAt", NOW);
        return entry;
    }

    @Test
    void listReviewComments_returnsComments() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.listReviewComments(PROJECT_ID, RUN_ID)).thenReturn(List.of(makeReviewComment()));

        mockMvc.perform(get("/api/v1/research-runs/{id}/review-comments", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].provenance").value("HUMAN_REVIEW"));
    }

    @Test
    void resolveReviewComment_returnsResolvedComment() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        var comment = makeReviewComment();
        comment.resolve("Fixed by updating methodology.", "resolver-actor");
        when(researchRunService.resolveReviewComment(
                        eq(PROJECT_ID), eq(RUN_ID), eq(COMMENT_ID), any(ResolveReviewCommentCommand.class)))
                .thenReturn(comment);

        mockMvc.perform(post("/api/v1/research-runs/{id}/review-comments/{commentId}/resolve", RUN_ID, COMMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionSummary\":\"Fixed by updating methodology.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        var captor = ArgumentCaptor.forClass(ResolveReviewCommentCommand.class);
        verify(researchRunService).resolveReviewComment(eq(PROJECT_ID), eq(RUN_ID), eq(COMMENT_ID), captor.capture());
        assertThat(captor.getValue().resolutionSummary()).isEqualTo("Fixed by updating methodology.");
    }

    @Test
    void resolveReviewComment_resolutionSummaryTooLong_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        var oversized = "x".repeat(1001);
        mockMvc.perform(post("/api/v1/research-runs/{id}/review-comments/{commentId}/resolve", RUN_ID, COMMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionSummary\":\"" + oversized + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- Rationale Ledger (ADR-068) ----

    @Test
    void addRationaleEntry_returns201AndForwardsCommand() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.addRationaleEntry(eq(PROJECT_ID), eq(RUN_ID), any(AddRationaleEntryCommand.class)))
                .thenReturn(makeRationaleEntry());

        mockMvc.perform(post("/api/v1/research-runs/{id}/rationale", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"SOURCE_SEARCH\","
                                + "\"kind\":\"SEARCH_DECISION\","
                                + "\"evidenceBasis\":\"USER_DECISION\","
                                + "\"provenance\":\"HUMAN\","
                                + "\"subjectKey\":\"search-query-1\","
                                + "\"rationaleSummary\":\"Chose this query.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("SEARCH_DECISION"))
                .andExpect(jsonPath("$.evidenceBasis").value("USER_DECISION"));

        var captor = ArgumentCaptor.forClass(AddRationaleEntryCommand.class);
        verify(researchRunService).addRationaleEntry(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.stage()).isEqualTo(ResearchRunStage.SOURCE_SEARCH);
        assertThat(cmd.kind()).isEqualTo(RationaleEntryKind.SEARCH_DECISION);
        assertThat(cmd.evidenceBasis()).isEqualTo(RationaleEvidenceBasis.USER_DECISION);
        assertThat(cmd.provenance()).isEqualTo(RationaleProvenance.HUMAN);
        assertThat(cmd.subjectKey()).isEqualTo("search-query-1");
        assertThat(cmd.rationaleSummary()).isEqualTo("Chose this query.");
    }

    @Test
    void addRationaleEntry_missingStage_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        mockMvc.perform(post("/api/v1/research-runs/{id}/rationale", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SEARCH_DECISION\","
                                + "\"evidenceBasis\":\"USER_DECISION\","
                                + "\"provenance\":\"HUMAN\","
                                + "\"subjectKey\":\"k\","
                                + "\"rationaleSummary\":\"s\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void addRationaleEntry_badKind_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        mockMvc.perform(post("/api/v1/research-runs/{id}/rationale", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"SOURCE_SEARCH\","
                                + "\"kind\":\"NONSENSE\","
                                + "\"evidenceBasis\":\"USER_DECISION\","
                                + "\"provenance\":\"HUMAN\","
                                + "\"subjectKey\":\"k\","
                                + "\"rationaleSummary\":\"s\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void listRationale_returnsEntries() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.listRationale(PROJECT_ID, RUN_ID)).thenReturn(List.of(makeRationaleEntry()));

        mockMvc.perform(get("/api/v1/research-runs/{id}/rationale", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("SEARCH_DECISION"))
                .andExpect(jsonPath("$[0].provenance").value("HUMAN"));
    }

    // ---- Accountability Disclosure (ADR-068 §4) ----

    @Test
    void createDisclosure_returns201AndForwardsCommand() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.createDisclosure(eq(PROJECT_ID), eq(RUN_ID), any(CreateDisclosureCommand.class)))
                .thenReturn(makeDisclosure());

        mockMvc.perform(post("/api/v1/research-runs/{id}/disclosure", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalArtifactId\":\"" + ARTIFACT_ID + "\","
                                + "\"finalAttemptNo\":1,"
                                + "\"aiPartsDeclaredNone\":false,"
                                + "\"uncertaintyDeclaredNone\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CURRENT"))
                .andExpect(jsonPath("$.finalArtifactId").value(ARTIFACT_ID.toString()));

        var captor = ArgumentCaptor.forClass(CreateDisclosureCommand.class);
        verify(researchRunService).createDisclosure(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.finalArtifactId()).isEqualTo(ARTIFACT_ID);
        assertThat(cmd.finalAttemptNo()).isEqualTo(1);
        assertThat(cmd.aiPartsDeclaredNone()).isFalse();
        assertThat(cmd.uncertaintyDeclaredNone()).isFalse();
    }

    @Test
    void createDisclosure_missingFinalArtifactId_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        mockMvc.perform(post("/api/v1/research-runs/{id}/disclosure", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalAttemptNo\":1}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getDisclosure_returnsCurrentDisclosure() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.getDisclosure(PROJECT_ID, RUN_ID)).thenReturn(makeDisclosure());

        mockMvc.perform(get("/api/v1/research-runs/{id}/disclosure", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CURRENT"))
                .andExpect(jsonPath("$.finalArtifactId").value(ARTIFACT_ID.toString()));
    }

    @Test
    void addDisclosureEntry_returns201AndForwardsCommand() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.addDisclosureEntry(
                        eq(PROJECT_ID), eq(RUN_ID), eq(DISCLOSURE_ID), any(AddDisclosureEntryCommand.class)))
                .thenReturn(makeDisclosureEntry());

        mockMvc.perform(post("/api/v1/research-runs/{id}/disclosure/{disclosureId}/entries", RUN_ID, DISCLOSURE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"family\":\"AI_GENERATED_PART\"," + "\"summary\":\"Section 3 was drafted by AI.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.family").value("AI_GENERATED_PART"));

        var captor = ArgumentCaptor.forClass(AddDisclosureEntryCommand.class);
        verify(researchRunService).addDisclosureEntry(eq(PROJECT_ID), eq(RUN_ID), eq(DISCLOSURE_ID), captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.family()).isEqualTo(DisclosureEntryFamily.AI_GENERATED_PART);
        assertThat(cmd.summary()).isEqualTo("Section 3 was drafted by AI.");
    }

    @Test
    void addDisclosureEntry_missingFamily_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        mockMvc.perform(post("/api/v1/research-runs/{id}/disclosure/{disclosureId}/entries", RUN_ID, DISCLOSURE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"missing family\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void addDisclosureEntry_badFamily_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        mockMvc.perform(post("/api/v1/research-runs/{id}/disclosure/{disclosureId}/entries", RUN_ID, DISCLOSURE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"family\":\"INVALID_FAMILY\",\"summary\":\"s\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void addDisclosureEntry_withUncertaintyCategory_forwardsField() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        var entry = makeDisclosureEntry();
        entry.setUncertaintyCategory(DisclosureUncertaintyCategory.ACCESS_GAP);
        when(researchRunService.addDisclosureEntry(
                        eq(PROJECT_ID), eq(RUN_ID), eq(DISCLOSURE_ID), any(AddDisclosureEntryCommand.class)))
                .thenReturn(entry);

        mockMvc.perform(post("/api/v1/research-runs/{id}/disclosure/{disclosureId}/entries", RUN_ID, DISCLOSURE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"family\":\"UNRESOLVED_UNCERTAINTY\","
                                + "\"uncertaintyCategory\":\"ACCESS_GAP\","
                                + "\"summary\":\"Three sources were paywalled.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uncertaintyCategory").value("ACCESS_GAP"));

        var captor = ArgumentCaptor.forClass(AddDisclosureEntryCommand.class);
        verify(researchRunService).addDisclosureEntry(eq(PROJECT_ID), eq(RUN_ID), eq(DISCLOSURE_ID), captor.capture());
        assertThat(captor.getValue().uncertaintyCategory()).isEqualTo(DisclosureUncertaintyCategory.ACCESS_GAP);
    }
}

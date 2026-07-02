package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.research.ResearchRunController;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContract;
import com.keplerops.groundcontrol.domain.research.model.ProtocolCoverageDisposition;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlan;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanCoverage;
import com.keplerops.groundcontrol.domain.research.model.ProtocolPlanSection;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSectionKind;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import com.keplerops.groundcontrol.domain.research.service.ProtocolPlanAggregate;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GC-RSCH-F008 / GC-RSCH-F009 / ADR-081 — controller slice for the protocol
 * plan endpoints on {@link ResearchRunController}.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ResearchRunController.class)
class ResearchRunProtocolPlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearchRunService researchRunService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SELECTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID METHODOLOGY_ARTIFACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID CONTRACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000050");
    private static final UUID PROTOCOL_ARTIFACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000060");
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000070");
    private static final Instant NOW = Instant.parse("2026-06-25T00:00:00Z");

    private ProtocolPlanAggregate aggregate() {
        var project = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        setField(project, "id", PROJECT_ID);
        var run = new ResearchRun(project, "RUN-1", AutonomyLevel.COPILOT);
        setField(run, "id", RUN_ID);
        var sel = new ResearchRunMethodologySelection(run, "systematic", "actor");
        setField(sel, "id", SELECTION_ID);
        var contract = new MethodologyRequirementsContract(run, sel, METHODOLOGY_ARTIFACT_ID, 1, "1", "actor");
        setField(contract, "id", CONTRACT_ID);
        var plan = new ProtocolPlan(run, contract, PROTOCOL_ARTIFACT_ID, 1, "1", "systematic", "1", "actor");
        setField(plan, "id", PLAN_ID);
        setField(plan, "createdAt", NOW);
        var coverage = new ProtocolPlanCoverage(
                plan,
                "req-1",
                ProtocolCoverageDisposition.FILLED,
                "answer",
                com.keplerops.groundcontrol.domain.research.model.ProtocolAnswerProvenance.METHODOLOGY_SOURCE,
                null,
                null,
                null,
                "actor");
        setField(coverage, "id", UUID.randomUUID());
        var section = new ProtocolPlanSection(
                plan, "s-eligibility", ProtocolSectionKind.ELIGIBILITY_CRITERIA, null, "summary", "actor");
        setField(section, "id", UUID.randomUUID());
        return new ProtocolPlanAggregate(plan, List.of(coverage), List.of(section));
    }

    @Test
    void record_happyPath_returns201() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.recordProtocolPlan(eq(PROJECT_ID), eq(RUN_ID), any()))
                .thenReturn(aggregate());

        mockMvc.perform(post("/api/v1/research-runs/{id}/protocol-plan", RUN_ID)
                        .param("project", "research-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocolSchemaVersion\":\"1\","
                                + "\"coverages\":[{\"contractEntryKey\":\"req-1\",\"disposition\":\"FILLED\","
                                + "\"answerSummary\":\"answer\",\"answerProvenance\":\"METHODOLOGY_SOURCE\"}],"
                                + "\"sections\":[{\"sectionKey\":\"s-eligibility\",\"sectionKind\":\"ELIGIBILITY_CRITERIA\","
                                + "\"contentSummary\":\"summary\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PLAN_ID.toString()))
                .andExpect(jsonPath("$.methodKey").value("systematic"))
                .andExpect(jsonPath("$.coverages[0].contractEntryKey").value("req-1"))
                .andExpect(jsonPath("$.sections[0].sectionKey").value("s-eligibility"));
    }

    @Test
    void record_missingCoverages_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(post("/api/v1/research-runs/{id}/protocol-plan", RUN_ID)
                        .param("project", "research-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocolSchemaVersion\":\"1\",\"coverages\":[],\"sections\":[]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void get_happyPath_returns200() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.getProtocolPlan(PROJECT_ID, RUN_ID)).thenReturn(aggregate());

        mockMvc.perform(get("/api/v1/research-runs/{id}/protocol-plan", RUN_ID).param("project", "research-p"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactId").value(PROTOCOL_ARTIFACT_ID.toString()))
                .andExpect(jsonPath("$.coverages[0].disposition").value("FILLED"));
    }

    @Test
    void get_notFound_returns404() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.getProtocolPlan(PROJECT_ID, RUN_ID))
                .thenThrow(new NotFoundException("No protocol plan for run " + RUN_ID));

        mockMvc.perform(get("/api/v1/research-runs/{id}/protocol-plan", RUN_ID).param("project", "research-p"))
                .andExpect(status().isNotFound());
    }
}

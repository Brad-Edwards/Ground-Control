package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.riskscenarios.RiskAssessmentResultController;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskAssessmentResultService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(RiskAssessmentResultController.class)
class RiskAssessmentResultControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RiskAssessmentResultService riskAssessmentResultService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SCENARIO_ID = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID RESULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");

    private RiskAssessmentResult makeResult() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var scenario = new RiskScenario(project, "RS-001", "Scenario", "Source", "Event", "Object", "Consequence");
        scenario.setTimeHorizon("12 months");
        setField(scenario, "id", SCENARIO_ID);
        var profile = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        setField(profile, "id", PROFILE_ID);

        var result = new RiskAssessmentResult(project, scenario, profile);
        result.setAnalystIdentity("Security Lead");
        result.setAssessmentAt(NOW);
        result.setInputFactors(Map.of("lossEventFrequency", "moderate"));
        result.setComputedOutputs(Map.of("risk", "high"));
        result.setEvidenceRefs(List.of("EVID-001"));
        result.setNotes("Initial FAIR assessment");
        setField(result, "id", RESULT_ID);
        setField(result, "createdAt", NOW);
        setField(result, "updatedAt", NOW);
        return result;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskAssessmentResultService.create(any())).thenReturn(makeResult());

        mockMvc.perform(post("/api/v1/risk-assessment-results")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "riskScenarioId": "%s",
                                  "methodologyProfileId": "%s",
                                  "analystIdentity": "Security Lead",
                                  "assessmentAt": "2026-04-02T12:00:00Z",
                                  "inputFactors": {"lossEventFrequency": "moderate"},
                                  "computedOutputs": {"risk": "high"},
                                  "evidenceRefs": ["EVID-001"],
                                  "notes": "Initial FAIR assessment"
                                }
                                """
                                        .formatted(SCENARIO_ID, PROFILE_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(RESULT_ID.toString())))
                .andExpect(jsonPath("$.graphNodeId", is("RISK_ASSESSMENT_RESULT:" + RESULT_ID)))
                .andExpect(jsonPath("$.riskScenarioId", is(SCENARIO_ID.toString())))
                .andExpect(jsonPath("$.methodologyProfileId", is(PROFILE_ID.toString())));
    }

    @Test
    void listReturnsResults() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskAssessmentResultService.listByProject(PROJECT_ID)).thenReturn(List.of(makeResult()));

        mockMvc.perform(get("/api/v1/risk-assessment-results").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(RESULT_ID.toString())));
    }

    @Test
    void listByScenarioDispatchesToScenarioQuery() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskAssessmentResultService.listByScenario(PROJECT_ID, SCENARIO_ID))
                .thenReturn(List.of(makeResult()));

        mockMvc.perform(get("/api/v1/risk-assessment-results")
                        .param("project", "ground-control")
                        .param("riskScenarioId", SCENARIO_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(RESULT_ID.toString())))
                .andExpect(jsonPath("$[0].riskScenarioId", is(SCENARIO_ID.toString())));

        verify(riskAssessmentResultService).listByScenario(PROJECT_ID, SCENARIO_ID);
    }

    @Test
    void listByRiskRegisterRecordDispatchesToRecordQuery() throws Exception {
        var recordId = UUID.fromString("00000000-0000-0000-0000-000000000300");
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskAssessmentResultService.listByRiskRegisterRecord(PROJECT_ID, recordId))
                .thenReturn(List.of(makeResult()));

        mockMvc.perform(get("/api/v1/risk-assessment-results")
                        .param("project", "ground-control")
                        .param("riskRegisterRecordId", recordId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(RESULT_ID.toString())));

        verify(riskAssessmentResultService).listByRiskRegisterRecord(PROJECT_ID, recordId);
    }

    @Test
    void updateReturnsResult() throws Exception {
        var result = makeResult();
        result.transitionApprovalState(RiskAssessmentApprovalStatus.SUBMITTED);
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskAssessmentResultService.update(eq(PROJECT_ID), eq(RESULT_ID), any()))
                .thenReturn(result);

        mockMvc.perform(
                        put("/api/v1/risk-assessment-results/{id}", RESULT_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"notes":"Updated notes"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalState", is("SUBMITTED")));
    }

    @Test
    void transitionApprovalStateReturnsResult() throws Exception {
        var result = makeResult();
        result.transitionApprovalState(RiskAssessmentApprovalStatus.SUBMITTED);
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskAssessmentResultService.transitionApprovalState(
                        PROJECT_ID, RESULT_ID, RiskAssessmentApprovalStatus.SUBMITTED))
                .thenReturn(result);

        mockMvc.perform(
                        put("/api/v1/risk-assessment-results/{id}/approval-state", RESULT_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"approvalState":"SUBMITTED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalState", is("SUBMITTED")));
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/risk-assessment-results/{id}", RESULT_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(riskAssessmentResultService).delete(PROJECT_ID, RESULT_ID);
    }
}

package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.riskcontrol.RiskControlAnalysisController;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlCoverageService;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlMappingFeedService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(RiskControlAnalysisController.class)
class RiskControlAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RiskControlCoverageService coverageService;

    @MockitoBean
    private RiskControlMappingFeedService feedService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
    }

    @Test
    void unmappedScenarios_C5a_returnsScenarioList() throws Exception {
        var scenario = new RiskScenario(project, "RS-001", "Phishing", "A", "B", "C", "D");
        setField(scenario, "id", UUID.randomUUID());

        when(coverageService.findUnmappedScenarios(PROJECT_ID)).thenReturn(List.of(scenario));

        mockMvc.perform(get("/api/v1/analysis/risk-control/unmapped-scenarios").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarios", hasSize(1)))
                .andExpect(jsonPath("$.scenarios[0].uid", is("RS-001")));
    }

    @Test
    void unmappedRecords_C5b_returnsRecordList() throws Exception {
        var record = new RiskRegisterRecord(project, "RR-001", "Risk Register Entry 1");
        setField(record, "id", UUID.randomUUID());

        when(coverageService.findUnmappedRecords(PROJECT_ID, true)).thenReturn(List.of(record));

        mockMvc.perform(get("/api/v1/analysis/risk-control/unmapped-records")
                        .param("project", "ground-control")
                        .param("transitive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records", hasSize(1)))
                .andExpect(jsonPath("$.records[0].uid", is("RR-001")));
    }

    @Test
    void unmappedControls_C6_returnsControlList() throws Exception {
        var control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        setField(control, "id", UUID.randomUUID());

        when(coverageService.findUnmappedControls(PROJECT_ID)).thenReturn(List.of(control));

        mockMvc.perform(get("/api/v1/analysis/risk-control/unmapped-controls").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controls", hasSize(1)))
                .andExpect(jsonPath("$.controls[0].uid", is("CTRL-001")));
    }

    @Test
    void assessmentFeed_C7C8_returnsFeedResult() throws Exception {
        var assessmentResultId = UUID.randomUUID();
        var controlId = UUID.randomUUID();
        var assessmentId = UUID.randomUUID();
        var mappingId = UUID.randomUUID();

        var c7Input = new RiskControlMappingFeedService.ControlEffectivenessInput(
                mappingId, controlId, assessmentId, "PARTIALLY_EFFECTIVE", "EFFECTIVE", LocalDate.of(2026, 5, 1));
        var feed = new RiskControlMappingFeedService.AssessmentFeedResult(List.of(c7Input), List.of(), List.of());

        when(feedService.feedForAssessment(PROJECT_ID, assessmentResultId)).thenReturn(feed);

        mockMvc.perform(get("/api/v1/analysis/risk-control/assessment-feed/{id}", assessmentResultId)
                        .param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectivenessInputs", hasSize(1)))
                .andExpect(jsonPath("$.effectivenessInputs[0].operatingEffectiveness", is("PARTIALLY_EFFECTIVE")));
    }
}

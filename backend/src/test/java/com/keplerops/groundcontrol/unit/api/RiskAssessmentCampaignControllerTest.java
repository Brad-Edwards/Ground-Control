package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
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

import com.keplerops.groundcontrol.api.riskscenarios.RiskAssessmentCampaignController;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentCampaign;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskAssessmentCampaignService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.CampaignPhase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(RiskAssessmentCampaignController.class)
class RiskAssessmentCampaignControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RiskAssessmentCampaignService service;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CAMPAIGN_ID = UUID.fromString("00000000-0000-0000-0000-000000000800");
    private static final Instant NOW = Instant.parse("2026-04-04T12:00:00Z");

    private RiskAssessmentCampaign makeCampaign() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var campaign = new RiskAssessmentCampaign(project, "CMP-001", "FY26 Q1 Risk Campaign");
        campaign.setOwner("CISO");
        campaign.setObjective("Q1 enterprise risk review");
        setField(campaign, "id", CAMPAIGN_ID);
        setField(campaign, "createdAt", NOW);
        setField(campaign, "updatedAt", NOW);
        return campaign;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any())).thenReturn(makeCampaign());

        mockMvc.perform(
                        post("/api/v1/risk-assessment-campaigns")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "CMP-001",
                                  "title": "FY26 Q1 Risk Campaign",
                                  "owner": "CISO"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(CAMPAIGN_ID.toString())))
                .andExpect(jsonPath("$.graphNodeId", is("RISK_ASSESSMENT_CAMPAIGN:" + CAMPAIGN_ID)))
                .andExpect(jsonPath("$.uid", is("CMP-001")))
                .andExpect(jsonPath("$.phase", is("PLANNING")));
    }

    @Test
    void getByIdReturnsCampaign() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.getById(PROJECT_ID, CAMPAIGN_ID)).thenReturn(makeCampaign());

        mockMvc.perform(get("/api/v1/risk-assessment-campaigns/{id}", CAMPAIGN_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("CMP-001")));
    }

    @Test
    void advancePhaseInvokesService() throws Exception {
        var advanced = makeCampaign();
        setField(advanced, "phase", CampaignPhase.IDENTIFICATION);
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.advancePhase(eq(PROJECT_ID), eq(CAMPAIGN_ID), eq(CampaignPhase.IDENTIFICATION)))
                .thenReturn(advanced);

        mockMvc.perform(
                        put("/api/v1/risk-assessment-campaigns/{id}/phase", CAMPAIGN_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"phase": "IDENTIFICATION"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase", is("IDENTIFICATION")));
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/risk-assessment-campaigns/{id}", CAMPAIGN_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(service).delete(PROJECT_ID, CAMPAIGN_ID);
    }
}

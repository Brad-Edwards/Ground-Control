package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
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

import com.keplerops.groundcontrol.api.riskappetite.RiskAppetiteProfileController;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskappetite.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskappetite.model.ToleranceThreshold;
import com.keplerops.groundcontrol.domain.riskappetite.service.CreateRiskAppetiteProfileCommand;
import com.keplerops.groundcontrol.domain.riskappetite.service.RiskAppetiteProfileService;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
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

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(RiskAppetiteProfileController.class)
class RiskAppetiteProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RiskAppetiteProfileService riskAppetiteProfileService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000500");
    private static final Instant NOW = Instant.parse("2026-04-04T12:00:00Z");

    private RiskAppetiteProfile makeProfile() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var profile = new RiskAppetiteProfile(
                project, "BOARD_APPETITE", "Board Risk Appetite", "1.0", MethodologyFamily.FAIR, NOW);
        profile.setAppetiteStatement("Tolerate routine operational risk; escalate material loss exposure.");
        profile.setStatus(RiskAppetiteProfileStatus.ACTIVE);
        profile.setToleranceThresholds(List.of(new ToleranceThreshold(
                "data-breach",
                "annualized_loss_expectancy.likely",
                500000.0,
                "USD",
                "USD",
                null,
                null,
                "ALE ceiling")));
        setField(profile, "id", PROFILE_ID);
        setField(profile, "createdAt", NOW);
        setField(profile, "updatedAt", NOW);
        return profile;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskAppetiteProfileService.create(any())).thenReturn(makeProfile());

        mockMvc.perform(
                        post("/api/v1/risk-appetite-profiles")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "appetiteKey": "BOARD_APPETITE",
                                  "name": "Board Risk Appetite",
                                  "version": "1.0",
                                  "methodologyFamily": "FAIR",
                                  "effectiveFrom": "2026-04-04T12:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(PROFILE_ID.toString())))
                .andExpect(jsonPath("$.graphNodeId", is("RISK_APPETITE_PROFILE:" + PROFILE_ID)))
                .andExpect(jsonPath("$.appetiteKey", is("BOARD_APPETITE")))
                .andExpect(jsonPath("$.methodologyFamily", is("FAIR")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.toleranceThresholds[0].metricPath", is("annualized_loss_expectancy.likely")));
    }

    @Test
    void createPlumbsThresholdsIntoCommand() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskAppetiteProfileService.create(any())).thenReturn(makeProfile());

        mockMvc.perform(
                        post("/api/v1/risk-appetite-profiles")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "appetiteKey": "BOARD_APPETITE",
                                  "name": "Board Risk Appetite",
                                  "version": "1.0",
                                  "methodologyFamily": "FAIR",
                                  "effectiveFrom": "2026-04-04T12:00:00Z",
                                  "toleranceThresholds": [
                                    {
                                      "metricPath": "annualized_loss_expectancy.likely",
                                      "maxQuantitativeValue": 500000.0,
                                      "units": "USD",
                                      "currency": "USD"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());

        var captor = ArgumentCaptor.forClass(CreateRiskAppetiteProfileCommand.class);
        verify(riskAppetiteProfileService).create(captor.capture());
        assertThat(captor.getValue().toleranceThresholds()).hasSize(1);
        assertThat(captor.getValue().toleranceThresholds().get(0).metricPath())
                .isEqualTo("annualized_loss_expectancy.likely");
        assertThat(captor.getValue().methodologyFamily()).isEqualTo(MethodologyFamily.FAIR);
    }

    @Test
    void createMissingMethodologyFamilyReturns422() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(
                        post("/api/v1/risk-appetite-profiles")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "appetiteKey": "BOARD_APPETITE",
                                  "name": "Board Risk Appetite",
                                  "version": "1.0",
                                  "effectiveFrom": "2026-04-04T12:00:00Z"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createWithBlankThresholdMetricPathReturns422() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(
                        post("/api/v1/risk-appetite-profiles")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "appetiteKey": "BOARD_APPETITE",
                                  "name": "Board Risk Appetite",
                                  "version": "1.0",
                                  "methodologyFamily": "FAIR",
                                  "effectiveFrom": "2026-04-04T12:00:00Z",
                                  "toleranceThresholds": [
                                    {"metricPath": "", "maxQuantitativeValue": 1.0}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void listReturnsProfiles() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskAppetiteProfileService.listByProject(PROJECT_ID)).thenReturn(List.of(makeProfile()));

        mockMvc.perform(get("/api/v1/risk-appetite-profiles").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].appetiteKey", is("BOARD_APPETITE")));
    }

    @Test
    void getByIdReturnsProfile() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskAppetiteProfileService.getById(PROJECT_ID, PROFILE_ID)).thenReturn(makeProfile());

        mockMvc.perform(get("/api/v1/risk-appetite-profiles/{id}", PROFILE_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(PROFILE_ID.toString())))
                .andExpect(jsonPath("$.version", is("1.0")));
    }

    @Test
    void updateReturnsUpdatedProfile() throws Exception {
        var profile = makeProfile();
        profile.setStatus(RiskAppetiteProfileStatus.RETIRED);
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskAppetiteProfileService.update(eq(PROJECT_ID), eq(PROFILE_ID), any()))
                .thenReturn(profile);

        mockMvc.perform(
                        put("/api/v1/risk-appetite-profiles/{id}", PROFILE_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"status":"RETIRED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RETIRED")));
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/risk-appetite-profiles/{id}", PROFILE_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(riskAppetiteProfileService).delete(PROJECT_ID, PROFILE_ID);
    }
}

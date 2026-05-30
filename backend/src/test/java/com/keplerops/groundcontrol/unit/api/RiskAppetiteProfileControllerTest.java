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

import com.keplerops.groundcontrol.api.riskscenarios.RiskAppetiteProfileController;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteTolerance;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateRiskAppetiteProfileCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskAppetiteProfileService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.AppetiteToleranceKind;
import java.math.BigDecimal;
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

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(RiskAppetiteProfileController.class)
class RiskAppetiteProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RiskAppetiteProfileService service;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000700");
    private static final Instant NOW = Instant.parse("2026-04-04T12:00:00Z");

    private RiskAppetiteProfile makeProfile() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var profile = new RiskAppetiteProfile(project, "APPETITE_BOARD_2026", "Board Appetite 2026", "1");
        profile.setAppetiteStatement("Tolerance posture for FY26.");
        profile.setOwner("Board Risk Committee");
        profile.setActive(true);
        profile.setTolerances(List.of(new RiskAppetiteTolerance(
                "CYBER",
                AppetiteToleranceKind.MONETARY_RANGE,
                null,
                new BigDecimal("100000"),
                new BigDecimal("500000"),
                "USD",
                null,
                null,
                null,
                "Within $100k–$500k per event")));
        setField(profile, "id", PROFILE_ID);
        setField(profile, "createdAt", NOW);
        setField(profile, "updatedAt", NOW);
        return profile;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any())).thenReturn(makeProfile());

        mockMvc.perform(
                        post("/api/v1/risk-appetite-profiles")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "profileKey": "APPETITE_BOARD_2026",
                                  "name": "Board Appetite 2026",
                                  "version": "1",
                                  "appetiteStatement": "Tolerance posture for FY26.",
                                  "active": true,
                                  "tolerances": [{
                                    "category": "CYBER",
                                    "kind": "MONETARY_RANGE",
                                    "monetaryLow": 100000,
                                    "monetaryHigh": 500000,
                                    "currency": "USD"
                                  }]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(PROFILE_ID.toString())))
                .andExpect(jsonPath("$.graphNodeId", is("RISK_APPETITE_PROFILE:" + PROFILE_ID)))
                .andExpect(jsonPath("$.profileKey", is("APPETITE_BOARD_2026")))
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.tolerances[0].category", is("CYBER")))
                .andExpect(jsonPath("$.tolerances[0].kind", is("MONETARY_RANGE")));

        var captor = org.mockito.ArgumentCaptor.forClass(CreateRiskAppetiteProfileCommand.class);
        verify(service).create(captor.capture());
        var captured = captor.getValue();
        // Cycle-2: tighten captor assertions so a controller bug that dropped
        // half the tolerance fields on the way into the command (e.g. forgetting
        // monetaryLow / monetaryHigh / currency) would not still produce a
        // one-element list and pass this test.
        assertThat(captured.profileKey()).isEqualTo("APPETITE_BOARD_2026");
        assertThat(captured.name()).isEqualTo("Board Appetite 2026");
        assertThat(captured.version()).isEqualTo("1");
        assertThat(captured.appetiteStatement()).isEqualTo("Tolerance posture for FY26.");
        assertThat(captured.active()).isTrue();
        assertThat(captured.tolerances()).hasSize(1);
        var tolerance = captured.tolerances().get(0);
        assertThat(tolerance.category()).isEqualTo("CYBER");
        assertThat(tolerance.kind()).isEqualTo(AppetiteToleranceKind.MONETARY_RANGE);
        assertThat(tolerance.monetaryLow()).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(tolerance.monetaryHigh()).isEqualByComparingTo(new BigDecimal("500000"));
        assertThat(tolerance.currency()).isEqualTo("USD");
    }

    @Test
    void listReturnsProfiles() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.listByProject(PROJECT_ID)).thenReturn(List.of(makeProfile()));

        mockMvc.perform(get("/api/v1/risk-appetite-profiles").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].profileKey", is("APPETITE_BOARD_2026")));
    }

    @Test
    void getByIdReturnsProfile() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.getById(PROJECT_ID, PROFILE_ID)).thenReturn(makeProfile());

        mockMvc.perform(get("/api/v1/risk-appetite-profiles/{id}", PROFILE_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(PROFILE_ID.toString())))
                .andExpect(jsonPath("$.version", is("1")));
    }

    @Test
    void updateReturnsUpdatedProfile() throws Exception {
        var profile = makeProfile();
        profile.setActive(false);
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.update(eq(PROJECT_ID), eq(PROFILE_ID), any())).thenReturn(profile);

        mockMvc.perform(
                        put("/api/v1/risk-appetite-profiles/{id}", PROFILE_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"active": false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)));
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/risk-appetite-profiles/{id}", PROFILE_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(service).delete(PROJECT_ID, PROFILE_ID);
    }

    @Test
    void createRequiresProfileKey() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(
                        post("/api/v1/risk-appetite-profiles")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"name": "x", "version": "1"}
                                """))
                // GlobalExceptionHandler maps @Valid violations to 422 (per ADR-026 envelope).
                .andExpect(status().isUnprocessableEntity());
    }
}

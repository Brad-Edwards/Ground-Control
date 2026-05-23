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

import com.keplerops.groundcontrol.api.riskscenarios.MethodologyProfileController;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.service.MethodologyProfileService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyProfileStatus;
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
@WebMvcTest(MethodologyProfileController.class)
class MethodologyProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MethodologyProfileService methodologyProfileService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000300");
    private static final Instant NOW = Instant.parse("2026-04-04T12:00:00Z");

    private MethodologyProfile makeProfile() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var profile = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        profile.setDescription("FAIR v3.0 quantitative model");
        profile.setInputSchema(Map.of("type", "object"));
        profile.setOutputSchema(Map.of("type", "object"));
        profile.setStatus(MethodologyProfileStatus.ACTIVE);
        setField(profile, "id", PROFILE_ID);
        setField(profile, "createdAt", NOW);
        setField(profile, "updatedAt", NOW);
        return profile;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(methodologyProfileService.create(any())).thenReturn(makeProfile());

        mockMvc.perform(
                        post("/api/v1/methodology-profiles")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "profileKey": "FAIR_V3_0",
                                  "name": "FAIR",
                                  "version": "3.0",
                                  "family": "FAIR",
                                  "description": "FAIR v3.0 quantitative model"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(PROFILE_ID.toString())))
                .andExpect(jsonPath("$.graphNodeId", is("METHODOLOGY_PROFILE:" + PROFILE_ID)))
                .andExpect(jsonPath("$.profileKey", is("FAIR_V3_0")))
                .andExpect(jsonPath("$.family", is("FAIR")))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    void listReturnsProfiles() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(methodologyProfileService.listByProject(PROJECT_ID)).thenReturn(List.of(makeProfile()));

        mockMvc.perform(get("/api/v1/methodology-profiles").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(PROFILE_ID.toString())))
                .andExpect(jsonPath("$[0].profileKey", is("FAIR_V3_0")));
    }

    @Test
    void getByIdReturnsProfile() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(methodologyProfileService.getById(PROJECT_ID, PROFILE_ID)).thenReturn(makeProfile());

        mockMvc.perform(get("/api/v1/methodology-profiles/{id}", PROFILE_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(PROFILE_ID.toString())))
                .andExpect(jsonPath("$.version", is("3.0")));
    }

    @Test
    void updateReturnsUpdatedProfile() throws Exception {
        var profile = makeProfile();
        profile.setStatus(MethodologyProfileStatus.DEPRECATED);
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(methodologyProfileService.update(eq(PROJECT_ID), eq(PROFILE_ID), any()))
                .thenReturn(profile);

        mockMvc.perform(
                        put("/api/v1/methodology-profiles/{id}", PROFILE_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"status":"DEPRECATED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DEPRECATED")));
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/methodology-profiles/{id}", PROFILE_ID).param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(methodologyProfileService).delete(PROJECT_ID, PROFILE_ID);
    }

    // -------------------------------------------------------------------------
    // C5: treatmentStrategyVocabulary plumbs through create/update/response
    // -------------------------------------------------------------------------

    @Test
    void createPlumbsTreatmentStrategyVocabularyIntoCommand() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        var profile = makeProfile();
        profile.setTreatmentStrategyVocabulary(
                Map.of("RESIDUAL_TRANSFER", Map.of("description", "transfer residual risk")));
        when(methodologyProfileService.create(any())).thenReturn(profile);

        mockMvc.perform(
                        post("/api/v1/methodology-profiles")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "profileKey": "FAIR_V3_0",
                                  "name": "FAIR",
                                  "version": "3.0",
                                  "family": "FAIR",
                                  "treatmentStrategyVocabulary": {"RESIDUAL_TRANSFER": {"description": "transfer residual risk"}}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.treatmentStrategyVocabulary.RESIDUAL_TRANSFER")
                        .exists());

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.keplerops.groundcontrol.domain.riskscenarios.service.CreateMethodologyProfileCommand.class);
        verify(methodologyProfileService).create(captor.capture());
        assertThat(captor.getValue().treatmentStrategyVocabulary()).containsKey("RESIDUAL_TRANSFER");
    }

    @Test
    void updatePlumbsTreatmentStrategyVocabularyIntoCommand() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var profile = makeProfile();
        profile.setTreatmentStrategyVocabulary(Map.of("NEW_KEY", Map.of()));
        when(methodologyProfileService.update(eq(PROJECT_ID), eq(PROFILE_ID), any()))
                .thenReturn(profile);

        mockMvc.perform(
                        put("/api/v1/methodology-profiles/{id}", PROFILE_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"treatmentStrategyVocabulary": {"NEW_KEY": {}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treatmentStrategyVocabulary.NEW_KEY").exists());

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateMethodologyProfileCommand.class);
        verify(methodologyProfileService).update(any(), any(), captor.capture());
        assertThat(captor.getValue().treatmentStrategyVocabulary()).containsKey("NEW_KEY");
    }

    @Test
    void responseIncludesNullTreatmentStrategyVocabularyWhenAbsent() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(methodologyProfileService.getById(PROJECT_ID, PROFILE_ID)).thenReturn(makeProfile());

        mockMvc.perform(get("/api/v1/methodology-profiles/{id}", PROFILE_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treatmentStrategyVocabulary").doesNotExist());
    }
}

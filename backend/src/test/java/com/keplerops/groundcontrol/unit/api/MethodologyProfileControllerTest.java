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
import com.keplerops.groundcontrol.domain.riskscenarios.model.CrosswalkEntry;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateMethodologyProfileCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.MethodologyProfileService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateMethodologyProfileCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.CrosswalkVocabularySurface;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NormalizedConcept;
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
        var profile = new MethodologyProfile(
                project, "FAIR_V3_0", "Open FAIR", "O-RT 3.0.1 / O-RA 2.0.1", MethodologyFamily.FAIR);
        profile.setDescription("Open FAIR quantitative profile");
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
                                  "name": "Open FAIR",
                                  "version": "O-RT 3.0.1 / O-RA 2.0.1",
                                  "family": "FAIR",
                                  "description": "Open FAIR quantitative profile"
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
                .andExpect(jsonPath("$.version", is("O-RT 3.0.1 / O-RA 2.0.1")));
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
                                  "name": "Open FAIR",
                                  "version": "O-RT 3.0.1 / O-RA 2.0.1",
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

    // -------------------------------------------------------------------------
    // GC-T012 crosswalk entries — controller round-trip
    // -------------------------------------------------------------------------

    @Test
    void createPlumbsCrosswalkEntriesIntoCommand() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        var profile = makeProfile();
        var entry = new CrosswalkEntry(
                NormalizedConcept.THREAT_SOURCE,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "threat_source",
                "Threat Source",
                null,
                "qualitative ordinal",
                "5-level ordinal",
                null,
                "5-level ordinal, no continuous frequency");
        profile.setCrosswalkEntries(List.of(entry));
        when(methodologyProfileService.create(any())).thenReturn(profile);

        mockMvc.perform(
                        post("/api/v1/methodology-profiles")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "profileKey": "NIST_SP800_30_R1",
                                  "name": "NIST",
                                  "version": "1",
                                  "family": "NIST_SP800_30_R1",
                                  "crosswalkEntries": [
                                    {
                                      "normalizedConcept": "THREAT_SOURCE",
                                      "vocabularySurface": "INPUT_SCHEMA",
                                      "sourceFieldPath": "threat_source",
                                      "sourceTermLabel": "Threat Source",
                                      "scale": "qualitative ordinal",
                                      "units": "5-level ordinal",
                                      "limitations": "5-level ordinal, no continuous frequency"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.crosswalkEntries[0].normalizedConcept", is("THREAT_SOURCE")))
                .andExpect(jsonPath("$.crosswalkEntries[0].vocabularySurface", is("INPUT_SCHEMA")))
                .andExpect(jsonPath("$.crosswalkEntries[0].sourceFieldPath", is("threat_source")));

        var captor = org.mockito.ArgumentCaptor.forClass(CreateMethodologyProfileCommand.class);
        verify(methodologyProfileService).create(captor.capture());
        assertThat(captor.getValue().crosswalkEntries()).hasSize(1);
        assertThat(captor.getValue().crosswalkEntries().get(0).normalizedConcept())
                .isEqualTo(NormalizedConcept.THREAT_SOURCE);
        assertThat(captor.getValue().crosswalkEntries().get(0).vocabularySurface())
                .isEqualTo(CrosswalkVocabularySurface.INPUT_SCHEMA);
    }

    @Test
    void updatePlumbsCrosswalkEntriesIntoCommand() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var profile = makeProfile();
        var entry = new CrosswalkEntry(
                NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "loss_event_frequency",
                null,
                null,
                "continuous",
                "annual events",
                "LEF = TEF × Vulnerability",
                null);
        profile.setCrosswalkEntries(List.of(entry));
        when(methodologyProfileService.update(eq(PROJECT_ID), eq(PROFILE_ID), any()))
                .thenReturn(profile);

        mockMvc.perform(
                        put("/api/v1/methodology-profiles/{id}", PROFILE_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "crosswalkEntries": [
                                    {
                                      "normalizedConcept": "LIKELIHOOD_OR_FREQUENCY",
                                      "vocabularySurface": "INPUT_SCHEMA",
                                      "sourceFieldPath": "loss_event_frequency",
                                      "scale": "continuous",
                                      "units": "annual events",
                                      "conversionRule": "LEF = TEF × Vulnerability"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crosswalkEntries[0].normalizedConcept", is("LIKELIHOOD_OR_FREQUENCY")))
                .andExpect(jsonPath("$.crosswalkEntries[0].sourceFieldPath", is("loss_event_frequency")));

        var captor = org.mockito.ArgumentCaptor.forClass(UpdateMethodologyProfileCommand.class);
        verify(methodologyProfileService).update(any(), any(), captor.capture());
        assertThat(captor.getValue().crosswalkEntries()).hasSize(1);
        assertThat(captor.getValue().crosswalkEntries().get(0).conversionRule()).isEqualTo("LEF = TEF × Vulnerability");
    }

    @Test
    void createWithInvalidCrosswalkEntry_returns400() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(
                        post("/api/v1/methodology-profiles")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "profileKey": "NIST_SP800_30_R1",
                                  "name": "NIST",
                                  "version": "1",
                                  "family": "NIST_SP800_30_R1",
                                  "crosswalkEntries": [
                                    {
                                      "normalizedConcept": "THREAT_SOURCE",
                                      "vocabularySurface": "INPUT_SCHEMA",
                                      "sourceFieldPath": ""
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getByIdResponseIncludesCrosswalkEntriesWhenPresent() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var profile = makeProfile();
        profile.setCrosswalkEntries(List.of(new CrosswalkEntry(
                NormalizedConcept.ASSET,
                CrosswalkVocabularySurface.INPUT_SCHEMA,
                "asset_value",
                "Asset Value",
                null,
                "ordinal",
                null,
                null,
                null)));
        when(methodologyProfileService.getById(PROJECT_ID, PROFILE_ID)).thenReturn(profile);

        mockMvc.perform(get("/api/v1/methodology-profiles/{id}", PROFILE_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crosswalkEntries[0].normalizedConcept", is("ASSET")))
                .andExpect(jsonPath("$.crosswalkEntries[0].sourceFieldPath", is("asset_value")));
    }
}

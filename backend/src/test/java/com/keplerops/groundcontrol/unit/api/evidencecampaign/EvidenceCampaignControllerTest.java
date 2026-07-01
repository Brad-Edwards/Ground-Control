package com.keplerops.groundcontrol.unit.api.evidencecampaign;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.evidencecampaign.EvidenceCampaignController;
import com.keplerops.groundcontrol.domain.evidence.campaign.model.EvidenceCampaign;
import com.keplerops.groundcontrol.domain.evidence.campaign.model.EvidenceCampaignRun;
import com.keplerops.groundcontrol.domain.evidence.campaign.service.UpdateEvidenceCampaignCommand;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignFrequency;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignRunStatus;
import com.keplerops.groundcontrol.domain.evidence.campaign.state.EvidenceCampaignStatus;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
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
@WebMvcTest(EvidenceCampaignController.class)
class EvidenceCampaignControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private com.keplerops.groundcontrol.domain.evidence.campaign.service.EvidenceCampaignService service;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CAMPAIGN_ID = UUID.fromString("00000000-0000-0000-0000-000000000900");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    private static final String CREATE_BODY =
            """
            {
              "uid": "CAMP-0001",
              "name": "Quarterly IAM evidence",
              "frequency": "QUARTERLY",
              "adapterName": "iam-collector",
              "scopeType": "iam.users",
              "connectionProfileId": "iam-prod",
              "connectionEndpoint": "https://iam.example.com",
              "credentialRef": "vault://iam/prod"
            }
            """;

    private Project project() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        return project;
    }

    private EvidenceCampaign makeCampaign() {
        var campaign = new EvidenceCampaign(
                project(),
                "CAMP-0001",
                "Quarterly IAM evidence",
                EvidenceCampaignFrequency.QUARTERLY,
                "iam-collector",
                "iam.users",
                "iam-prod",
                "https://iam.example.com",
                "vault://iam/prod",
                NOW);
        campaign.setStatus(EvidenceCampaignStatus.ACTIVE);
        setField(campaign, "id", CAMPAIGN_ID);
        setField(campaign, "createdAt", NOW);
        setField(campaign, "updatedAt", NOW);
        return campaign;
    }

    private EvidenceCampaignRun makeRun() {
        var run = new EvidenceCampaignRun(
                makeCampaign(), project(), EvidenceCampaignRunStatus.COMPLETED, NOW, NOW.plusSeconds(60));
        run.setArtifactCount(2);
        run.setErrorCount(0);
        run.setProducedArtifactIds(List.of(UUID.randomUUID(), UUID.randomUUID()));
        setField(run, "id", RUN_ID);
        setField(run, "createdAt", NOW);
        setField(run, "updatedAt", NOW);
        return run;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any())).thenReturn(makeCampaign());

        mockMvc.perform(post("/api/v1/evidence-campaigns")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(CAMPAIGN_ID.toString())))
                .andExpect(jsonPath("$.uid", is("CAMP-0001")))
                .andExpect(jsonPath("$.frequency", is("QUARTERLY")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.adapterName", is("iam-collector")));
    }

    @Test
    void createReturns422OnMissingRequiredFields() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(
                        post("/api/v1/evidence-campaigns")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "name": "missing uid/frequency/adapter"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createReturns409OnUidConflict() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any())).thenThrow(new ConflictException("duplicate uid"));

        mockMvc.perform(post("/api/v1/evidence-campaigns")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void getByIdReturns404() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.getById(PROJECT_ID, CAMPAIGN_ID)).thenThrow(new NotFoundException("missing"));

        mockMvc.perform(get("/api/v1/evidence-campaigns/{id}", CAMPAIGN_ID).param("project", "ground-control"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listReturns200() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.listByProject(PROJECT_ID)).thenReturn(List.of(makeCampaign()));

        mockMvc.perform(get("/api/v1/evidence-campaigns").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(CAMPAIGN_ID.toString())))
                .andExpect(jsonPath("$[0].uid", is("CAMP-0001")))
                .andExpect(jsonPath("$[0].frequency", is("QUARTERLY")));
    }

    @Test
    void getByIdReturns200() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.getById(PROJECT_ID, CAMPAIGN_ID)).thenReturn(makeCampaign());

        mockMvc.perform(get("/api/v1/evidence-campaigns/{id}", CAMPAIGN_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(CAMPAIGN_ID.toString())))
                .andExpect(jsonPath("$.uid", is("CAMP-0001")))
                .andExpect(jsonPath("$.frequency", is("QUARTERLY")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.adapterName", is("iam-collector")));
    }

    @Test
    void updateReturns200() throws Exception {
        var renamed = makeCampaign();
        renamed.setName("Renamed IAM evidence");
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.update(eq(PROJECT_ID), eq(CAMPAIGN_ID), any(UpdateEvidenceCampaignCommand.class)))
                .thenReturn(renamed);

        mockMvc.perform(put("/api/v1/evidence-campaigns/{id}", CAMPAIGN_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Renamed IAM evidence\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(CAMPAIGN_ID.toString())))
                .andExpect(jsonPath("$.name", is("Renamed IAM evidence")));
    }

    @Test
    void pauseReturns200() throws Exception {
        var paused = makeCampaign();
        paused.setStatus(EvidenceCampaignStatus.PAUSED);
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.pause(PROJECT_ID, CAMPAIGN_ID)).thenReturn(paused);

        mockMvc.perform(post("/api/v1/evidence-campaigns/{id}/pause", CAMPAIGN_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PAUSED")));
    }

    @Test
    void resumeReturns200() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.resume(PROJECT_ID, CAMPAIGN_ID)).thenReturn(makeCampaign());

        mockMvc.perform(post("/api/v1/evidence-campaigns/{id}/resume", CAMPAIGN_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    void triggerReturnsRun() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.trigger(PROJECT_ID, CAMPAIGN_ID)).thenReturn(makeRun());

        mockMvc.perform(post("/api/v1/evidence-campaigns/{id}/trigger", CAMPAIGN_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(RUN_ID.toString())))
                .andExpect(jsonPath("$.campaignId", is(CAMPAIGN_ID.toString())))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.artifactCount", is(2)));
    }

    @Test
    void runsListReturns200() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.listRuns(PROJECT_ID, CAMPAIGN_ID)).thenReturn(List.of(makeRun()));

        mockMvc.perform(get("/api/v1/evidence-campaigns/{id}/runs", CAMPAIGN_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(RUN_ID.toString())))
                .andExpect(jsonPath("$[0].status", is("COMPLETED")));
    }
}

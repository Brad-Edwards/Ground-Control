package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.evidence.EvidenceArtifactController;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.evidence.service.EvidenceArtifactService;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
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
@WebMvcTest(EvidenceArtifactController.class)
class EvidenceArtifactControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EvidenceArtifactService service;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ARTIFACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000777");
    private static final UUID OBSERVATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    private EvidenceArtifact makeArtifact() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var artifact = new EvidenceArtifact(
                project,
                "EVD-0001",
                "Q2 assurance summary",
                "Control X operated effectively across Q2.",
                EvidenceType.ASSURANCE_CONCLUSION,
                "manual-rollup-v1",
                NOW);
        artifact.setSources(List.of(new EvidenceSourceRef(EvidenceSourceKind.OBSERVATION, OBSERVATION_ID, null, null)));
        setField(artifact, "id", ARTIFACT_ID);
        setField(artifact, "createdAt", NOW);
        setField(artifact, "updatedAt", NOW);
        return artifact;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any())).thenReturn(makeArtifact());

        mockMvc.perform(
                        post("/api/v1/evidence-artifacts")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "EVD-0001",
                                  "title": "Q2 assurance summary",
                                  "summary": "Control X operated effectively across Q2.",
                                  "evidenceType": "ASSURANCE_CONCLUSION",
                                  "derivationMethod": "manual-rollup-v1",
                                  "derivedAt": "2026-05-01T12:00:00Z",
                                  "sources": [
                                    {
                                      "sourceKind": "OBSERVATION",
                                      "sourceEntityId": "00000000-0000-0000-0000-000000000200"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(ARTIFACT_ID.toString())))
                .andExpect(jsonPath("$.uid", is("EVD-0001")))
                .andExpect(jsonPath("$.evidenceType", is("ASSURANCE_CONCLUSION")))
                .andExpect(jsonPath("$.sources", hasSize(1)))
                .andExpect(jsonPath("$.sources[0].sourceKind", is("OBSERVATION")));
    }

    @Test
    void createReturns400OnMissingSources() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(
                        post("/api/v1/evidence-artifacts")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "EVD-0001",
                                  "title": "Q2",
                                  "summary": "x",
                                  "evidenceType": "ATTESTATION",
                                  "derivationMethod": "m",
                                  "derivedAt": "2026-05-01T12:00:00Z"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void listFiltersByEvidenceType() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.listByProject(PROJECT_ID, EvidenceType.ATTESTATION, false)).thenReturn(List.of(makeArtifact()));

        mockMvc.perform(get("/api/v1/evidence-artifacts")
                        .param("project", "ground-control")
                        .param("evidenceType", "ATTESTATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].uid", is("EVD-0001")))
                .andExpect(jsonPath("$[0].evidenceType", is("ASSURANCE_CONCLUSION")));
    }

    @Test
    void listIncludeSupersededTrue() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.listByProject(PROJECT_ID, null, true)).thenReturn(List.of(makeArtifact()));

        mockMvc.perform(get("/api/v1/evidence-artifacts")
                        .param("project", "ground-control")
                        .param("includeSuperseded", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void getByIdReturns200() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.getById(PROJECT_ID, ARTIFACT_ID)).thenReturn(makeArtifact());

        mockMvc.perform(get("/api/v1/evidence-artifacts/{id}", ARTIFACT_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("EVD-0001")));
    }

    @Test
    void getByIdReturns404() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.getById(PROJECT_ID, ARTIFACT_ID)).thenThrow(new NotFoundException("missing"));

        mockMvc.perform(get("/api/v1/evidence-artifacts/{id}", ARTIFACT_ID).param("project", "ground-control"))
                .andExpect(status().isNotFound());
    }

    @Test
    void supersedeReturns201() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.supersede(eq(PROJECT_ID), eq(ARTIFACT_ID), any())).thenReturn(makeArtifact());

        mockMvc.perform(
                        post("/api/v1/evidence-artifacts/{id}/supersede", ARTIFACT_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "EVD-0002",
                                  "title": "revised",
                                  "summary": "y",
                                  "evidenceType": "ASSURANCE_CONCLUSION",
                                  "derivationMethod": "manual-rollup-v2",
                                  "derivedAt": "2026-05-15T17:00:00Z",
                                  "sources": [
                                    {
                                      "sourceKind": "OBSERVATION",
                                      "sourceEntityId": "00000000-0000-0000-0000-000000000200"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                // Assert serialized response body so a regression in
                // EvidenceArtifactResponse.from() (or the controller's
                // toCreateCommand mapping) is caught here, not at the next
                // boundary. Mirrors createReturns201's body assertions.
                .andExpect(jsonPath("$.uid", is("EVD-0001")))
                .andExpect(jsonPath("$.evidenceType", is("ASSURANCE_CONCLUSION")))
                .andExpect(jsonPath("$.sources", hasSize(1)))
                .andExpect(jsonPath("$.sources[0].sourceKind", is("OBSERVATION")));
    }

    @Test
    void supersedeReturns409WhenAlreadySuperseded() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.supersede(eq(PROJECT_ID), eq(ARTIFACT_ID), any()))
                .thenThrow(new ConflictException("already superseded"));

        mockMvc.perform(
                        post("/api/v1/evidence-artifacts/{id}/supersede", ARTIFACT_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "EVD-0002",
                                  "title": "revised",
                                  "summary": "y",
                                  "evidenceType": "ASSURANCE_CONCLUSION",
                                  "derivationMethod": "manual-rollup-v2",
                                  "derivedAt": "2026-05-15T17:00:00Z",
                                  "sources": [
                                    {
                                      "sourceKind": "OBSERVATION",
                                      "sourceEntityId": "00000000-0000-0000-0000-000000000200"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isConflict());
    }

    /**
     * Append-only enforcement at the controller surface: no PUT or DELETE route
     * may be registered on /api/v1/evidence-artifacts/{id} for any id. This is
     * the API-boundary half of clause C2 of GC-M016; the service-layer state
     * check on supersede is the second half.
     */
    @Test
    void appendOnlyContractHasNoPutRoute() throws Exception {
        mockMvc.perform(put("/api/v1/evidence-artifacts/{id}", ARTIFACT_ID).param("project", "ground-control"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void appendOnlyContractHasNoDeleteRoute() throws Exception {
        mockMvc.perform(delete("/api/v1/evidence-artifacts/{id}", ARTIFACT_ID).param("project", "ground-control"))
                .andExpect(status().isMethodNotAllowed());
    }

    /**
     * GC-I004: expiredOnly=true filters out non-expired artifacts. The
     * filter is computed server-side from {@code expiresAt} and the request
     * time; the controller routes the filter through
     * {@code listByProjectFilteredByExpiry} so the ArchUnit api -> model
     * boundary stays clean.
     */
    @Test
    void listFiltersByExpiredOnly() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var expired = new EvidenceArtifact(project, "EVD-EXP", "t", "s", EvidenceType.ATTESTATION, "m", NOW);
        setField(expired, "id", UUID.randomUUID());
        expired.setSources(List.of(new EvidenceSourceRef(EvidenceSourceKind.ATTESTATION, null, "id", null)));
        expired.setExpiresAt(NOW.minusSeconds(60));
        when(service.listByProjectFilteredByExpiry(eq(PROJECT_ID), eq(null), eq(false), any(), eq(true)))
                .thenReturn(List.of(expired));

        mockMvc.perform(get("/api/v1/evidence-artifacts")
                        .param("project", "ground-control")
                        .param("expiredOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].uid", is("EVD-EXP")))
                .andExpect(jsonPath("$[0].expired", is(true)));
    }

    /**
     * GC-I004: expired=true is the canonical form; expiredOnly is an alias.
     * Both must route to {@code listByProjectFilteredByExpiry}.
     */
    @Test
    void listFiltersByExpiredParamTrue() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var expired = new EvidenceArtifact(project, "EVD-EXP2", "t", "s", EvidenceType.ATTESTATION, "m", NOW);
        setField(expired, "id", UUID.randomUUID());
        expired.setSources(List.of(new EvidenceSourceRef(EvidenceSourceKind.ATTESTATION, null, "id", null)));
        expired.setExpiresAt(NOW.minusSeconds(60));
        when(service.listByProjectFilteredByExpiry(eq(PROJECT_ID), eq(null), eq(false), any(), eq(true)))
                .thenReturn(List.of(expired));

        mockMvc.perform(get("/api/v1/evidence-artifacts")
                        .param("project", "ground-control")
                        .param("expired", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].uid", is("EVD-EXP2")));
    }

    /**
     * GC-I004: expired=false filters to non-expired artifacts.
     */
    @Test
    void listFiltersByExpiredParamFalse() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var active = new EvidenceArtifact(project, "EVD-ACTIVE", "t", "s", EvidenceType.ATTESTATION, "m", NOW);
        setField(active, "id", UUID.randomUUID());
        active.setSources(List.of(new EvidenceSourceRef(EvidenceSourceKind.ATTESTATION, null, "id", null)));
        active.setExpiresAt(NOW.plusSeconds(86400));
        when(service.listByProjectFilteredByExpiry(eq(PROJECT_ID), eq(null), eq(false), any(), eq(false)))
                .thenReturn(List.of(active));

        mockMvc.perform(get("/api/v1/evidence-artifacts")
                        .param("project", "ground-control")
                        .param("expired", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].uid", is("EVD-ACTIVE")));
    }

    @Test
    void createAcceptsExpiresAtAndValidityWindowDays() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var artifact = new EvidenceArtifact(project, "EVD-EXP", "t", "s", EvidenceType.ATTESTATION, "m", NOW);
        setField(artifact, "id", ARTIFACT_ID);
        artifact.setSources(List.of(new EvidenceSourceRef(EvidenceSourceKind.ATTESTATION, null, "id", null)));
        artifact.setExpiresAt(NOW.plusSeconds(86400));
        artifact.setValidityWindowDays(1);
        when(service.create(any())).thenReturn(artifact);

        mockMvc.perform(
                        post("/api/v1/evidence-artifacts")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "EVD-EXP",
                                  "title": "t",
                                  "summary": "s",
                                  "evidenceType": "ATTESTATION",
                                  "derivationMethod": "m",
                                  "derivedAt": "2026-05-01T12:00:00Z",
                                  "expiresAt": "2026-05-02T12:00:00Z",
                                  "validityWindowDays": 1,
                                  "sources": [
                                    {
                                      "sourceKind": "ATTESTATION",
                                      "sourceIdentifier": "id"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt", is("2026-05-02T12:00:00Z")))
                .andExpect(jsonPath("$.validityWindowDays", is(1)));
    }

    @Test
    void createRejectsValidityWindowDaysZero() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        mockMvc.perform(
                        post("/api/v1/evidence-artifacts")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "EVD-EXP",
                                  "title": "t",
                                  "summary": "s",
                                  "evidenceType": "ATTESTATION",
                                  "derivationMethod": "m",
                                  "derivedAt": "2026-05-01T12:00:00Z",
                                  "validityWindowDays": 0,
                                  "sources": [
                                    {
                                      "sourceKind": "ATTESTATION",
                                      "sourceIdentifier": "id"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }
}

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

import com.keplerops.groundcontrol.api.threatmodels.ThreatModelController;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.service.CreateThreatModelCommand;
import com.keplerops.groundcontrol.domain.threatmodels.service.ThreatModelService;
import com.keplerops.groundcontrol.domain.threatmodels.service.UpdateThreatModelCommand;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelStatus;
import com.keplerops.groundcontrol.domain.trace.SecurityTrace;
import com.keplerops.groundcontrol.domain.trace.SecurityTraceSourceType;
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
@WebMvcTest(ThreatModelController.class)
class ThreatModelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ThreatModelService threatModelService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TM_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final Instant NOW = Instant.parse("2026-04-11T12:00:00Z");

    private ThreatModel makeThreatModel() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var tm = new ThreatModel(
                project,
                "TM-001",
                "Credential stuffing on login portal",
                "External threat actor using leaked credentials",
                "Automated credential replay against /login",
                "Account takeover and customer data exposure");
        tm.setStride(StrideCategory.SPOOFING);
        tm.setNarrative("Observed 3x surge after credential dump release.");
        tm.setCreatedBy("analyst");
        setField(tm, "id", TM_ID);
        setField(tm, "createdAt", NOW);
        setField(tm, "updatedAt", NOW);
        return tm;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.create(any())).thenReturn(makeThreatModel());

        mockMvc.perform(
                        post("/api/v1/threat-models")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                    "uid": "TM-001",
                                    "title": "Credential stuffing on login portal",
                                    "threatSource": "External threat actor using leaked credentials",
                                    "threatEvent": "Automated credential replay against /login",
                                    "effect": "Account takeover and customer data exposure",
                                    "stride": "SPOOFING",
                                    "narrative": "Observed surge after breach dump."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(TM_ID.toString())))
                .andExpect(jsonPath("$.graphNodeId", is("THREAT_MODEL:" + TM_ID)))
                .andExpect(jsonPath("$.uid", is("TM-001")))
                .andExpect(jsonPath("$.threatSource", is("External threat actor using leaked credentials")))
                .andExpect(jsonPath("$.threatEvent", is("Automated credential replay against /login")))
                .andExpect(jsonPath("$.effect", is("Account takeover and customer data exposure")))
                .andExpect(jsonPath("$.stride", is("SPOOFING")))
                .andExpect(jsonPath("$.status", is("DRAFT")));

        // Lock in the request→command mapping: without this capture, the test
        // would still pass if the controller silently dropped or swapped fields
        // because the mocked service returns a canned fixture regardless of input.
        var captor = ArgumentCaptor.forClass(CreateThreatModelCommand.class);
        verify(threatModelService).create(captor.capture());
        var command = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(PROJECT_ID, command.projectId());
        org.junit.jupiter.api.Assertions.assertEquals("TM-001", command.uid());
        org.junit.jupiter.api.Assertions.assertEquals("Credential stuffing on login portal", command.title());
        org.junit.jupiter.api.Assertions.assertEquals(
                "External threat actor using leaked credentials", command.threatSource());
        org.junit.jupiter.api.Assertions.assertEquals(
                "Automated credential replay against /login", command.threatEvent());
        org.junit.jupiter.api.Assertions.assertEquals("Account takeover and customer data exposure", command.effect());
        org.junit.jupiter.api.Assertions.assertEquals(StrideCategory.SPOOFING, command.stride());
        org.junit.jupiter.api.Assertions.assertEquals("Observed surge after breach dump.", command.narrative());
    }

    @Test
    void createReturns422WhenRequiredFieldMissing() throws Exception {
        mockMvc.perform(
                        post("/api/v1/threat-models")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                    "title": "Missing uid",
                                    "threatSource": "Source",
                                    "threatEvent": "Event",
                                    "effect": "Effect"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createReturns409OnConflict() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.create(any())).thenThrow(new ConflictException("Duplicate UID"));

        mockMvc.perform(
                        post("/api/v1/threat-models")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                    "uid": "TM-001",
                                    "title": "Dup",
                                    "threatSource": "s",
                                    "threatEvent": "e",
                                    "effect": "x"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void listReturnsThreatModels() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.listByProject(PROJECT_ID)).thenReturn(List.of(makeThreatModel()));

        mockMvc.perform(get("/api/v1/threat-models").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].uid", is("TM-001")));
    }

    @Test
    void getByIdReturnsThreatModel() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.getById(PROJECT_ID, TM_ID)).thenReturn(makeThreatModel());

        mockMvc.perform(get("/api/v1/threat-models/{id}", TM_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(TM_ID.toString())))
                .andExpect(jsonPath("$.projectIdentifier", is("ground-control")));
    }

    @Test
    void getByIdReturns404WhenMissing() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.getById(PROJECT_ID, TM_ID)).thenThrow(new NotFoundException("Not found"));

        mockMvc.perform(get("/api/v1/threat-models/{id}", TM_ID).param("project", "ground-control"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByUidReturnsThreatModel() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.getByUid("TM-001", PROJECT_ID)).thenReturn(makeThreatModel());

        mockMvc.perform(get("/api/v1/threat-models/uid/TM-001").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("TM-001")));
    }

    @Test
    void updateReturnsUpdatedThreatModel() throws Exception {
        var updated = makeThreatModel();
        updated.setTitle("Updated title");
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.update(eq(PROJECT_ID), eq(TM_ID), any())).thenReturn(updated);

        mockMvc.perform(
                        put("/api/v1/threat-models/{id}", TM_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"title": "Updated title"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Updated title")));
    }

    @Test
    void updateForwardsClearStrideFlagToCommand() throws Exception {
        var updated = makeThreatModel();
        updated.setStride(null);
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.update(eq(PROJECT_ID), eq(TM_ID), any())).thenReturn(updated);

        mockMvc.perform(
                        put("/api/v1/threat-models/{id}", TM_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"clearStride": true}
                                """))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(UpdateThreatModelCommand.class);
        verify(threatModelService).update(eq(PROJECT_ID), eq(TM_ID), captor.capture());
        var command = captor.getValue();
        org.junit.jupiter.api.Assertions.assertTrue(command.clearStride());
        org.junit.jupiter.api.Assertions.assertFalse(command.clearNarrative());
    }

    @Test
    void updateForwardsClearNarrativeFlagToCommand() throws Exception {
        var updated = makeThreatModel();
        updated.setNarrative(null);
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.update(eq(PROJECT_ID), eq(TM_ID), any())).thenReturn(updated);

        mockMvc.perform(
                        put("/api/v1/threat-models/{id}", TM_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"clearNarrative": true}
                                """))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(UpdateThreatModelCommand.class);
        verify(threatModelService).update(eq(PROJECT_ID), eq(TM_ID), captor.capture());
        var command = captor.getValue();
        org.junit.jupiter.api.Assertions.assertTrue(command.clearNarrative());
        org.junit.jupiter.api.Assertions.assertFalse(command.clearStride());
    }

    @Test
    void updateDefaultsClearFlagsToFalseWhenNotProvided() throws Exception {
        var updated = makeThreatModel();
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.update(eq(PROJECT_ID), eq(TM_ID), any())).thenReturn(updated);

        mockMvc.perform(
                        put("/api/v1/threat-models/{id}", TM_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"title": "still here"}
                                """))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(UpdateThreatModelCommand.class);
        verify(threatModelService).update(eq(PROJECT_ID), eq(TM_ID), captor.capture());
        var command = captor.getValue();
        org.junit.jupiter.api.Assertions.assertFalse(command.clearStride());
        org.junit.jupiter.api.Assertions.assertFalse(command.clearNarrative());
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/threat-models/{id}", TM_ID).param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(threatModelService).delete(PROJECT_ID, TM_ID);
    }

    @Test
    void getLinkedRequirementsReturnsRequirementList() throws Exception {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var req = new Requirement(
                project, "GC-H002", "Threat linking", "System shall link threat models to requirements");
        setField(req, "id", UUID.fromString("00000000-0000-0000-0000-000000000300"));
        setField(req, "createdAt", NOW);
        setField(req, "updatedAt", NOW);

        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.findLinkedRequirements(PROJECT_ID, TM_ID)).thenReturn(List.of(req));

        mockMvc.perform(get("/api/v1/threat-models/{id}/requirements", TM_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].uid", is("GC-H002")));
    }

    @Test
    void getLinkedRequirementsReturns404WhenThreatModelUnknown() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.findLinkedRequirements(PROJECT_ID, TM_ID))
                .thenThrow(new NotFoundException("Threat model not found: " + TM_ID));

        mockMvc.perform(get("/api/v1/threat-models/{id}/requirements", TM_ID).param("project", "ground-control"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTraceReturnsSecurityTrace() throws Exception {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);

        var asset = new OperationalAsset(project, "ASSET-001", "Auth Service");
        setField(asset, "id", UUID.fromString("00000000-0000-0000-0000-000000000010"));
        setField(asset, "createdAt", NOW);
        setField(asset, "updatedAt", NOW);

        var control = new Control(project, "CTL-001", "MFA Control", ControlFunction.PREVENTIVE);
        setField(control, "id", UUID.fromString("00000000-0000-0000-0000-000000000020"));
        setField(control, "createdAt", NOW);
        setField(control, "updatedAt", NOW);

        var req = new Requirement(project, "GC-H003", "Threat tracing", "System shall trace threats");
        setField(req, "id", UUID.fromString("00000000-0000-0000-0000-000000000300"));
        setField(req, "createdAt", NOW);
        setField(req, "updatedAt", NOW);

        var artifact = new TraceabilityLink(req, ArtifactType.PULL_REQUEST, "42", LinkType.IMPLEMENTS);
        setField(artifact, "id", UUID.fromString("00000000-0000-0000-0000-000000000400"));
        setField(artifact, "createdAt", NOW);
        setField(artifact, "updatedAt", NOW);

        var reqTrace = new SecurityTrace.RequirementTrace(req, List.of(artifact));
        var trace = new SecurityTrace(
                SecurityTraceSourceType.THREAT_MODEL,
                TM_ID,
                "TM-001",
                "Credential stuffing",
                List.of(asset),
                List.of(control),
                List.of(reqTrace));

        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.findTrace(PROJECT_ID, TM_ID)).thenReturn(trace);

        mockMvc.perform(get("/api/v1/threat-models/{id}/trace", TM_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceType", is("THREAT_MODEL")))
                .andExpect(jsonPath("$.sourceUid", is("TM-001")))
                .andExpect(jsonPath("$.assets", hasSize(1)))
                .andExpect(jsonPath("$.assets[0].uid", is("ASSET-001")))
                .andExpect(jsonPath("$.controls", hasSize(1)))
                .andExpect(jsonPath("$.controls[0].uid", is("CTL-001")))
                .andExpect(jsonPath("$.requirements", hasSize(1)))
                .andExpect(jsonPath("$.requirements[0].requirement.uid", is("GC-H003")))
                .andExpect(jsonPath("$.requirements[0].artifacts", hasSize(1)))
                .andExpect(jsonPath("$.requirements[0].artifacts[0].artifactIdentifier", is("42")));
    }

    @Test
    void getTraceReturns404WhenThreatModelUnknown() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.findTrace(PROJECT_ID, TM_ID))
                .thenThrow(new NotFoundException("Threat model not found: " + TM_ID));

        mockMvc.perform(get("/api/v1/threat-models/{id}/trace", TM_ID).param("project", "ground-control"))
                .andExpect(status().isNotFound());
    }

    @Test
    void transitionStatusReturnsUpdatedThreatModel() throws Exception {
        var tm = makeThreatModel();
        setField(tm, "status", ThreatModelStatus.ACTIVE);
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(threatModelService.transitionStatus(PROJECT_ID, TM_ID, ThreatModelStatus.ACTIVE))
                .thenReturn(tm);

        mockMvc.perform(
                        put("/api/v1/threat-models/{id}/status", TM_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"status": "ACTIVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }
}

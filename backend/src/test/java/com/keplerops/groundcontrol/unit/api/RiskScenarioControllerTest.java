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

import com.keplerops.groundcontrol.api.riskscenarios.RiskScenarioController;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateRiskScenarioCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskScenarioService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
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
@WebMvcTest(RiskScenarioController.class)
class RiskScenarioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RiskScenarioService riskScenarioService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RS_ID = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");

    private RiskScenario makeScenario() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var rs = new RiskScenario(
                project,
                "RS-001",
                "Credential stuffing on customer portal",
                "External threat actor",
                "Credential stuffing attack",
                "Customer authentication portal",
                "Data breach and unauthorized access");
        rs.setTimeHorizon("12 months");
        rs.setCreatedBy("system");
        setField(rs, "id", RS_ID);
        setField(rs, "createdAt", NOW);
        setField(rs, "updatedAt", NOW);
        return rs;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(riskScenarioService.create(any())).thenReturn(makeScenario());

        mockMvc.perform(
                        post("/api/v1/risk-scenarios")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                            "uid": "RS-001",
                            "title": "Credential stuffing on customer portal",
                            "threat": "External threat actor",
                            "method": "Credential stuffing attack",
                            "asset": "Customer authentication portal",
                            "effect": "Data breach and unauthorized access",
                            "timeHorizon": "12 months"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(RS_ID.toString())))
                .andExpect(jsonPath("$.graphNodeId", is("RISK_SCENARIO:" + RS_ID)))
                .andExpect(jsonPath("$.uid", is("RS-001")))
                .andExpect(jsonPath("$.threat", is("External threat actor")))
                .andExpect(jsonPath("$.status", is("DRAFT")));

        // Lock in the request→command mapping: without this capture, the test
        // would still pass if the controller silently dropped or swapped fields
        // because the mocked service returns a canned fixture regardless of input.
        var captor = ArgumentCaptor.forClass(CreateRiskScenarioCommand.class);
        verify(riskScenarioService).create(captor.capture());
        var command = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(PROJECT_ID, command.projectId());
        org.junit.jupiter.api.Assertions.assertEquals("RS-001", command.uid());
        org.junit.jupiter.api.Assertions.assertEquals("Credential stuffing on customer portal", command.title());
        org.junit.jupiter.api.Assertions.assertEquals("External threat actor", command.threat());
        org.junit.jupiter.api.Assertions.assertEquals("Credential stuffing attack", command.method());
        org.junit.jupiter.api.Assertions.assertEquals("Customer authentication portal", command.asset());
        org.junit.jupiter.api.Assertions.assertEquals("Data breach and unauthorized access", command.effect());
        org.junit.jupiter.api.Assertions.assertEquals("12 months", command.timeHorizon());
    }

    @Test
    void createReturns422WhenUidMissing() throws Exception {
        mockMvc.perform(
                        post("/api/v1/risk-scenarios")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                            "title": "Title",
                            "threat": "Source",
                            "method": "Event",
                            "asset": "Object",
                            "effect": "Consequence",
                            "timeHorizon": "12 months"
                        }
                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    // TDD: fairSentence projection — GREEN after RiskScenarioResponse.from() computes it.
    @Test
    void createResponseIncludesFairSentence() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(riskScenarioService.create(any())).thenReturn(makeScenario());

        mockMvc.perform(
                        post("/api/v1/risk-scenarios")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                            "uid": "RS-001",
                            "title": "Credential stuffing on customer portal",
                            "threat": "External threat actor",
                            "method": "Credential stuffing attack",
                            "asset": "Customer authentication portal",
                            "effect": "Data breach and unauthorized access",
                            "timeHorizon": "12 months"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath(
                                "$.fairSentence",
                                is(
                                        "External threat actor impacts Customer authentication portal via Credential stuffing attack, causing Data breach and unauthorized access")));
    }

    // TDD: @Size(min=10) on threat — 9-char value must return 422.
    @Test
    void createReturns422WhenThreatTooShort() throws Exception {
        mockMvc.perform(
                        post("/api/v1/risk-scenarios")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                            "uid": "RS-001",
                            "title": "Credential stuffing on customer portal",
                            "threat": "123456789",
                            "method": "Credential stuffing attack",
                            "asset": "Customer authentication portal",
                            "effect": "Data breach and unauthorized access",
                            "timeHorizon": "12 months"
                        }
                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    // TDD: @Size(min=10) on method — 9-char value must return 422.
    @Test
    void createReturns422WhenMethodTooShort() throws Exception {
        mockMvc.perform(
                        post("/api/v1/risk-scenarios")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                            "uid": "RS-001",
                            "title": "Credential stuffing on customer portal",
                            "threat": "External threat actor",
                            "method": "123456789",
                            "asset": "Customer authentication portal",
                            "effect": "Data breach and unauthorized access",
                            "timeHorizon": "12 months"
                        }
                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    // TDD: @Size(min=10) on asset — 9-char value must return 422.
    @Test
    void createReturns422WhenAssetTooShort() throws Exception {
        mockMvc.perform(
                        post("/api/v1/risk-scenarios")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                            "uid": "RS-001",
                            "title": "Credential stuffing on customer portal",
                            "threat": "External threat actor",
                            "method": "Credential stuffing attack",
                            "asset": "123456789",
                            "effect": "Data breach and unauthorized access",
                            "timeHorizon": "12 months"
                        }
                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    // TDD: @Size(min=10) on effect — 9-char value must return 422.
    @Test
    void createReturns422WhenEffectTooShort() throws Exception {
        mockMvc.perform(
                        post("/api/v1/risk-scenarios")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                            "uid": "RS-001",
                            "title": "Credential stuffing on customer portal",
                            "threat": "External threat actor",
                            "method": "Credential stuffing attack",
                            "asset": "Customer authentication portal",
                            "effect": "123456789",
                            "timeHorizon": "12 months"
                        }
                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    // TDD: vulnerability is no longer on RiskScenarioRequest — Spring Boot silently
    // ignores unknown JSON properties by default, so a request body including
    // "vulnerability" must still be accepted (200/201) with the known fields processed.
    @Test
    void createIgnoresVulnerabilityField() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(riskScenarioService.create(any())).thenReturn(makeScenario());

        mockMvc.perform(
                        post("/api/v1/risk-scenarios")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                            "uid": "RS-001",
                            "title": "Credential stuffing on customer portal",
                            "threat": "External threat actor",
                            "method": "Credential stuffing attack",
                            "asset": "Customer authentication portal",
                            "effect": "Data breach and unauthorized access",
                            "timeHorizon": "12 months",
                            "vulnerability": "old field should be ignored"
                        }
                        """))
                .andExpect(status().isCreated());
    }

    @Test
    void listReturnsScenarios() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(riskScenarioService.listByProject(PROJECT_ID)).thenReturn(List.of(makeScenario()));

        mockMvc.perform(get("/api/v1/risk-scenarios").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].uid", is("RS-001")));
    }

    @Test
    void getByIdReturnsScenario() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskScenarioService.getById(PROJECT_ID, RS_ID)).thenReturn(makeScenario());

        mockMvc.perform(get("/api/v1/risk-scenarios/{id}", RS_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(RS_ID.toString())))
                .andExpect(jsonPath("$.projectIdentifier", is("ground-control")));
    }

    @Test
    void getByUidReturnsScenario() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(riskScenarioService.getByUid("RS-001", PROJECT_ID)).thenReturn(makeScenario());

        mockMvc.perform(get("/api/v1/risk-scenarios/uid/RS-001").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("RS-001")));
    }

    @Test
    void updateReturnsUpdatedScenario() throws Exception {
        var updated = makeScenario();
        updated.setTitle("Updated title");
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskScenarioService.update(eq(PROJECT_ID), eq(RS_ID), any())).thenReturn(updated);

        mockMvc.perform(
                        put("/api/v1/risk-scenarios/{id}", RS_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                            "title": "Updated title"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Updated title")));
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/risk-scenarios/{id}", RS_ID).param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(riskScenarioService).delete(PROJECT_ID, RS_ID);
    }

    @Test
    void transitionStatusReturnsUpdatedScenario() throws Exception {
        var rs = makeScenario();
        setField(rs, "status", RiskScenarioStatus.ACTIVE);
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(riskScenarioService.transitionStatus(PROJECT_ID, RS_ID, RiskScenarioStatus.ACTIVE))
                .thenReturn(rs);

        mockMvc.perform(put("/api/v1/risk-scenarios/{id}/status", RS_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"status": "ACTIVE"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @org.junit.jupiter.api.Test
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
                SecurityTraceSourceType.RISK_SCENARIO,
                RS_ID,
                "RS-001",
                "Credential stuffing on customer portal",
                List.of(asset),
                List.of(control),
                List.of(reqTrace));

        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(riskScenarioService.findTrace(PROJECT_ID, RS_ID)).thenReturn(trace);

        mockMvc.perform(get("/api/v1/risk-scenarios/{id}/trace", RS_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceType", is("RISK_SCENARIO")))
                .andExpect(jsonPath("$.sourceUid", is("RS-001")))
                .andExpect(jsonPath("$.assets", hasSize(1)))
                .andExpect(jsonPath("$.assets[0].uid", is("ASSET-001")))
                .andExpect(jsonPath("$.controls", hasSize(1)))
                .andExpect(jsonPath("$.controls[0].uid", is("CTL-001")))
                .andExpect(jsonPath("$.requirements", hasSize(1)))
                .andExpect(jsonPath("$.requirements[0].requirement.uid", is("GC-H003")))
                .andExpect(jsonPath("$.requirements[0].artifacts", hasSize(1)))
                .andExpect(jsonPath("$.requirements[0].artifacts[0].artifactIdentifier", is("42")));
    }

    @org.junit.jupiter.api.Test
    void getTraceReturns404WhenRiskScenarioUnknown() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(riskScenarioService.findTrace(PROJECT_ID, RS_ID))
                .thenThrow(new NotFoundException("Risk scenario not found: " + RS_ID));

        mockMvc.perform(get("/api/v1/risk-scenarios/{id}/trace", RS_ID).param("project", "ground-control"))
                .andExpect(status().isNotFound());
    }
}

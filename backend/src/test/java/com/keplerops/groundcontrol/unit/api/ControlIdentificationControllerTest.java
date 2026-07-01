package com.keplerops.groundcontrol.unit.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.controlidentification.ControlIdentificationController;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlIdentificationResult;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlIdentificationService;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlMappingConfirmation;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlMappingConfirmationService;
import com.keplerops.groundcontrol.domain.controlidentification.service.CoveredControl;
import com.keplerops.groundcontrol.domain.controlidentification.service.ThreatControlCoverage;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ControlIdentificationController.class)
class ControlIdentificationControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111100");
    private static final UUID THREAT_ID = UUID.fromString("22222222-2222-2222-2222-222222222200");
    private static final UUID CONTROL_ID = UUID.fromString("33333333-3333-3333-3333-333333333300");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ControlIdentificationService identificationService;

    @MockitoBean
    private ControlMappingConfirmationService confirmationService;

    @MockitoBean
    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(projectService.resolveProjectIdentifier("ground-control")).thenReturn("ground-control");
    }

    private ControlIdentificationResult emptyResult() {
        return new ControlIdentificationResult(
                ControlIdentificationService.SCHEMA_VERSION,
                "gc-default-control-mapping",
                "1.0.0",
                List.of(),
                List.of());
    }

    @Test
    void identifyReturnsCandidatesForLatestSnapshot() throws Exception {
        when(identificationService.identifyForLatestSnapshot(eq(PROJECT_ID), eq("stride-baseline"), isNull()))
                .thenReturn(emptyResult());

        mockMvc.perform(get("/api/v1/control-identification")
                        .param("project", "ground-control")
                        .param("threatPackId", "stride-baseline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(ControlIdentificationService.SCHEMA_VERSION))
                .andExpect(jsonPath("$.ruleSetId").value("gc-default-control-mapping"))
                .andExpect(jsonPath("$.candidateCount").value(0));
    }

    @Test
    void identifyRequiresThreatPackId() throws Exception {
        mockMvc.perform(get("/api/v1/control-identification").param("project", "ground-control"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void coverageReturnsCoveringControls() throws Exception {
        var coverage = new ThreatControlCoverage(
                THREAT_ID, List.of(new CoveredControl(CONTROL_ID, "AC-3", "Access", true, false)));
        when(confirmationService.controlsCoveringThreat(PROJECT_ID, THREAT_ID)).thenReturn(coverage);

        mockMvc.perform(get("/api/v1/control-identification/coverage")
                        .param("project", "ground-control")
                        .param("threatModelId", THREAT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlCount").value(1))
                .andExpect(jsonPath("$.controls[0].controlUid").value("AC-3"))
                .andExpect(jsonPath("$.controls[0].viaRiskControlMapping").value(true));
    }

    @Test
    void confirmRecordsMappingAndReturnsIds() throws Exception {
        var mappingId = UUID.fromString("44444444-4444-4444-4444-444444444400");
        var linkId = UUID.fromString("55555555-5555-5555-5555-555555555500");
        when(confirmationService.confirm(eq(PROJECT_ID), eq(THREAT_ID), eq(CONTROL_ID), any(), any(), any()))
                .thenReturn(new ControlMappingConfirmation(mappingId, linkId, true, true));

        var body = "{\"threatModelId\":\"" + THREAT_ID + "\",\"controlId\":\"" + CONTROL_ID + "\"}";
        mockMvc.perform(post("/api/v1/control-identification/confirmations")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskControlMappingId").value(mappingId.toString()))
                .andExpect(jsonPath("$.threatModelLinkId").value(linkId.toString()))
                .andExpect(jsonPath("$.mappingCreated").value(true))
                .andExpect(jsonPath("$.linkCreated").value(true));
    }

    @Test
    void confirmRejectsMissingControlId() throws Exception {
        var body = "{\"threatModelId\":\"" + THREAT_ID + "\"}";
        mockMvc.perform(post("/api/v1/control-identification/confirmations")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }
}

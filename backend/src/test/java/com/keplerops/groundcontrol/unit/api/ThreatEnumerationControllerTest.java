package com.keplerops.groundcontrol.unit.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.threatenumeration.ThreatEnumerationController;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatCandidate;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationLimitation;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationResult;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationService;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatEnumerationLimitationReason;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ThreatEnumerationController.class)
class ThreatEnumerationControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111100");
    private static final UUID SNAPSHOT_ID = UUID.fromString("33333333-3333-3333-3333-333333333300");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ThreatEnumerationService enumerationService;

    @MockitoBean
    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(projectService.resolveProjectIdentifier("ground-control")).thenReturn("ground-control");
    }

    private ThreatEnumerationResult emptyResult() {
        return new ThreatEnumerationResult(
                ThreatEnumerationService.SCHEMA_VERSION,
                "stride-baseline",
                "1.0.0",
                "sha256:abc",
                null,
                null,
                List.of(),
                List.of());
    }

    private ThreatEnumerationResult resultWithCandidate() {
        var candidate = new ThreatCandidate(
                "stride.component.tampering",
                ThreatRuleCategory.STRIDE_BASELINE,
                StrideCategory.TAMPERING,
                "svc.auth",
                ArchitectureModelElementKind.COMPONENT,
                Map.of("elementKind", "COMPONENT", "predicate", "ALWAYS"),
                "Component svc.auth may be tampered with.");
        return new ThreatEnumerationResult(
                ThreatEnumerationService.SCHEMA_VERSION,
                "stride-baseline",
                "1.0.0",
                "sha256:abc",
                "snap-001",
                "model/v1",
                List.of(candidate),
                List.of());
    }

    @Test
    void enumerateLatestReturnsEmptyResultWhenNoSnapshot() throws Exception {
        when(enumerationService.enumerateLatest(eq(PROJECT_ID), eq("stride-baseline"), isNull()))
                .thenReturn(emptyResult());

        mockMvc.perform(get("/api/v1/threat-enumeration")
                        .param("project", "ground-control")
                        .param("packId", "stride-baseline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(ThreatEnumerationService.SCHEMA_VERSION))
                .andExpect(jsonPath("$.packId").value("stride-baseline"))
                .andExpect(jsonPath("$.candidateCount").value(0))
                .andExpect(jsonPath("$.projectIdentifier").value("ground-control"));
    }

    @Test
    void enumerateLatestReturnsCandidates() throws Exception {
        when(enumerationService.enumerateLatest(eq(PROJECT_ID), eq("stride-baseline"), isNull()))
                .thenReturn(resultWithCandidate());

        mockMvc.perform(get("/api/v1/threat-enumeration")
                        .param("project", "ground-control")
                        .param("packId", "stride-baseline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateCount").value(1))
                .andExpect(jsonPath("$.candidates[0].producingRuleId").value("stride.component.tampering"))
                .andExpect(jsonPath("$.candidates[0].strideCategory").value("TAMPERING"))
                .andExpect(jsonPath("$.candidates[0].elementStableKey").value("svc.auth"))
                .andExpect(jsonPath("$.candidates[0].matchedFacts.predicate").value("ALWAYS"));
    }

    @Test
    void enumerateSpecificSnapshotCallsEnumerateSnapshot() throws Exception {
        when(enumerationService.enumerateSnapshot(eq(PROJECT_ID), eq(SNAPSHOT_ID), eq("stride-baseline"), isNull()))
                .thenReturn(resultWithCandidate());

        mockMvc.perform(get("/api/v1/threat-enumeration")
                        .param("project", "ground-control")
                        .param("packId", "stride-baseline")
                        .param("snapshotId", SNAPSHOT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateCount").value(1));
    }

    @Test
    void enumerateWithVersionParamPassesVersionToService() throws Exception {
        when(enumerationService.enumerateLatest(eq(PROJECT_ID), eq("stride-baseline"), eq("1.0.0")))
                .thenReturn(emptyResult());

        mockMvc.perform(get("/api/v1/threat-enumeration")
                        .param("project", "ground-control")
                        .param("packId", "stride-baseline")
                        .param("version", "1.0.0"))
                .andExpect(status().isOk());
    }

    @Test
    void enumerateReturns404WhenPackNotFound() throws Exception {
        when(enumerationService.enumerateLatest(eq(PROJECT_ID), eq("unknown-pack"), isNull()))
                .thenThrow(new NotFoundException("No available versions found for pack 'unknown-pack'"));

        mockMvc.perform(get("/api/v1/threat-enumeration")
                        .param("project", "ground-control")
                        .param("packId", "unknown-pack"))
                .andExpect(status().isNotFound());
    }

    @Test
    void enumerateWithLimitationsIncludesLimitationCount() throws Exception {
        var limitation = new ThreatEnumerationLimitation(
                ThreatEnumerationLimitationReason.NO_SNAPSHOT, "No snapshot found", null);
        var result = new ThreatEnumerationResult(
                ThreatEnumerationService.SCHEMA_VERSION,
                "stride-baseline",
                "1.0.0",
                "sha256:abc",
                null,
                null,
                List.of(),
                List.of(limitation));
        when(enumerationService.enumerateLatest(eq(PROJECT_ID), eq("stride-baseline"), isNull()))
                .thenReturn(result);

        mockMvc.perform(get("/api/v1/threat-enumeration")
                        .param("project", "ground-control")
                        .param("packId", "stride-baseline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limitationCount").value(1))
                .andExpect(jsonPath("$.limitations[0].reason").value("NO_SNAPSHOT"));
    }
}

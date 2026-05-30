package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.riskscenarios.KeyRiskIndicatorController;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.KeyRiskIndicator;
import com.keplerops.groundcontrol.domain.riskscenarios.service.KeyRiskIndicatorService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.KriThresholdBand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(KeyRiskIndicatorController.class)
class KeyRiskIndicatorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KeyRiskIndicatorService service;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID KRI_ID = UUID.fromString("00000000-0000-0000-0000-000000000900");
    private static final Instant NOW = Instant.parse("2026-04-04T12:00:00Z");

    private KeyRiskIndicator makeKri() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var kri = new KeyRiskIndicator(project, "KRI-001", "Patch backlog");
        kri.setMetricUnit("days");
        kri.setYellowThreshold(new BigDecimal("14"));
        kri.setRedThreshold(new BigDecimal("30"));
        kri.setOwner("Patch Mgmt Lead");
        setField(kri, "id", KRI_ID);
        setField(kri, "createdAt", NOW);
        setField(kri, "updatedAt", NOW);
        return kri;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any())).thenReturn(makeKri());

        mockMvc.perform(
                        post("/api/v1/key-risk-indicators")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "KRI-001",
                                  "name": "Patch backlog",
                                  "metricUnit": "days",
                                  "yellowThreshold": 14,
                                  "redThreshold": 30
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(KRI_ID.toString())))
                .andExpect(jsonPath("$.graphNodeId", is("KEY_RISK_INDICATOR:" + KRI_ID)))
                .andExpect(jsonPath("$.uid", is("KRI-001")))
                .andExpect(jsonPath("$.yellowThreshold", is(14)))
                .andExpect(jsonPath("$.redThreshold", is(30)));
    }

    @Test
    void recordMeasurementReturnsUpdatedBand() throws Exception {
        var kri = makeKri();
        setField(kri, "currentValue", new BigDecimal("45"));
        setField(kri, "currentBand", KriThresholdBand.RED);
        setField(kri, "lastMeasuredAt", NOW);
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.recordMeasurement(eq(PROJECT_ID), eq(KRI_ID), any())).thenReturn(kri);

        mockMvc.perform(post("/api/v1/key-risk-indicators/{id}/measurements", KRI_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value": 45}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBand", is("RED")))
                .andExpect(jsonPath("$.currentValue", is(45)));
    }

    @Test
    void getByIdReturnsKri() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.getById(PROJECT_ID, KRI_ID)).thenReturn(makeKri());

        mockMvc.perform(get("/api/v1/key-risk-indicators/{id}", KRI_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("KRI-001")));
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/key-risk-indicators/{id}", KRI_ID).param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(service).delete(PROJECT_ID, KRI_ID);
    }
}

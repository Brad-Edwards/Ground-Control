package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.riskcontrol.RiskControlMappingController;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlMappingService;
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
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
@WebMvcTest(RiskControlMappingController.class)
class RiskControlMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RiskControlMappingService service;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000500");
    private static final UUID SCENARIO_ID = UUID.fromString("00000000-0000-0000-0000-000000000600");
    private static final UUID MAPPING_ID = UUID.fromString("00000000-0000-0000-0000-000000000700");
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    private RiskControlMapping makeMapping() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);

        var control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        setField(control, "id", CONTROL_ID);

        var scenario =
                new RiskScenario(project, "RS-001", "Phishing", "Attacker", "Phishing email", "Users", "Data breach");
        setField(scenario, "id", SCENARIO_ID);

        var mapping = new RiskControlMapping(project, control, scenario, MappingControlRole.PREVENTIVE);
        mapping.setMappingObjective("Prevent credential theft");
        mapping.setMappingScope("Email gateway only");
        setField(mapping, "id", MAPPING_ID);
        setField(mapping, "createdAt", NOW);
        setField(mapping, "updatedAt", NOW);
        return mapping;
    }

    @Test
    void createReturns201WithC1C3Fields() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any())).thenReturn(makeMapping());

        mockMvc.perform(
                        post("/api/v1/risk-control-mappings")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "controlId": "00000000-0000-0000-0000-000000000500",
                                  "riskScenarioId": "00000000-0000-0000-0000-000000000600",
                                  "controlRole": "PREVENTIVE",
                                  "mappingObjective": "Prevent credential theft",
                                  "mappingScope": "Email gateway only"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(MAPPING_ID.toString())))
                .andExpect(jsonPath("$.controlId", is(CONTROL_ID.toString())))
                .andExpect(jsonPath("$.riskScenarioId", is(SCENARIO_ID.toString())))
                .andExpect(jsonPath("$.controlRole", is("PREVENTIVE")))
                .andExpect(jsonPath("$.mappingObjective", is("Prevent credential theft")));
    }

    @Test
    void listReturns200() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.listByProject(PROJECT_ID)).thenReturn(List.of(makeMapping()));

        mockMvc.perform(get("/api/v1/risk-control-mappings").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", is(MAPPING_ID.toString())));
    }

    @Test
    void getByIdReturns200() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.getById(PROJECT_ID, MAPPING_ID)).thenReturn(makeMapping());

        mockMvc.perform(get("/api/v1/risk-control-mappings/{id}", MAPPING_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(MAPPING_ID.toString())));
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/risk-control-mappings/{id}", MAPPING_ID).param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(service).delete(PROJECT_ID, MAPPING_ID);
    }

    @Test
    void updateReturns200() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.update(any())).thenReturn(makeMapping());

        mockMvc.perform(
                        put("/api/v1/risk-control-mappings/{id}", MAPPING_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "mappingObjective": "Prevent credential theft",
                                  "controlRole": "PREVENTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(MAPPING_ID.toString())))
                .andExpect(jsonPath("$.controlRole", is("PREVENTIVE")));
    }

    @Test
    void createRequiresControlRole() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(
                        post("/api/v1/risk-control-mappings")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "controlId": "00000000-0000-0000-0000-000000000500",
                                  "riskScenarioId": "00000000-0000-0000-0000-000000000600"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }
}

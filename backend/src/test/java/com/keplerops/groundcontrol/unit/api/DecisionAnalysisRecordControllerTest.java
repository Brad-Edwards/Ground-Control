package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.decisions.DecisionAnalysisRecordController;
import com.keplerops.groundcontrol.domain.decisions.model.DecisionAnalysisRecord;
import com.keplerops.groundcontrol.domain.decisions.service.DecisionAnalysisRecordService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
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
@WebMvcTest(DecisionAnalysisRecordController.class)
class DecisionAnalysisRecordControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DecisionAnalysisRecordService service;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Instant NOW = Instant.parse("2026-05-30T12:00:00Z");

    private DecisionAnalysisRecord make() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var r = new DecisionAnalysisRecord(project, "DR-1", "Buy vs build", "monte_carlo");
        r.setInputs(Map.of("buy.cost", 100_000));
        r.setSimulationParameters(Map.of("seed", 7, "iterations", 10_000));
        r.setResults(Map.of("buy.npv.p50", 50_000));
        r.setAlternatives(List.of("buy", "build"));
        r.setChosenAlternative("buy");
        r.setRationale("buy dominates");
        r.setCreatedBy("alice");
        setField(r, "id", DR_ID);
        setField(r, "createdAt", NOW);
        setField(r, "updatedAt", NOW);
        return r;
    }

    @Test
    void createReturns201WithFullAuditPayload() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(service.create(any())).thenReturn(make());

        mockMvc.perform(
                        post("/api/v1/decisions")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "DR-1",
                                  "title": "Buy vs build",
                                  "modelName": "monte_carlo",
                                  "inputs": {"buy.cost": 100000},
                                  "simulationParameters": {"seed": 7, "iterations": 10000},
                                  "results": {"buy.npv.p50": 50000},
                                  "alternatives": ["buy", "build"],
                                  "chosenAlternative": "buy",
                                  "rationale": "buy dominates"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(DR_ID.toString())))
                .andExpect(jsonPath("$.uid", is("DR-1")))
                .andExpect(jsonPath("$.modelName", is("monte_carlo")))
                .andExpect(jsonPath("$.chosenAlternative", is("buy")))
                .andExpect(jsonPath("$.createdBy", is("alice")))
                .andExpect(jsonPath("$.alternatives.length()", is(2)))
                .andExpect(jsonPath("$.simulationParameters.seed", is(7)))
                .andExpect(jsonPath("$.results['buy.npv.p50']", is(50000)));
    }
}

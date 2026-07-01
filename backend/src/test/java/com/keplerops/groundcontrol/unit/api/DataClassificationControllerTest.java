package com.keplerops.groundcontrol.unit.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.dataclassification.DataClassificationController;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationEvaluationResult;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationEvaluationService;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationFinding;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeService;
import com.keplerops.groundcontrol.domain.dataclassification.service.DefaultDataClassificationLattice;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationFindingReason;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
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
@WebMvcTest(DataClassificationController.class)
class DataClassificationControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-1111111111aa");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataClassificationLatticeService latticeService;

    @MockitoBean
    private DataClassificationEvaluationService evaluationService;

    @MockitoBean
    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(projectService.resolveProjectIdentifier("ground-control")).thenReturn("ground-control");
    }

    @Test
    void getLatticeReturnsActiveDefinition() throws Exception {
        when(latticeService.resolveActiveDefinition(PROJECT_ID))
                .thenReturn(DefaultDataClassificationLattice.definition());

        mockMvc.perform(get("/api/v1/data-classification/lattice").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", org.hamcrest.Matchers.is("DEFAULT")))
                .andExpect(jsonPath("$.labelCount", org.hamcrest.Matchers.is(7)));
    }

    @Test
    void putLatticeReplacesPolicy() throws Exception {
        when(latticeService.replace(eq(PROJECT_ID), any())).thenReturn(DefaultDataClassificationLattice.definition());

        mockMvc.perform(
                        put("/api/v1/data-classification/lattice")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "labels": [
                                    {"key": "PUBLIC", "displayName": "Public"},
                                    {"key": "SECRET", "displayName": "Secret"}
                                  ],
                                  "permittedFlows": [
                                    {"from": "PUBLIC", "to": "SECRET"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", org.hamcrest.Matchers.is("DEFAULT")))
                .andExpect(jsonPath("$.labelCount", org.hamcrest.Matchers.is(7)));

        verify(latticeService).replace(eq(PROJECT_ID), any());
    }

    @Test
    void putLatticeRejectsEmptyLabelSet() throws Exception {
        mockMvc.perform(put("/api/v1/data-classification/lattice")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\": [], \"permittedFlows\": []}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void evaluationReturnsViolations() throws Exception {
        var violation = new DataClassificationFinding(
                "flow.users-to-log",
                "db.users",
                "log.app",
                "PII",
                "PUBLIC",
                DataClassificationFindingReason.LABEL_FLOW_NOT_PERMITTED,
                "Flow from PII to PUBLIC is not a permitted label flow");
        when(evaluationService.evaluateLatest(PROJECT_ID))
                .thenReturn(new DataClassificationEvaluationResult(
                        "data-classification-evaluation/v1",
                        "dcl/abc",
                        DataClassificationSource.DEFAULT,
                        "architecture-model/v1",
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        1,
                        List.of(violation),
                        List.of()));

        mockMvc.perform(get("/api/v1/data-classification/evaluation").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.violationCount", org.hamcrest.Matchers.is(1)))
                .andExpect(jsonPath("$.violations[0].reason", org.hamcrest.Matchers.is("LABEL_FLOW_NOT_PERMITTED")));
    }

    @Test
    void deleteRevertsToDefault() throws Exception {
        when(latticeService.resetToDefault(PROJECT_ID)).thenReturn(DefaultDataClassificationLattice.definition());

        mockMvc.perform(delete("/api/v1/data-classification/lattice").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", org.hamcrest.Matchers.is("DEFAULT")));
    }
}

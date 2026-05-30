package com.keplerops.groundcontrol.unit.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.requirements.TraceabilityMatrixController;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityMatrixResult;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityMatrixService;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import com.keplerops.groundcontrol.domain.requirements.state.SyncStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * @WebMvcTest slice for TraceabilityMatrixController — required for Sonar coverage (GC-Q003).
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(TraceabilityMatrixController.class)
class TraceabilityMatrixControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TraceabilityMatrixService matrixService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REQ_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final UUID LINK_ID = UUID.fromString("00000000-0000-0000-0000-000000000300");

    @BeforeEach
    void setUp() {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
    }

    private TraceabilityMatrixResult emptyResult() {
        return new TraceabilityMatrixResult(List.of(), List.of(), 0, 0, 0);
    }

    private TraceabilityMatrixResult composedResult() {
        var cell = new TraceabilityMatrixResult.MatrixCell(
                LINK_ID,
                LinkType.IMPLEMENTS,
                ArtifactType.CODE_FILE,
                "backend/Foo.java",
                "Foo",
                "https://example.com/Foo.java",
                SyncStatus.SYNCED);
        var row = new TraceabilityMatrixResult.MatrixRow(
                REQ_ID,
                "GC-Q003",
                "Traceability Matrix",
                Status.ACTIVE,
                2,
                Priority.MUST,
                List.of(cell),
                List.of(LinkType.IMPLEMENTS),
                true);
        var implementsColumn = new TraceabilityMatrixResult.LinkTypeColumn(LinkType.IMPLEMENTS, 1, 1, 1);
        var testsColumn = new TraceabilityMatrixResult.LinkTypeColumn(LinkType.TESTS, 0, 1, 0);
        return new TraceabilityMatrixResult(List.of(row), List.of(implementsColumn, testsColumn), 1, 1, 1);
    }

    @Nested
    class HappyPath {

        @Test
        void returns200WithComposedBody() throws Exception {
            when(matrixService.matrix(any(), any(), any(), any())).thenReturn(composedResult());

            mockMvc.perform(get("/api/v1/requirements/traceability/matrix").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize(1)))
                    .andExpect(jsonPath("$.rows[0].uid", is("GC-Q003")))
                    .andExpect(jsonPath("$.rows[0].status", is("ACTIVE")))
                    .andExpect(jsonPath("$.rows[0].wave", is(2)))
                    .andExpect(jsonPath("$.rows[0].priority", is("MUST")))
                    .andExpect(jsonPath("$.rows[0].cells", hasSize(1)))
                    .andExpect(jsonPath("$.rows[0].cells[0].linkType", is("IMPLEMENTS")))
                    .andExpect(jsonPath("$.rows[0].cells[0].artifactType", is("CODE_FILE")))
                    .andExpect(jsonPath("$.rows[0].cells[0].artifactIdentifier", is("backend/Foo.java")))
                    .andExpect(jsonPath("$.rows[0].coveredLinkTypes", hasSize(1)))
                    .andExpect(jsonPath("$.rows[0].hasGap", is(true)))
                    .andExpect(jsonPath("$.columns", hasSize(2)))
                    .andExpect(jsonPath("$.columns[0].linkType", is("IMPLEMENTS")))
                    .andExpect(jsonPath("$.columns[0].coveredRequirements", is(1)))
                    .andExpect(jsonPath("$.columns[1].linkType", is("TESTS")))
                    .andExpect(jsonPath("$.columns[1].coveredRequirements", is(0)))
                    .andExpect(jsonPath("$.requirementCount", is(1)))
                    .andExpect(jsonPath("$.linkedRequirementCount", is(1)))
                    .andExpect(jsonPath("$.gapCount", is(1)));
        }

        @Test
        void returns200ForEmptyProject() throws Exception {
            when(matrixService.matrix(any(), any(), any(), any())).thenReturn(emptyResult());

            mockMvc.perform(get("/api/v1/requirements/traceability/matrix").param("project", "ground-control"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows", hasSize(0)))
                    .andExpect(jsonPath("$.requirementCount", is(0)));
        }

        @Test
        void passesFiltersThroughToService() throws Exception {
            when(matrixService.matrix(eq(PROJECT_ID), eq(2), eq(Status.ACTIVE), eq(LinkType.IMPLEMENTS)))
                    .thenReturn(emptyResult());

            mockMvc.perform(get("/api/v1/requirements/traceability/matrix")
                            .param("project", "ground-control")
                            .param("wave", "2")
                            .param("status", "ACTIVE")
                            .param("linkType", "IMPLEMENTS"))
                    .andExpect(status().isOk());

            org.mockito.Mockito.verify(projectService).resolveProjectId("ground-control");
            org.mockito.Mockito.verify(matrixService).matrix(PROJECT_ID, 2, Status.ACTIVE, LinkType.IMPLEMENTS);
        }

        @Test
        void passesNullFiltersWhenOmitted() throws Exception {
            when(matrixService.matrix(any(), any(), any(), any())).thenReturn(emptyResult());

            mockMvc.perform(get("/api/v1/requirements/traceability/matrix").param("project", "ground-control"))
                    .andExpect(status().isOk());

            org.mockito.Mockito.verify(matrixService).matrix(eq(PROJECT_ID), isNull(), isNull(), isNull());
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void returns404WhenProjectNotFound() throws Exception {
            when(projectService.resolveProjectId(any())).thenThrow(new NotFoundException("Project not found"));

            mockMvc.perform(get("/api/v1/requirements/traceability/matrix").param("project", "no-such-project"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void returns400WhenStatusEnumInvalid() throws Exception {
            mockMvc.perform(get("/api/v1/requirements/traceability/matrix")
                            .param("project", "ground-control")
                            .param("status", "NOT_A_STATUS"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns400WhenLinkTypeEnumInvalid() throws Exception {
            mockMvc.perform(get("/api/v1/requirements/traceability/matrix")
                            .param("project", "ground-control")
                            .param("linkType", "NOT_A_LINK_TYPE"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns400WhenWaveNotInteger() throws Exception {
            mockMvc.perform(get("/api/v1/requirements/traceability/matrix")
                            .param("project", "ground-control")
                            .param("wave", "not-a-number"))
                    .andExpect(status().isBadRequest());
        }
    }
}

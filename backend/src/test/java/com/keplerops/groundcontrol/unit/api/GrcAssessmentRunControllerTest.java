package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.grcassessment.GrcAssessmentRunController;
import com.keplerops.groundcontrol.domain.grcassessment.model.GrcAssessmentRun;
import com.keplerops.groundcontrol.domain.grcassessment.service.GrcAssessmentRunService;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentMode;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewPolicy;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentRunState;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentScopeType;
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
@WebMvcTest(GrcAssessmentRunController.class)
class GrcAssessmentRunControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111129");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222221129");
    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GrcAssessmentRunService assessmentRunService;

    @MockitoBean
    private ProjectService projectService;

    @Test
    void createReturnsDurableAssessmentRunRecord() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assessmentRunService.createRun(any())).thenReturn(sampleRun(GrcAssessmentRunState.READY_FOR_REVIEW));

        mockMvc.perform(
                        post("/api/v1/grc-assessment-runs")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                          "mode": "MODEL",
                          "scopeType": "BOUNDARY",
                          "scopeValues": ["payments"],
                          "commitSha": "25c991231cf2a1464792846b083d1bd885299b3c",
                          "languages": ["java"],
                          "surfaces": ["application"],
                          "reviewPolicy": "REQUIRED",
                          "reviewDecision": "REQUEST_REVIEW",
                          "idempotencyKey": "gc-1129-payments",
                          "declaredBoundaries": [
                            {
                              "key": "payments",
                              "name": "Payments",
                              "pathSelectors": ["backend/payments/**"],
                              "surfaces": ["application"]
                            }
                          ]
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(RUN_ID.toString())))
                .andExpect(jsonPath("$.mode", is("MODEL")))
                .andExpect(jsonPath("$.scopeType", is("BOUNDARY")))
                .andExpect(jsonPath("$.state", is("READY_FOR_REVIEW")))
                .andExpect(jsonPath("$.partitionCount", is(1)))
                .andExpect(jsonPath("$.partitions", hasSize(1)))
                .andExpect(jsonPath("$.partitions[0].partitionKey", is("boundary:payments")));
    }

    @Test
    void reviewApprovesAndCommitsRun() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assessmentRunService.reviewRun(any())).thenReturn(sampleRun(GrcAssessmentRunState.COMMITTED));

        mockMvc.perform(
                        post("/api/v1/grc-assessment-runs/{id}/review", RUN_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                          "reviewDecision": "APPROVED",
                          "reviewedBy": "alice",
                          "reviewRationale": "Approved baseline bootstrap."
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(RUN_ID.toString())))
                .andExpect(jsonPath("$.state", is("COMMITTED")));
    }

    @Test
    void listRunsReturnsProjectScopedRuns() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(assessmentRunService.listRuns(PROJECT_ID, 25))
                .thenReturn(List.of(sampleRun(GrcAssessmentRunState.COMMITTED)));

        mockMvc.perform(get("/api/v1/grc-assessment-runs").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(RUN_ID.toString())))
                .andExpect(jsonPath("$[0].graphEffectCount", is(1)));
    }

    private GrcAssessmentRun sampleRun(GrcAssessmentRunState state) {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var run = new GrcAssessmentRun(
                project,
                GrcAssessmentMode.MODEL,
                GrcAssessmentScopeType.BOUNDARY,
                COMMIT,
                null,
                List.of("java"),
                List.of("application"),
                GrcAssessmentReviewPolicy.REQUIRED,
                "gc-1129-payments");
        setField(run, "id", RUN_ID);
        setField(run, "createdAt", Instant.parse("2026-07-04T00:00:00Z"));
        setField(run, "updatedAt", Instant.parse("2026-07-04T00:00:00Z"));
        run.recordPartitions(1, List.of(Map.of("partitionKey", "boundary:payments", "scopeType", "BOUNDARY")), 1);
        if (state == GrcAssessmentRunState.COMMITTED) {
            run.recordGraphEffects(List.of(Map.of("effectType", "DERIVATION_RUN", "effectId", "run-1")));
        }
        run.setState(state);
        return run;
    }
}

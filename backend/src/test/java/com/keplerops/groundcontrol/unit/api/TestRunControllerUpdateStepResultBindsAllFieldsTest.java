package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.testcases.TestRunController;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.testcases.model.TestCase;
import com.keplerops.groundcontrol.domain.testcases.model.TestCaseStep;
import com.keplerops.groundcontrol.domain.testcases.model.TestPlan;
import com.keplerops.groundcontrol.domain.testcases.model.TestRun;
import com.keplerops.groundcontrol.domain.testcases.model.TestRunCaseResult;
import com.keplerops.groundcontrol.domain.testcases.model.TestRunStepResult;
import com.keplerops.groundcontrol.domain.testcases.model.TestSuite;
import com.keplerops.groundcontrol.domain.testcases.service.TestRunService;
import com.keplerops.groundcontrol.domain.testcases.service.UpdateTestRunCursorCommand;
import com.keplerops.groundcontrol.domain.testcases.service.UpdateTestRunStepResultCommand;
import com.keplerops.groundcontrol.domain.testcases.state.TestCasePriority;
import com.keplerops.groundcontrol.domain.testcases.state.TestCaseType;
import com.keplerops.groundcontrol.domain.testcases.state.TestRunCaseResultStatus;
import com.keplerops.groundcontrol.domain.testcases.state.TestSuitePopulationMode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Split from TestRunControllerTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(TestRunController.class)
class TestRunControllerUpdateStepResultBindsAllFieldsTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TestRunService testRunService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID SUITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000300");
    private static final UUID TC_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final Instant NOW = Instant.parse("2026-05-18T12:00:00Z");

    private TestRun makeRun() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var plan = new TestPlan(project, "TP-001", "Wave-1");
        setField(plan, "id", PLAN_ID);
        var suite = new TestSuite(project, "TS-001", "Smoke", TestSuitePopulationMode.STATIC);
        setField(suite, "id", SUITE_ID);
        var run = new TestRun(project, plan, suite, "TR-001", "Smoke pass 1");
        run.setEnvironment("staging");
        run.setVersion("1.2.0");
        run.setBuild("build-42");
        run.setStartAt(Instant.parse("2026-06-01T00:00:00Z"));
        run.setEndAt(Instant.parse("2026-06-30T00:00:00Z"));
        setField(run, "id", RUN_ID);
        setField(run, "createdAt", NOW);
        setField(run, "updatedAt", NOW);
        return run;
    }

    // ------------------------------------------------------------------
    // TC-009 / ADR-050 — step results + cursor
    // ------------------------------------------------------------------

    private static final UUID CASE_RESULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000a01");
    private static final UUID STEP_RESULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000b01");

    private TestRunStepResult makeStepResult() {
        var run = makeRun();
        var project = run.getProject();
        var tc = new TestCase(project, "TC-001", "Login", TestCaseType.MANUAL, TestCasePriority.MEDIUM);
        setField(tc, "id", TC_ID);
        var caseResult = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(caseResult, "id", CASE_RESULT_ID);
        var step = new TestCaseStep(tc, 1, "Open page", "Form visible");
        setField(step, "id", UUID.randomUUID());
        var stepResult = new TestRunStepResult(caseResult, step, 1, "Open page", "Form visible", 0);
        setField(stepResult, "id", STEP_RESULT_ID);
        setField(stepResult, "createdAt", NOW);
        setField(stepResult, "updatedAt", NOW);
        return stepResult;
    }

    @Test
    void updateStepResultBindsAllFields() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var updated = makeStepResult();
        updated.setStatus(TestRunCaseResultStatus.PASSED);
        updated.setComment("Looks good");
        updated.setExecutedAt(Instant.parse("2026-06-15T12:00:00Z"));
        when(testRunService.updateStepResult(
                        eq(PROJECT_ID),
                        eq(RUN_ID),
                        eq(CASE_RESULT_ID),
                        eq(STEP_RESULT_ID),
                        any(UpdateTestRunStepResultCommand.class)))
                .thenReturn(updated);

        mockMvc.perform(put(
                                "/api/v1/test-runs/{id}/results/{caseResultId}/steps/{stepResultId}",
                                RUN_ID,
                                CASE_RESULT_ID,
                                STEP_RESULT_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PASSED\",\"comment\":\"Looks good\","
                                + "\"executedAt\":\"2026-06-15T12:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PASSED")))
                .andExpect(jsonPath("$.comment", is("Looks good")))
                .andExpect(jsonPath("$.executedAt", is("2026-06-15T12:00:00Z")));

        ArgumentCaptor<UpdateTestRunStepResultCommand> captor =
                ArgumentCaptor.forClass(UpdateTestRunStepResultCommand.class);
        verify(testRunService)
                .updateStepResult(eq(PROJECT_ID), eq(RUN_ID), eq(CASE_RESULT_ID), eq(STEP_RESULT_ID), captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.status()).isEqualTo(TestRunCaseResultStatus.PASSED);
        assertThat(cmd.comment()).isEqualTo("Looks good");
        assertThat(cmd.executedAt()).isEqualTo(Instant.parse("2026-06-15T12:00:00Z"));
        assertThat(cmd.clearComment()).isFalse();
        assertThat(cmd.clearExecutedAt()).isFalse();
    }

    @Test
    void updateStepResultPropagatesClearFlags() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(testRunService.updateStepResult(
                        eq(PROJECT_ID),
                        eq(RUN_ID),
                        eq(CASE_RESULT_ID),
                        eq(STEP_RESULT_ID),
                        any(UpdateTestRunStepResultCommand.class)))
                .thenReturn(makeStepResult());

        mockMvc.perform(put(
                                "/api/v1/test-runs/{id}/results/{caseResultId}/steps/{stepResultId}",
                                RUN_ID,
                                CASE_RESULT_ID,
                                STEP_RESULT_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOT_RUN\",\"clearComment\":true,\"clearExecutedAt\":true}"))
                .andExpect(status().isOk())
                // Response-body cover: makeStepResult() returns a step with
                // null comment + null executedAt, so jsonPath().doesNotExist
                // catches a future regression in
                // TestRunStepResultResponse.from() that echoed the request
                // body instead of the serialized entity.
                .andExpect(jsonPath("$.comment").doesNotExist())
                .andExpect(jsonPath("$.executedAt").doesNotExist());

        ArgumentCaptor<UpdateTestRunStepResultCommand> captor =
                ArgumentCaptor.forClass(UpdateTestRunStepResultCommand.class);
        verify(testRunService)
                .updateStepResult(eq(PROJECT_ID), eq(RUN_ID), eq(CASE_RESULT_ID), eq(STEP_RESULT_ID), captor.capture());
        assertThat(captor.getValue().clearComment()).isTrue();
        assertThat(captor.getValue().clearExecutedAt()).isTrue();
    }

    @Test
    void updateStepResultAcceptsBodyWithoutStatus() throws Exception {
        // Mirror of updateResultAcceptsBodyWithoutStatus for the step-result
        // path: a comment-only autosave forwards null status; the service
        // preserves the existing value (regression guard for the codex
        // review cycle 1 "Comment saves can revert a newer status" finding).
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(testRunService.updateStepResult(
                        eq(PROJECT_ID),
                        eq(RUN_ID),
                        eq(CASE_RESULT_ID),
                        eq(STEP_RESULT_ID),
                        any(UpdateTestRunStepResultCommand.class)))
                .thenReturn(makeStepResult());

        mockMvc.perform(put(
                                "/api/v1/test-runs/{id}/results/{caseResultId}/steps/{stepResultId}",
                                RUN_ID,
                                CASE_RESULT_ID,
                                STEP_RESULT_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"comment-only autosave\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateTestRunStepResultCommand> captor =
                ArgumentCaptor.forClass(UpdateTestRunStepResultCommand.class);
        verify(testRunService)
                .updateStepResult(eq(PROJECT_ID), eq(RUN_ID), eq(CASE_RESULT_ID), eq(STEP_RESULT_ID), captor.capture());
        assertThat(captor.getValue().status()).isNull();
        assertThat(captor.getValue().comment()).isEqualTo("comment-only autosave");
    }

    @Test
    void updateCursorBindsBothFieldsAndReturnsRun() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var run = makeRun();
        run.setCurrentCaseResultId(CASE_RESULT_ID);
        run.setCurrentStepResultId(STEP_RESULT_ID);
        when(testRunService.updateCursor(eq(PROJECT_ID), eq(RUN_ID), any(UpdateTestRunCursorCommand.class)))
                .thenReturn(run);

        mockMvc.perform(put("/api/v1/test-runs/{id}/cursor", RUN_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentCaseResultId\":\"" + CASE_RESULT_ID + "\"," + "\"currentStepResultId\":\""
                                + STEP_RESULT_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentCaseResultId", is(CASE_RESULT_ID.toString())))
                .andExpect(jsonPath("$.currentStepResultId", is(STEP_RESULT_ID.toString())));

        ArgumentCaptor<UpdateTestRunCursorCommand> captor = ArgumentCaptor.forClass(UpdateTestRunCursorCommand.class);
        verify(testRunService).updateCursor(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        assertThat(captor.getValue().currentCaseResultId()).isEqualTo(CASE_RESULT_ID);
        assertThat(captor.getValue().currentStepResultId()).isEqualTo(STEP_RESULT_ID);
        assertThat(captor.getValue().clearCursor()).isFalse();
    }

    @Test
    void updateCursorPropagatesClearFlag() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(testRunService.updateCursor(eq(PROJECT_ID), eq(RUN_ID), any(UpdateTestRunCursorCommand.class)))
                .thenReturn(makeRun());

        mockMvc.perform(put("/api/v1/test-runs/{id}/cursor", RUN_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearCursor\":true}"))
                .andExpect(status().isOk())
                // makeRun() returns a run with null cursor fields, so these
                // assertions pin the cleared-cursor shape against a future
                // TestRunResponse.from() refactor that might echo the
                // pre-save cursor.
                .andExpect(jsonPath("$.currentCaseResultId").doesNotExist())
                .andExpect(jsonPath("$.currentStepResultId").doesNotExist());

        ArgumentCaptor<UpdateTestRunCursorCommand> captor = ArgumentCaptor.forClass(UpdateTestRunCursorCommand.class);
        verify(testRunService).updateCursor(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        assertThat(captor.getValue().clearCursor()).isTrue();
    }
}

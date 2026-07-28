package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.keplerops.groundcontrol.domain.testcases.model.TestRunTesterAssignment;
import com.keplerops.groundcontrol.domain.testcases.model.TestSuite;
import com.keplerops.groundcontrol.domain.testcases.service.CreateTestRunCommand;
import com.keplerops.groundcontrol.domain.testcases.service.TestRunService;
import com.keplerops.groundcontrol.domain.testcases.service.UpdateTestRunCaseResultCommand;
import com.keplerops.groundcontrol.domain.testcases.service.UpdateTestRunCommand;
import com.keplerops.groundcontrol.domain.testcases.state.TestCasePriority;
import com.keplerops.groundcontrol.domain.testcases.state.TestCaseType;
import com.keplerops.groundcontrol.domain.testcases.state.TestRunCaseResultStatus;
import com.keplerops.groundcontrol.domain.testcases.state.TestRunStatus;
import com.keplerops.groundcontrol.domain.testcases.state.TestSuitePopulationMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
class TestRunControllerTest {
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

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidCreatePayloads() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "blank uid",
                        "{\"uid\":\"\",\"name\":\"Run\","
                                + "\"testPlanId\":\"00000000-0000-0000-0000-000000000100\","
                                + "\"testSuiteId\":\"00000000-0000-0000-0000-000000000200\"}"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "missing testPlanId",
                        "{\"uid\":\"TR-001\",\"name\":\"Run\","
                                + "\"testSuiteId\":\"00000000-0000-0000-0000-000000000200\"}"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "missing testSuiteId",
                        "{\"uid\":\"TR-001\",\"name\":\"Run\","
                                + "\"testPlanId\":\"00000000-0000-0000-0000-000000000100\"}"));
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
    void createReturns201AndBindsAllFields() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(testRunService.create(any(CreateTestRunCommand.class))).thenReturn(makeRun());

        mockMvc.perform(
                        post("/api/v1/test-runs")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "TR-001",
                                  "name": "Smoke pass 1",
                                  "testPlanId": "00000000-0000-0000-0000-000000000100",
                                  "testSuiteId": "00000000-0000-0000-0000-000000000200",
                                  "environment": "staging",
                                  "version": "1.2.0",
                                  "build": "build-42",
                                  "startAt": "2026-06-01T00:00:00Z",
                                  "endAt": "2026-06-30T00:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.uid", is("TR-001")))
                .andExpect(jsonPath("$.status", is("PLANNED")))
                .andExpect(jsonPath("$.testPlanUid", is("TP-001")))
                .andExpect(jsonPath("$.testSuiteUid", is("TS-001")))
                .andExpect(jsonPath("$.environment", is("staging")));

        ArgumentCaptor<CreateTestRunCommand> captor = ArgumentCaptor.forClass(CreateTestRunCommand.class);
        verify(testRunService).create(captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.uid()).isEqualTo("TR-001");
        assertThat(cmd.name()).isEqualTo("Smoke pass 1");
        assertThat(cmd.testPlanId()).isEqualTo(PLAN_ID);
        assertThat(cmd.testSuiteId()).isEqualTo(SUITE_ID);
        assertThat(cmd.environment()).isEqualTo("staging");
        assertThat(cmd.version()).isEqualTo("1.2.0");
        assertThat(cmd.build()).isEqualTo("build-42");
        assertThat(cmd.startAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(cmd.endAt()).isEqualTo(Instant.parse("2026-06-30T00:00:00Z"));
    }

    @ParameterizedTest(name = "create returns 422 — {0}")
    @MethodSource("invalidCreatePayloads")
    void createReturns422OnInvalidRequest(String label, String body) throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(post("/api/v1/test-runs")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(testRunService);
    }

    @Test
    void listReturnsRuns() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(testRunService.listByProject(PROJECT_ID)).thenReturn(List.of(makeRun()));

        mockMvc.perform(get("/api/v1/test-runs").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].uid", is("TR-001")));
    }

    @Test
    void getByIdReturnsRun() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(testRunService.getById(PROJECT_ID, RUN_ID)).thenReturn(makeRun());

        mockMvc.perform(get("/api/v1/test-runs/{id}", RUN_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("TR-001")))
                .andExpect(jsonPath("$.name", is("Smoke pass 1")));
    }

    @Test
    void getByUidReturnsRun() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(testRunService.getByUid(PROJECT_ID, "TR-001")).thenReturn(makeRun());

        mockMvc.perform(get("/api/v1/test-runs/uid/{uid}", "TR-001").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("TR-001")));
    }

    @Test
    void updateBindsAllFieldsAndClearFlags() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(testRunService.update(eq(PROJECT_ID), eq(RUN_ID), any(UpdateTestRunCommand.class)))
                .thenReturn(makeRun());

        mockMvc.perform(
                        put("/api/v1/test-runs/{id}", RUN_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "name": "Renamed",
                                  "environment": "prod",
                                  "version": "2.0.0",
                                  "build": "build-50",
                                  "startAt": "2026-07-01T00:00:00Z",
                                  "endAt": "2026-07-31T00:00:00Z",
                                  "clearEnvironment": false,
                                  "clearVersion": false,
                                  "clearBuild": true,
                                  "clearStartAt": false,
                                  "clearEndAt": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("TR-001")))
                // Pass-through assertion — the response body must reflect
                // the mocked service result, not the request payload.
                .andExpect(jsonPath("$.name", is("Smoke pass 1")))
                .andExpect(jsonPath("$.build", is("build-42")));

        ArgumentCaptor<UpdateTestRunCommand> captor = ArgumentCaptor.forClass(UpdateTestRunCommand.class);
        verify(testRunService).update(eq(PROJECT_ID), eq(RUN_ID), captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.name()).isEqualTo("Renamed");
        assertThat(cmd.environment()).isEqualTo("prod");
        assertThat(cmd.version()).isEqualTo("2.0.0");
        // build cleared even though the client supplied "build-50".
        assertThat(cmd.clearBuild()).isTrue();
        assertThat(cmd.startAt()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(cmd.endAt()).isEqualTo(Instant.parse("2026-07-31T00:00:00Z"));
    }

    @Test
    void transitionStatusBindsRequestStatus() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var transitioned = makeRun();
        setField(transitioned, "status", TestRunStatus.IN_PROGRESS);
        when(testRunService.transitionStatus(PROJECT_ID, RUN_ID, TestRunStatus.IN_PROGRESS))
                .thenReturn(transitioned);

        mockMvc.perform(put("/api/v1/test-runs/{id}/status", RUN_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")));
        verify(testRunService).transitionStatus(PROJECT_ID, RUN_ID, TestRunStatus.IN_PROGRESS);
    }

    @Test
    void transitionStatusReturns422WhenStatusMissing() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(put("/api/v1/test-runs/{id}/status", RUN_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(testRunService);
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/test-runs/{id}", RUN_ID).param("project", "ground-control"))
                .andExpect(status().isNoContent());
        verify(testRunService).delete(PROJECT_ID, RUN_ID);
    }

    @Test
    void addTesterReturns201() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var run = makeRun();
        var assignment = new TestRunTesterAssignment(run, "Alex Doe");
        setField(assignment, "id", UUID.randomUUID());
        setField(assignment, "createdAt", NOW);
        setField(assignment, "updatedAt", NOW);
        when(testRunService.addTester(PROJECT_ID, RUN_ID, "Alex Doe")).thenReturn(assignment);

        mockMvc.perform(post("/api/v1/test-runs/{id}/testers", RUN_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"testerName\":\"Alex Doe\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.testerName", is("Alex Doe")));
    }

    @Test
    void addTesterReturns422WhenNameBlank() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(post("/api/v1/test-runs/{id}/testers", RUN_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"testerName\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(testRunService);
    }

    @Test
    void addTesterReturns422OnPathReservedCharacters() throws Exception {
        // A slash in the body would create a tester the DELETE
        // /testers/{testerName} route can't reliably address (encoded
        // slashes are stripped or decoded by the servlet stack before path
        // matching). Reject at create.
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(post("/api/v1/test-runs/{id}/testers", RUN_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"testerName\":\"alex/jones\"}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(testRunService);
    }

    @Test
    void listTestersReturnsAssignments() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var run = makeRun();
        var assignment = new TestRunTesterAssignment(run, "Alex");
        setField(assignment, "id", UUID.randomUUID());
        setField(assignment, "createdAt", NOW);
        setField(assignment, "updatedAt", NOW);
        when(testRunService.listTesters(PROJECT_ID, RUN_ID)).thenReturn(List.of(assignment));

        mockMvc.perform(get("/api/v1/test-runs/{id}/testers", RUN_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].testerName", is("Alex")));
    }

    @Test
    void removeTesterReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/test-runs/{id}/testers/{testerName}", RUN_ID, "Alex")
                        .param("project", "ground-control"))
                .andExpect(status().isNoContent());
        verify(testRunService).removeTester(PROJECT_ID, RUN_ID, "Alex");
    }

    @Test
    void listResultsReturnsRows() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var run = makeRun();
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var tc = new TestCase(project, "TC-001", "Login", TestCaseType.MANUAL, TestCasePriority.MEDIUM);
        setField(tc, "id", TC_ID);
        var result = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(result, "id", UUID.randomUUID());
        setField(result, "createdAt", NOW);
        setField(result, "updatedAt", NOW);
        when(testRunService.listResults(PROJECT_ID, RUN_ID)).thenReturn(List.of(result));

        mockMvc.perform(get("/api/v1/test-runs/{id}/results", RUN_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].testCaseUid", is("TC-001")))
                .andExpect(jsonPath("$[0].status", is("NOT_RUN")));
    }

    @Test
    void updateResultBindsStatusNotesAndClearFlag() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var run = makeRun();
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var tc = new TestCase(project, "TC-001", "Login", TestCaseType.MANUAL, TestCasePriority.MEDIUM);
        setField(tc, "id", TC_ID);
        var result = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        result.setStatus(TestRunCaseResultStatus.FAILED);
        result.setNotes("Step 3 missing");
        setField(result, "id", UUID.randomUUID());
        setField(result, "createdAt", NOW);
        setField(result, "updatedAt", NOW);
        when(testRunService.updateResult(
                        eq(PROJECT_ID), eq(RUN_ID), eq(TC_ID), any(UpdateTestRunCaseResultCommand.class)))
                .thenReturn(result);

        mockMvc.perform(put("/api/v1/test-runs/{id}/results/{testCaseId}", RUN_ID, TC_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\",\"notes\":\"Step 3 missing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("FAILED")))
                .andExpect(jsonPath("$.notes", is("Step 3 missing")));

        ArgumentCaptor<UpdateTestRunCaseResultCommand> captor =
                ArgumentCaptor.forClass(UpdateTestRunCaseResultCommand.class);
        verify(testRunService).updateResult(eq(PROJECT_ID), eq(RUN_ID), eq(TC_ID), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TestRunCaseResultStatus.FAILED);
        assertThat(captor.getValue().notes()).isEqualTo("Step 3 missing");
        assertThat(captor.getValue().clearNotes()).isFalse();
    }

    @Test
    void updateResultAcceptsBodyWithoutStatus() throws Exception {
        // TC-009 codex review cycle 1: status is intentionally optional on
        // UpdateTestRunCaseResultRequest so the runner's notes-only autosave
        // can't stomp a concurrent status flip. The controller forwards an
        // omitted status as null; the service preserves the existing value.
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var run = makeRun();
        var project = run.getProject();
        var tc = new TestCase(project, "TC-001", "Login", TestCaseType.MANUAL, TestCasePriority.MEDIUM);
        setField(tc, "id", TC_ID);
        var result = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        result.setStatus(TestRunCaseResultStatus.PASSED);
        result.setNotes("notes-only autosave");
        setField(result, "id", UUID.randomUUID());
        setField(result, "createdAt", NOW);
        setField(result, "updatedAt", NOW);
        when(testRunService.updateResult(
                        eq(PROJECT_ID), eq(RUN_ID), eq(TC_ID), any(UpdateTestRunCaseResultCommand.class)))
                .thenReturn(result);

        mockMvc.perform(put("/api/v1/test-runs/{id}/results/{testCaseId}", RUN_ID, TC_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"notes-only autosave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PASSED")));

        ArgumentCaptor<UpdateTestRunCaseResultCommand> captor =
                ArgumentCaptor.forClass(UpdateTestRunCaseResultCommand.class);
        verify(testRunService).updateResult(eq(PROJECT_ID), eq(RUN_ID), eq(TC_ID), captor.capture());
        assertThat(captor.getValue().status()).isNull();
    }

    @Test
    void listStepResultsReturnsRowsForCaseResult() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(testRunService.listStepResults(PROJECT_ID, RUN_ID, CASE_RESULT_ID)).thenReturn(List.of(makeStepResult()));

        mockMvc.perform(get("/api/v1/test-runs/{id}/results/{caseResultId}/steps", RUN_ID, CASE_RESULT_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].testRunCaseResultId", is(CASE_RESULT_ID.toString())))
                .andExpect(jsonPath("$[0].stepNumberSnapshot", is(1)))
                .andExpect(jsonPath("$[0].actionSnapshot", is("Open page")))
                .andExpect(jsonPath("$[0].expectedResultSnapshot", is("Form visible")))
                .andExpect(jsonPath("$[0].snapshotOrder", is(0)))
                .andExpect(jsonPath("$[0].status", is("NOT_RUN")));
    }
}

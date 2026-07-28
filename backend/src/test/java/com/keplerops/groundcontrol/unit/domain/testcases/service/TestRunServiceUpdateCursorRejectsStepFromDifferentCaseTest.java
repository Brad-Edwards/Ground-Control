package com.keplerops.groundcontrol.unit.domain.testcases.service;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.testcases.model.TestCase;
import com.keplerops.groundcontrol.domain.testcases.model.TestPlan;
import com.keplerops.groundcontrol.domain.testcases.model.TestRun;
import com.keplerops.groundcontrol.domain.testcases.model.TestRunCaseResult;
import com.keplerops.groundcontrol.domain.testcases.model.TestSuite;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseStepRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestPlanRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestRunCaseResultRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestRunRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestRunStepResultRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestRunTesterAssignmentRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteRepository;
import com.keplerops.groundcontrol.domain.testcases.service.TestRunService;
import com.keplerops.groundcontrol.domain.testcases.service.TestSuiteService;
import com.keplerops.groundcontrol.domain.testcases.service.UpdateTestRunCursorCommand;
import com.keplerops.groundcontrol.domain.testcases.state.TestCasePriority;
import com.keplerops.groundcontrol.domain.testcases.state.TestCaseType;
import com.keplerops.groundcontrol.domain.testcases.state.TestSuitePopulationMode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Split from TestRunServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
class TestRunServiceUpdateCursorRejectsStepFromDifferentCaseTest {
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID SUITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000300");
    private static final UUID TC1_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID TC2_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
    private static final UUID CASE_RESULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000a01");
    private static final UUID STEP_RESULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000b01");
    private static final UUID OTHER_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000c01");
    private static final UUID OTHER_CASE_RESULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000c02");

    private TestRunRepository testRunRepository;
    private TestRunTesterAssignmentRepository testerAssignmentRepository;
    private TestRunCaseResultRepository caseResultRepository;
    private TestRunStepResultRepository stepResultRepository;
    private TestCaseStepRepository testCaseStepRepository;
    private TestPlanRepository testPlanRepository;
    private TestSuiteRepository testSuiteRepository;
    private TestSuiteService testSuiteService;
    private ProjectService projectService;
    private TestRunService service;

    private Project project;
    private TestPlan plan;
    private TestSuite suite;

    @BeforeEach
    void setUp() {
        testRunRepository = mock(TestRunRepository.class);
        testerAssignmentRepository = mock(TestRunTesterAssignmentRepository.class);
        caseResultRepository = mock(TestRunCaseResultRepository.class);
        stepResultRepository = mock(TestRunStepResultRepository.class);
        testCaseStepRepository = mock(TestCaseStepRepository.class);
        testPlanRepository = mock(TestPlanRepository.class);
        testSuiteRepository = mock(TestSuiteRepository.class);
        testSuiteService = mock(TestSuiteService.class);
        projectService = mock(ProjectService.class);

        service = new TestRunService(
                testRunRepository,
                testerAssignmentRepository,
                caseResultRepository,
                stepResultRepository,
                testCaseStepRepository,
                testPlanRepository,
                testSuiteRepository,
                testSuiteService,
                projectService);

        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        plan = new TestPlan(project, "TP-001", "Wave-1");
        setField(plan, "id", PLAN_ID);
        suite = new TestSuite(project, "TS-001", "Smoke", TestSuitePopulationMode.STATIC);
        setField(suite, "id", SUITE_ID);
    }

    private TestCase mkTestCase(UUID id, String uid, String title) {
        var tc = new TestCase(project, uid, title, TestCaseType.MANUAL, TestCasePriority.MEDIUM);
        setField(tc, "id", id);
        return tc;
    }

    private TestRun mkRun() {
        var run = new TestRun(project, plan, suite, "TR-001", "Smoke pass 1");
        setField(run, "id", RUN_ID);
        return run;
    }

    @Test
    void updateCursorRejectsStepFromDifferentCase() {
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var caseResult = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(caseResult, "id", CASE_RESULT_ID);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findById(CASE_RESULT_ID)).thenReturn(Optional.of(caseResult));
        when(stepResultRepository.findByIdAndTestRunCaseResultId(STEP_RESULT_ID, CASE_RESULT_ID))
                .thenReturn(Optional.empty());
        var cmd = new UpdateTestRunCursorCommand(CASE_RESULT_ID, STEP_RESULT_ID, false);
        assertThatThrownBy(() -> service.updateCursor(PROJECT_ID, RUN_ID, cmd)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateCursorClearFlagNullsBothFields() {
        var run = mkRun();
        run.setCurrentCaseResultId(CASE_RESULT_ID);
        run.setCurrentStepResultId(STEP_RESULT_ID);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(testRunRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.updateCursor(
                PROJECT_ID, RUN_ID, new UpdateTestRunCursorCommand(CASE_RESULT_ID, STEP_RESULT_ID, true));

        assertThat(updated.getCurrentCaseResultId()).isNull();
        assertThat(updated.getCurrentStepResultId()).isNull();
    }
}

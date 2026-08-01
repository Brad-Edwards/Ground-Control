package com.keplerops.groundcontrol.unit.domain.testcases.service;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
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
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseStepRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestPlanRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestRunCaseResultRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestRunRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestRunStepResultRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestRunTesterAssignmentRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteRepository;
import com.keplerops.groundcontrol.domain.testcases.service.CreateTestRunCommand;
import com.keplerops.groundcontrol.domain.testcases.service.TestRunService;
import com.keplerops.groundcontrol.domain.testcases.service.TestSuiteService;
import com.keplerops.groundcontrol.domain.testcases.service.UpdateTestRunCaseResultCommand;
import com.keplerops.groundcontrol.domain.testcases.service.UpdateTestRunCommand;
import com.keplerops.groundcontrol.domain.testcases.state.TestCasePriority;
import com.keplerops.groundcontrol.domain.testcases.state.TestCaseType;
import com.keplerops.groundcontrol.domain.testcases.state.TestRunCaseResultStatus;
import com.keplerops.groundcontrol.domain.testcases.state.TestRunStatus;
import com.keplerops.groundcontrol.domain.testcases.state.TestSuitePopulationMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Split from TestRunServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
class TestRunServiceTest {
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID SUITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000300");
    private static final UUID TC1_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID TC2_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
    private static final UUID CASE_RESULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000a01");
    private static final UUID STEP_RESULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000b01");

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

    private TestCaseStep mkStep(TestCase tc, int stepNumber, String action, String expected) {
        var step = new TestCaseStep(tc, stepNumber, action, expected);
        setField(step, "id", UUID.randomUUID());
        return step;
    }

    @Test
    void createSnapshotsResolvedTestCasesAsCaseResultRows() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(testRunRepository.existsByProjectIdAndUid(PROJECT_ID, "TR-001")).thenReturn(false);
        when(testPlanRepository.findByIdAndProjectId(PLAN_ID, PROJECT_ID)).thenReturn(Optional.of(plan));
        when(testSuiteRepository.findByIdAndProjectId(SUITE_ID, PROJECT_ID)).thenReturn(Optional.of(suite));
        when(testRunRepository.save(any(TestRun.class))).thenAnswer(inv -> {
            var r = inv.getArgument(0, TestRun.class);
            setField(r, "id", RUN_ID);
            return r;
        });
        var tc1 = mkTestCase(TC1_ID, "TC-001", "Login");
        var tc2 = mkTestCase(TC2_ID, "TC-002", "Logout");
        when(testSuiteService.resolveTestCases(PROJECT_ID, SUITE_ID)).thenReturn(List.of(tc1, tc2));
        when(caseResultRepository.save(any(TestRunCaseResult.class))).thenAnswer(inv -> inv.getArgument(0));
        // Cases with zero authored steps yield zero step-result rows; the
        // dedicated step-snapshot test covers the non-empty path.
        when(testCaseStepRepository.findByTestCaseIdOrderByStepNumberAsc(any(UUID.class)))
                .thenReturn(List.of());

        var run = service.create(new CreateTestRunCommand(
                PROJECT_ID, "TR-001", "Smoke pass 1", PLAN_ID, SUITE_ID, "staging", "1.2.0", "build-42", null, null));

        assertThat(run.getUid()).isEqualTo("TR-001");
        assertThat(run.getTestPlan()).isSameAs(plan);
        assertThat(run.getTestSuite()).isSameAs(suite);
        // Verify the snapshot rows carry the resolver's order + the
        // resolved case's identity. A times()-only check would miss an
        // always-zero snapshotOrder regression or a swapped uid/title.
        var captor = org.mockito.ArgumentCaptor.forClass(TestRunCaseResult.class);
        verify(caseResultRepository, times(2)).save(captor.capture());
        var saved = captor.getAllValues();
        assertThat(saved.get(0).getTestCase()).isSameAs(tc1);
        assertThat(saved.get(0).getTestCaseUid()).isEqualTo("TC-001");
        assertThat(saved.get(0).getTestCaseTitle()).isEqualTo("Login");
        assertThat(saved.get(0).getSnapshotOrder()).isZero();
        assertThat(saved.get(1).getTestCase()).isSameAs(tc2);
        assertThat(saved.get(1).getTestCaseUid()).isEqualTo("TC-002");
        assertThat(saved.get(1).getTestCaseTitle()).isEqualTo("Logout");
        assertThat(saved.get(1).getSnapshotOrder()).isEqualTo(1);
    }

    @Test
    void createRejectsDuplicateUid() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(testRunRepository.existsByProjectIdAndUid(PROJECT_ID, "TR-001")).thenReturn(true);
        var cmd = new CreateTestRunCommand(
                PROJECT_ID, "TR-001", "Smoke", PLAN_ID, SUITE_ID, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createRejectsMissingPlanInProject() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(testRunRepository.existsByProjectIdAndUid(PROJECT_ID, "TR-001")).thenReturn(false);
        when(testPlanRepository.findByIdAndProjectId(PLAN_ID, PROJECT_ID)).thenReturn(Optional.empty());
        var cmd = new CreateTestRunCommand(
                PROJECT_ID, "TR-001", "Smoke", PLAN_ID, SUITE_ID, null, null, null, null, null);
        // Cross-project plan = NotFoundException (concealment): the
        // caller never learns the plan exists in another project.
        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Test plan not found");
    }

    @Test
    void createRejectsMissingSuiteInProject() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(testRunRepository.existsByProjectIdAndUid(PROJECT_ID, "TR-001")).thenReturn(false);
        when(testPlanRepository.findByIdAndProjectId(PLAN_ID, PROJECT_ID)).thenReturn(Optional.of(plan));
        when(testSuiteRepository.findByIdAndProjectId(SUITE_ID, PROJECT_ID)).thenReturn(Optional.empty());
        var cmd = new CreateTestRunCommand(
                PROJECT_ID, "TR-001", "Smoke", PLAN_ID, SUITE_ID, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Test suite not found");
    }

    @Test
    void createRejectsNullPlanOrSuiteIdAtServiceLayer() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(testRunRepository.existsByProjectIdAndUid(PROJECT_ID, "TR-001")).thenReturn(false);
        var cmdNoPlan =
                new CreateTestRunCommand(PROJECT_ID, "TR-001", "Smoke", null, SUITE_ID, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(cmdNoPlan))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("test_plan_id");
        when(testPlanRepository.findByIdAndProjectId(PLAN_ID, PROJECT_ID)).thenReturn(Optional.of(plan));
        var cmdNoSuite =
                new CreateTestRunCommand(PROJECT_ID, "TR-001", "Smoke", PLAN_ID, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(cmdNoSuite))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("test_suite_id");
    }

    @Test
    void getByIdReturnsRunInProject() {
        var run = mkRun();
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        assertThat(service.getById(PROJECT_ID, RUN_ID)).isSameAs(run);
    }

    @Test
    void getByIdRejectsCrossProject() {
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(PROJECT_ID, RUN_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateAppliesPartialFieldsAndScheduleWindow() {
        var run = mkRun();
        run.setEnvironment("staging");
        run.setStartAt(Instant.parse("2026-06-01T00:00:00Z"));
        run.setEndAt(Instant.parse("2026-06-30T00:00:00Z"));
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(testRunRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.update(
                PROJECT_ID,
                RUN_ID,
                new UpdateTestRunCommand(
                        "Smoke pass 1 — updated",
                        "prod",
                        null,
                        null,
                        Instant.parse("2026-07-01T00:00:00Z"),
                        Instant.parse("2026-07-31T00:00:00Z"),
                        false,
                        false,
                        false,
                        false,
                        false));

        assertThat(updated.getName()).isEqualTo("Smoke pass 1 — updated");
        assertThat(updated.getEnvironment()).isEqualTo("prod");
        assertThat(updated.getStartAt()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(updated.getEndAt()).isEqualTo(Instant.parse("2026-07-31T00:00:00Z"));
    }

    @Test
    void updateScheduleAcceptsWholeWindowShift() {
        // Whole-window shift caught the TestPlan codex cycle 1 finding —
        // assert the same atomic-reconcile path holds for TestRun.
        var run = mkRun();
        run.setStartAt(Instant.parse("2026-06-01T00:00:00Z"));
        run.setEndAt(Instant.parse("2026-06-30T00:00:00Z"));
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(testRunRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.update(
                PROJECT_ID,
                RUN_ID,
                new UpdateTestRunCommand(
                        null,
                        null,
                        null,
                        null,
                        Instant.parse("2026-07-01T00:00:00Z"),
                        Instant.parse("2026-07-31T00:00:00Z"),
                        false,
                        false,
                        false,
                        false,
                        false));

        assertThat(updated.getStartAt()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(updated.getEndAt()).isEqualTo(Instant.parse("2026-07-31T00:00:00Z"));
    }

    @Test
    void updateRejectsInvertedScheduleAtomic() {
        var run = mkRun();
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        var invertedStart = Instant.parse("2026-07-31T00:00:00Z");
        var invertedEnd = Instant.parse("2026-07-01T00:00:00Z");
        var invertedUpdate = new UpdateTestRunCommand(
                null, null, null, null, invertedStart, invertedEnd, false, false, false, false, false);
        assertThatThrownBy(() -> service.update(PROJECT_ID, RUN_ID, invertedUpdate))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("end_at must be on or after start_at");
    }

    @Test
    void updateClearsFieldsWhenFlagSet() {
        var run = mkRun();
        run.setEnvironment("staging");
        run.setStartAt(Instant.parse("2026-06-01T00:00:00Z"));
        run.setEndAt(Instant.parse("2026-06-30T00:00:00Z"));
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(testRunRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.update(
                PROJECT_ID,
                RUN_ID,
                new UpdateTestRunCommand(null, null, null, null, null, null, true, false, false, true, true));

        assertThat(updated.getEnvironment()).isNull();
        assertThat(updated.getStartAt()).isNull();
        assertThat(updated.getEndAt()).isNull();
    }

    @Test
    void transitionStatusDelegatesToEntity() {
        var run = mkRun();
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(testRunRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        var updated = service.transitionStatus(PROJECT_ID, RUN_ID, TestRunStatus.IN_PROGRESS);
        assertThat(updated.getStatus()).isEqualTo(TestRunStatus.IN_PROGRESS);
    }

    @Test
    void addTesterAcceptsValidName() {
        var run = mkRun();
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(testerAssignmentRepository.existsByTestRunIdAndTesterName(RUN_ID, "Alex"))
                .thenReturn(false);
        when(testerAssignmentRepository.save(any(TestRunTesterAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var assignment = service.addTester(PROJECT_ID, RUN_ID, "Alex");
        assertThat(assignment.getTesterName()).isEqualTo("Alex");
    }

    @Test
    void addTesterRejectsBlank() {
        var run = mkRun();
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        assertThatThrownBy(() -> service.addTester(PROJECT_ID, RUN_ID, "  "))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> service.addTester(PROJECT_ID, RUN_ID, null))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void addTesterRejectsDuplicate() {
        var run = mkRun();
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(testerAssignmentRepository.existsByTestRunIdAndTesterName(RUN_ID, "Alex"))
                .thenReturn(true);
        assertThatThrownBy(() -> service.addTester(PROJECT_ID, RUN_ID, "Alex")).isInstanceOf(ConflictException.class);
    }

    @Test
    void removeTesterRejectsUnknown() {
        var run = mkRun();
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(testerAssignmentRepository.findByTestRunIdAndTesterName(RUN_ID, "Alex"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.removeTester(PROJECT_ID, RUN_ID, "Alex"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateResultWritesStatusAndNotes() {
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var existing = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findByTestRunIdAndTestCaseId(RUN_ID, TC1_ID)).thenReturn(Optional.of(existing));
        when(caseResultRepository.save(any(TestRunCaseResult.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.updateResult(
                PROJECT_ID,
                RUN_ID,
                TC1_ID,
                new UpdateTestRunCaseResultCommand(TestRunCaseResultStatus.FAILED, "Step 3 missing button", false));

        assertThat(updated.getStatus()).isEqualTo(TestRunCaseResultStatus.FAILED);
        assertThat(updated.getNotes()).isEqualTo("Step 3 missing button");
    }

    @Test
    void updateResultClearsNotesWhenFlagSet() {
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var existing = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        existing.setNotes("stale notes");
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findByTestRunIdAndTestCaseId(RUN_ID, TC1_ID)).thenReturn(Optional.of(existing));
        when(caseResultRepository.save(any(TestRunCaseResult.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.updateResult(
                PROJECT_ID,
                RUN_ID,
                TC1_ID,
                new UpdateTestRunCaseResultCommand(TestRunCaseResultStatus.PASSED, null, true));

        assertThat(updated.getStatus()).isEqualTo(TestRunCaseResultStatus.PASSED);
        assertThat(updated.getNotes()).isNull();
    }

    @Test
    void updateResultRejectsUnknownTestCase() {
        var run = mkRun();
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findByTestRunIdAndTestCaseId(RUN_ID, TC2_ID)).thenReturn(Optional.empty());
        var passedCommand = new UpdateTestRunCaseResultCommand(TestRunCaseResultStatus.PASSED, null, false);
        assertThatThrownBy(() -> service.updateResult(PROJECT_ID, RUN_ID, TC2_ID, passedCommand))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateResultPreservesStatusWhenOmitted() {
        // TC-009 codex review cycle 1, finding "Comment and notes saves can
        // revert a newer status": status is intentionally optional so a
        // notes-only autosave from the runner UI cannot stomp on a
        // concurrent status flip. Null status preserves the existing value.
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var existing = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        existing.setStatus(TestRunCaseResultStatus.PASSED);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findByTestRunIdAndTestCaseId(RUN_ID, TC1_ID)).thenReturn(Optional.of(existing));
        when(caseResultRepository.save(any(TestRunCaseResult.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.updateResult(
                PROJECT_ID, RUN_ID, TC1_ID, new UpdateTestRunCaseResultCommand(null, "notes-only", false));

        assertThat(updated.getStatus()).isEqualTo(TestRunCaseResultStatus.PASSED);
        assertThat(updated.getNotes()).isEqualTo("notes-only");
    }

    @Test
    void deleteRemovesChildrenAndRun() {
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var assignment = new TestRunTesterAssignment(run, "Alex");
        var result = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(result, "id", CASE_RESULT_ID);
        var step = mkStep(tc, 1, "Open page", "Form visible");
        var stepResult = new TestRunStepResult(result, step, 1, "Open page", "Form visible", 0);
        setField(stepResult, "id", STEP_RESULT_ID);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(testerAssignmentRepository.findByTestRunId(RUN_ID)).thenReturn(List.of(assignment));
        when(stepResultRepository.findByTestRunId(RUN_ID)).thenReturn(List.of(stepResult));
        when(caseResultRepository.findByTestRunId(RUN_ID)).thenReturn(List.of(result));

        service.delete(PROJECT_ID, RUN_ID);

        verify(testerAssignmentRepository).deleteAll(List.of(assignment));
        // Step results must be removed BEFORE their parent case results so
        // the FK constraint reads bottom-up; verify the order, not just the
        // call counts.
        var inOrder = org.mockito.Mockito.inOrder(stepResultRepository, caseResultRepository, testRunRepository);
        inOrder.verify(stepResultRepository).deleteAll(List.of(stepResult));
        inOrder.verify(caseResultRepository).deleteAll(List.of(result));
        inOrder.verify(testRunRepository).delete(run);
    }

    @Test
    void listByProjectReturnsRepoResults() {
        var run = mkRun();
        when(testRunRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(run));
        assertThat(service.listByProject(PROJECT_ID)).containsExactly(run);
    }

    @Test
    void getByUidThrowsWhenAbsent() {
        when(testRunRepository.findByProjectIdAndUid(PROJECT_ID, "missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getByUid(PROJECT_ID, "missing")).isInstanceOf(NotFoundException.class);
    }
}

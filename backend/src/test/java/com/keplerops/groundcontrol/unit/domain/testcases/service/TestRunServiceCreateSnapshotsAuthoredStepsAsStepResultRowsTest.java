package com.keplerops.groundcontrol.unit.domain.testcases.service;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.keplerops.groundcontrol.domain.testcases.service.UpdateTestRunCursorCommand;
import com.keplerops.groundcontrol.domain.testcases.service.UpdateTestRunStepResultCommand;
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
class TestRunServiceCreateSnapshotsAuthoredStepsAsStepResultRowsTest {
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

    private TestCaseStep mkStep(TestCase tc, int stepNumber, String action, String expected) {
        var step = new TestCaseStep(tc, stepNumber, action, expected);
        setField(step, "id", UUID.randomUUID());
        return step;
    }

    // ------------------------------------------------------------------
    // TC-009 / ADR-050 — step-result snapshot, list, update, and cursor.
    // ------------------------------------------------------------------

    @Test
    void createSnapshotsAuthoredStepsAsStepResultRows() {
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
        var tc1Steps = List.of(
                mkStep(tc1, 1, "Open login page", "Login form visible"),
                mkStep(tc1, 2, "Enter credentials", "Fields populated"));
        var tc2Steps = List.of(mkStep(tc2, 1, "Click logout", "Session cleared"));
        when(testCaseStepRepository.findByTestCaseIdOrderByStepNumberAsc(TC1_ID))
                .thenReturn(tc1Steps);
        when(testCaseStepRepository.findByTestCaseIdOrderByStepNumberAsc(TC2_ID))
                .thenReturn(tc2Steps);
        when(stepResultRepository.save(any(TestRunStepResult.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(new CreateTestRunCommand(
                PROJECT_ID, "TR-001", "Smoke pass 1", PLAN_ID, SUITE_ID, "staging", "1.2.0", "build-42", null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(TestRunStepResult.class);
        verify(stepResultRepository, times(3)).save(captor.capture());
        var saved = captor.getAllValues();
        // TC-001 / step 1 — authored content captured verbatim into the
        // snapshot fields. The snapshot is what the tester sees later, so a
        // string-for-string assertion is the actual contract.
        assertThat(saved.get(0).getTestCaseStep()).isSameAs(tc1Steps.get(0));
        assertThat(saved.get(0).getActionSnapshot()).isEqualTo("Open login page");
        assertThat(saved.get(0).getExpectedResultSnapshot()).isEqualTo("Login form visible");
        assertThat(saved.get(0).getStepNumberSnapshot()).isEqualTo(1);
        assertThat(saved.get(0).getSnapshotOrder()).isZero();
        assertThat(saved.get(0).getStatus()).isEqualTo(TestRunCaseResultStatus.NOT_RUN);
        // TC-001 / step 2 — same case-result parent, snapshotOrder advances.
        assertThat(saved.get(1).getStepNumberSnapshot()).isEqualTo(2);
        assertThat(saved.get(1).getSnapshotOrder()).isEqualTo(1);
        // TC-002 / step 1 — new case-result parent, snapshotOrder restarts.
        assertThat(saved.get(2).getTestCaseStep()).isSameAs(tc2Steps.get(0));
        assertThat(saved.get(2).getSnapshotOrder()).isZero();
    }

    @Test
    void createSnapshotsCaseWithZeroStepsAsZeroStepResults() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(testRunRepository.existsByProjectIdAndUid(PROJECT_ID, "TR-001")).thenReturn(false);
        when(testPlanRepository.findByIdAndProjectId(PLAN_ID, PROJECT_ID)).thenReturn(Optional.of(plan));
        when(testSuiteRepository.findByIdAndProjectId(SUITE_ID, PROJECT_ID)).thenReturn(Optional.of(suite));
        when(testRunRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        var tc = mkTestCase(TC1_ID, "TC-001", "Empty case");
        when(testSuiteService.resolveTestCases(PROJECT_ID, SUITE_ID)).thenReturn(List.of(tc));
        when(caseResultRepository.save(any(TestRunCaseResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(testCaseStepRepository.findByTestCaseIdOrderByStepNumberAsc(TC1_ID))
                .thenReturn(List.of());

        service.create(new CreateTestRunCommand(
                PROJECT_ID, "TR-001", "Smoke", PLAN_ID, SUITE_ID, null, null, null, null, null));

        verify(stepResultRepository, times(0)).save(any(TestRunStepResult.class));
    }

    @Test
    void listStepResultsRejectsCaseResultFromDifferentRun() {
        var run = mkRun();
        var otherRun = new TestRun(project, plan, suite, "TR-002", "Other");
        setField(otherRun, "id", OTHER_RUN_ID);
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var foreignCaseResult = new TestRunCaseResult(otherRun, tc, "TC-001", "Login", 0);
        setField(foreignCaseResult, "id", OTHER_CASE_RESULT_ID);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findById(OTHER_CASE_RESULT_ID)).thenReturn(Optional.of(foreignCaseResult));
        assertThatThrownBy(() -> service.listStepResults(PROJECT_ID, RUN_ID, OTHER_CASE_RESULT_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("not part of run");
    }

    @Test
    void listStepResultsReturnsRepoResults() {
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var caseResult = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(caseResult, "id", CASE_RESULT_ID);
        var step = mkStep(tc, 1, "Open", "Visible");
        var stepResult = new TestRunStepResult(caseResult, step, 1, "Open", "Visible", 0);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findById(CASE_RESULT_ID)).thenReturn(Optional.of(caseResult));
        when(stepResultRepository.findByTestRunCaseResultIdOrderBySnapshotOrder(CASE_RESULT_ID))
                .thenReturn(List.of(stepResult));
        assertThat(service.listStepResults(PROJECT_ID, RUN_ID, CASE_RESULT_ID)).containsExactly(stepResult);
    }

    @Test
    void updateStepResultWritesStatusCommentAndExecutedAt() {
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var caseResult = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(caseResult, "id", CASE_RESULT_ID);
        var step = mkStep(tc, 1, "Open", "Visible");
        var stepResult = new TestRunStepResult(caseResult, step, 1, "Open", "Visible", 0);
        setField(stepResult, "id", STEP_RESULT_ID);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findById(CASE_RESULT_ID)).thenReturn(Optional.of(caseResult));
        when(stepResultRepository.findByIdAndTestRunCaseResultId(STEP_RESULT_ID, CASE_RESULT_ID))
                .thenReturn(Optional.of(stepResult));
        when(stepResultRepository.save(any(TestRunStepResult.class))).thenAnswer(inv -> inv.getArgument(0));

        var executedAt = Instant.parse("2026-06-15T12:00:00Z");
        var updated = service.updateStepResult(
                PROJECT_ID,
                RUN_ID,
                CASE_RESULT_ID,
                STEP_RESULT_ID,
                new UpdateTestRunStepResultCommand(
                        TestRunCaseResultStatus.PASSED, "Looks good", false, executedAt, false));

        assertThat(updated.getStatus()).isEqualTo(TestRunCaseResultStatus.PASSED);
        assertThat(updated.getComment()).isEqualTo("Looks good");
        assertThat(updated.getExecutedAt()).isEqualTo(executedAt);
    }

    @Test
    void updateStepResultClearsCommentAndExecutedAtWhenFlagsSet() {
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var caseResult = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(caseResult, "id", CASE_RESULT_ID);
        var step = mkStep(tc, 1, "Open", "Visible");
        var stepResult = new TestRunStepResult(caseResult, step, 1, "Open", "Visible", 0);
        setField(stepResult, "id", STEP_RESULT_ID);
        stepResult.setComment("stale");
        stepResult.setExecutedAt(Instant.parse("2026-06-15T12:00:00Z"));
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findById(CASE_RESULT_ID)).thenReturn(Optional.of(caseResult));
        when(stepResultRepository.findByIdAndTestRunCaseResultId(STEP_RESULT_ID, CASE_RESULT_ID))
                .thenReturn(Optional.of(stepResult));
        when(stepResultRepository.save(any(TestRunStepResult.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.updateStepResult(
                PROJECT_ID,
                RUN_ID,
                CASE_RESULT_ID,
                STEP_RESULT_ID,
                new UpdateTestRunStepResultCommand(TestRunCaseResultStatus.NOT_RUN, null, true, null, true));

        assertThat(updated.getStatus()).isEqualTo(TestRunCaseResultStatus.NOT_RUN);
        assertThat(updated.getComment()).isNull();
        assertThat(updated.getExecutedAt()).isNull();
    }

    @Test
    void updateStepResultPreservesStatusWhenOmitted() {
        // TC-009 codex review cycle 1: status optional on step-result updates
        // (mirror of updateResultPreservesStatusWhenOmitted) so a comment-only
        // autosave from the runner UI cannot revert a concurrent step status
        // flip.
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var caseResult = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(caseResult, "id", CASE_RESULT_ID);
        var step = mkStep(tc, 1, "Open", "Visible");
        var stepResult = new TestRunStepResult(caseResult, step, 1, "Open", "Visible", 0);
        setField(stepResult, "id", STEP_RESULT_ID);
        stepResult.setStatus(TestRunCaseResultStatus.PASSED);
        stepResult.setExecutedAt(Instant.parse("2026-06-15T12:00:00Z"));
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findById(CASE_RESULT_ID)).thenReturn(Optional.of(caseResult));
        when(stepResultRepository.findByIdAndTestRunCaseResultId(STEP_RESULT_ID, CASE_RESULT_ID))
                .thenReturn(Optional.of(stepResult));
        when(stepResultRepository.save(any(TestRunStepResult.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.updateStepResult(
                PROJECT_ID,
                RUN_ID,
                CASE_RESULT_ID,
                STEP_RESULT_ID,
                new UpdateTestRunStepResultCommand(null, "tester added a comment", false, null, false));

        // Status and the existing executedAt timestamp are preserved.
        assertThat(updated.getStatus()).isEqualTo(TestRunCaseResultStatus.PASSED);
        assertThat(updated.getExecutedAt()).isEqualTo(Instant.parse("2026-06-15T12:00:00Z"));
        assertThat(updated.getComment()).isEqualTo("tester added a comment");
    }

    @Test
    void updateStepResultDefaultsExecutedAtWhenStatusBecomesObserved() {
        // TC-009 codex review cycle 1, finding "Step execution timestamps
        // are optional even when a step is executed": the service defaults
        // executedAt to Instant.now() when status moves to non-NOT_RUN and
        // no explicit timestamp is supplied. Every executed step must carry
        // a timestamp regardless of which client drove the update.
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var caseResult = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(caseResult, "id", CASE_RESULT_ID);
        var step = mkStep(tc, 1, "Open", "Visible");
        var stepResult = new TestRunStepResult(caseResult, step, 1, "Open", "Visible", 0);
        setField(stepResult, "id", STEP_RESULT_ID);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findById(CASE_RESULT_ID)).thenReturn(Optional.of(caseResult));
        when(stepResultRepository.findByIdAndTestRunCaseResultId(STEP_RESULT_ID, CASE_RESULT_ID))
                .thenReturn(Optional.of(stepResult));
        when(stepResultRepository.save(any(TestRunStepResult.class))).thenAnswer(inv -> inv.getArgument(0));

        var before = Instant.now();
        var updated = service.updateStepResult(
                PROJECT_ID,
                RUN_ID,
                CASE_RESULT_ID,
                STEP_RESULT_ID,
                new UpdateTestRunStepResultCommand(TestRunCaseResultStatus.PASSED, null, false, null, false));
        var after = Instant.now();

        assertThat(updated.getExecutedAt()).isNotNull();
        assertThat(updated.getExecutedAt()).isBetween(before, after);
    }

    @Test
    void updateStepResultLeavesExecutedAtNullWhenStatusRemainsNotRun() {
        // Boundary of the auto-fill: status=NOT_RUN must not generate a
        // timestamp. Comment-only updates on a NOT_RUN step should leave
        // executed_at null.
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var caseResult = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(caseResult, "id", CASE_RESULT_ID);
        var step = mkStep(tc, 1, "Open", "Visible");
        var stepResult = new TestRunStepResult(caseResult, step, 1, "Open", "Visible", 0);
        setField(stepResult, "id", STEP_RESULT_ID);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findById(CASE_RESULT_ID)).thenReturn(Optional.of(caseResult));
        when(stepResultRepository.findByIdAndTestRunCaseResultId(STEP_RESULT_ID, CASE_RESULT_ID))
                .thenReturn(Optional.of(stepResult));
        when(stepResultRepository.save(any(TestRunStepResult.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.updateStepResult(
                PROJECT_ID,
                RUN_ID,
                CASE_RESULT_ID,
                STEP_RESULT_ID,
                new UpdateTestRunStepResultCommand(
                        TestRunCaseResultStatus.NOT_RUN, "stepping back", false, null, false));

        assertThat(updated.getExecutedAt()).isNull();
    }

    @Test
    void updateResultRejectsClosedRun() {
        // TC-009 codex review cycle 1, finding "Closed test-run evidence
        // remains mutable": runs in any terminal state must reject
        // execution-evidence writes so historical evidence cannot be
        // rewritten. Cover every terminal state on the case-result path.
        for (var terminalStatus : List.of(TestRunStatus.COMPLETED, TestRunStatus.ABORTED, TestRunStatus.ARCHIVED)) {
            var run = mkRun();
            setField(run, "status", terminalStatus);
            when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
            var cmd = new UpdateTestRunCaseResultCommand(TestRunCaseResultStatus.PASSED, null, false);
            assertThatThrownBy(() -> service.updateResult(PROJECT_ID, RUN_ID, TC1_ID, cmd))
                    .as("status=" + terminalStatus)
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining(terminalStatus.name())
                    .hasMessageContaining("cannot be mutated");
        }
    }

    @Test
    void updateStepResultRejectsClosedRun() {
        var run = mkRun();
        setField(run, "status", TestRunStatus.COMPLETED);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        var cmd = new UpdateTestRunStepResultCommand(TestRunCaseResultStatus.PASSED, null, false, null, false);
        assertThatThrownBy(() -> service.updateStepResult(PROJECT_ID, RUN_ID, CASE_RESULT_ID, STEP_RESULT_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("cannot be mutated");
    }

    @Test
    void updateCursorRejectsClosedRun() {
        var run = mkRun();
        setField(run, "status", TestRunStatus.ARCHIVED);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        var cmd = new UpdateTestRunCursorCommand(null, null, true);
        assertThatThrownBy(() -> service.updateCursor(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("cannot be mutated");
    }

    @Test
    void updateStepResultRejectsUnknownStepResult() {
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var caseResult = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(caseResult, "id", CASE_RESULT_ID);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findById(CASE_RESULT_ID)).thenReturn(Optional.of(caseResult));
        when(stepResultRepository.findByIdAndTestRunCaseResultId(STEP_RESULT_ID, CASE_RESULT_ID))
                .thenReturn(Optional.empty());
        var cmd = new UpdateTestRunStepResultCommand(TestRunCaseResultStatus.PASSED, null, false, null, false);
        assertThatThrownBy(() -> service.updateStepResult(PROJECT_ID, RUN_ID, CASE_RESULT_ID, STEP_RESULT_ID, cmd))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateCursorSetsAndValidatesPair() {
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var caseResult = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(caseResult, "id", CASE_RESULT_ID);
        var step = mkStep(tc, 1, "Open", "Visible");
        var stepResult = new TestRunStepResult(caseResult, step, 1, "Open", "Visible", 0);
        setField(stepResult, "id", STEP_RESULT_ID);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findById(CASE_RESULT_ID)).thenReturn(Optional.of(caseResult));
        when(stepResultRepository.findByIdAndTestRunCaseResultId(STEP_RESULT_ID, CASE_RESULT_ID))
                .thenReturn(Optional.of(stepResult));
        when(testRunRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.updateCursor(
                PROJECT_ID, RUN_ID, new UpdateTestRunCursorCommand(CASE_RESULT_ID, STEP_RESULT_ID, false));

        assertThat(updated.getCurrentCaseResultId()).isEqualTo(CASE_RESULT_ID);
        assertThat(updated.getCurrentStepResultId()).isEqualTo(STEP_RESULT_ID);
    }

    @Test
    void updateCursorAcceptsCaseOnlyAndIgnoresMissingStep() {
        // Cursor at the case level only — tester paused while selecting which
        // step to start. The service must not require a step UUID for the
        // case-only state to be persistable.
        var run = mkRun();
        var tc = mkTestCase(TC1_ID, "TC-001", "Login");
        var caseResult = new TestRunCaseResult(run, tc, "TC-001", "Login", 0);
        setField(caseResult, "id", CASE_RESULT_ID);
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(caseResultRepository.findById(CASE_RESULT_ID)).thenReturn(Optional.of(caseResult));
        when(testRunRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));

        var updated =
                service.updateCursor(PROJECT_ID, RUN_ID, new UpdateTestRunCursorCommand(CASE_RESULT_ID, null, false));

        assertThat(updated.getCurrentCaseResultId()).isEqualTo(CASE_RESULT_ID);
        assertThat(updated.getCurrentStepResultId()).isNull();
    }

    @Test
    void updateCursorRejectsStepWithoutCase() {
        var run = mkRun();
        when(testRunRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        var cmd = new UpdateTestRunCursorCommand(null, STEP_RESULT_ID, false);
        assertThatThrownBy(() -> service.updateCursor(PROJECT_ID, RUN_ID, cmd))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("current_case_result_id");
    }
}

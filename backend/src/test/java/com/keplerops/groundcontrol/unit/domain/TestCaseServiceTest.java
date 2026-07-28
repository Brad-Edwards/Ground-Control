package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.testcases.model.TestCase;
import com.keplerops.groundcontrol.domain.testcases.model.TestCaseFolder;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseFolderRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseRepository;
import com.keplerops.groundcontrol.domain.testcases.service.CreateTestCaseCommand;
import com.keplerops.groundcontrol.domain.testcases.service.MoveTestCaseCommand;
import com.keplerops.groundcontrol.domain.testcases.service.TestCaseGherkinService;
import com.keplerops.groundcontrol.domain.testcases.service.TestCaseService;
import com.keplerops.groundcontrol.domain.testcases.service.TestCaseStepService;
import com.keplerops.groundcontrol.domain.testcases.service.UpdateTestCaseCommand;
import com.keplerops.groundcontrol.domain.testcases.state.TestCaseFormat;
import com.keplerops.groundcontrol.domain.testcases.state.TestCasePriority;
import com.keplerops.groundcontrol.domain.testcases.state.TestCaseStatus;
import com.keplerops.groundcontrol.domain.testcases.state.TestCaseType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from TestCaseServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class TestCaseServiceTest {
    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestCaseFolderRepository folderRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.testcases.repository.TestRunCaseResultRepository
            testRunCaseResultRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private TestCaseStepService testCaseStepService;

    @Mock
    private TestCaseGherkinService testCaseGherkinService;

    @InjectMocks
    private TestCaseService testCaseService;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private TestCase makeTestCase() {
        var testCase = new TestCase(project, "TC-001", "Login flow", TestCaseType.MANUAL, TestCasePriority.HIGH);
        testCase.setDescription("# Verify user can log in");
        testCase.setPreconditions("User exists in identity provider");
        testCase.setPostconditions("User redirected to dashboard");
        testCase.setEstimatedDurationSeconds(300L);
        setField(testCase, "id", UUID.randomUUID());
        return testCase;
    }

    @Nested
    class Create {

        @Test
        void createsTestCase() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(testCaseRepository.existsByProjectIdAndUid(projectId, "TC-001"))
                    .thenReturn(false);
            when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateTestCaseCommand(
                    projectId,
                    "TC-001",
                    "Login flow",
                    TestCaseType.MANUAL,
                    TestCasePriority.HIGH,
                    null,
                    "# desc",
                    "pre",
                    "post",
                    300L);
            var result = testCaseService.create(command);

            assertThat(result.getUid()).isEqualTo("TC-001");
            assertThat(result.getTitle()).isEqualTo("Login flow");
            assertThat(result.getType()).isEqualTo(TestCaseType.MANUAL);
            assertThat(result.getPriority()).isEqualTo(TestCasePriority.HIGH);
            assertThat(result.getDescription()).isEqualTo("# desc");
            assertThat(result.getPreconditions()).isEqualTo("pre");
            assertThat(result.getPostconditions()).isEqualTo("post");
            assertThat(result.getEstimatedDurationSeconds()).isEqualTo(300L);
            assertThat(result.getStatus()).isEqualTo(TestCaseStatus.DRAFT);
            assertThat(result.getFormat()).isEqualTo(TestCaseFormat.STEP_BASED);
        }

        @Test
        void createsGherkinFormatTestCaseWhenSpecified() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(testCaseRepository.existsByProjectIdAndUid(projectId, "TC-G01"))
                    .thenReturn(false);
            when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateTestCaseCommand(
                    projectId,
                    "TC-G01",
                    "Sign in",
                    TestCaseType.MANUAL,
                    TestCasePriority.HIGH,
                    TestCaseFormat.GHERKIN,
                    null,
                    null,
                    null,
                    null);
            var result = testCaseService.create(command);

            assertThat(result.getFormat()).isEqualTo(TestCaseFormat.GHERKIN);
        }

        @Test
        void throwsConflictOnDuplicateUid() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(testCaseRepository.existsByProjectIdAndUid(projectId, "TC-001"))
                    .thenReturn(true);

            var command = new CreateTestCaseCommand(
                    projectId,
                    "TC-001",
                    "Title",
                    TestCaseType.MANUAL,
                    TestCasePriority.LOW,
                    null,
                    null,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> testCaseService.create(command))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("TC-001");
        }

        @Test
        void rejectsNegativeDuration() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(testCaseRepository.existsByProjectIdAndUid(projectId, "TC-001"))
                    .thenReturn(false);

            var command = new CreateTestCaseCommand(
                    projectId,
                    "TC-001",
                    "Title",
                    TestCaseType.MANUAL,
                    TestCasePriority.LOW,
                    null,
                    null,
                    null,
                    null,
                    -5L);

            assertThatThrownBy(() -> testCaseService.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("non-negative");
        }
    }

    @Nested
    class Update {

        @Test
        void updatesProvidedFields() {
            var existing = makeTestCase();
            when(testCaseRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));
            when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateTestCaseCommand(
                    "New Title",
                    TestCaseType.AUTOMATED,
                    TestCasePriority.CRITICAL,
                    null,
                    null,
                    null,
                    600L,
                    false,
                    false,
                    false,
                    false);
            var result = testCaseService.update(projectId, existing.getId(), command);

            assertThat(result.getTitle()).isEqualTo("New Title");
            assertThat(result.getType()).isEqualTo(TestCaseType.AUTOMATED);
            assertThat(result.getPriority()).isEqualTo(TestCasePriority.CRITICAL);
            assertThat(result.getEstimatedDurationSeconds()).isEqualTo(600L);
            // Unset fields preserved — postconditions included so an accidentally
            // inverted update branch (always clearing when clearPostconditions=false)
            // would fail this test.
            assertThat(result.getDescription()).isEqualTo("# Verify user can log in");
            assertThat(result.getPreconditions()).isEqualTo("User exists in identity provider");
            assertThat(result.getPostconditions()).isEqualTo("User redirected to dashboard");
        }

        @Test
        void throwsNotFoundWhenAbsent() {
            var unknownId = UUID.randomUUID();
            when(testCaseRepository.findByIdAndProjectId(unknownId, projectId)).thenReturn(Optional.empty());

            var command =
                    new UpdateTestCaseCommand("x", null, null, null, null, null, null, false, false, false, false);
            assertThatThrownBy(() -> testCaseService.update(projectId, unknownId, command))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void clearFlagsWipeNullableFields() {
            var existing = makeTestCase();
            when(testCaseRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));
            when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));

            // All four clear flags on; corresponding value fields are null (the
            // controller forbids setting both clear=true and a non-null value).
            var command = new UpdateTestCaseCommand(null, null, null, null, null, null, null, true, true, true, true);
            var result = testCaseService.update(projectId, existing.getId(), command);

            assertThat(result.getDescription()).isNull();
            assertThat(result.getPreconditions()).isNull();
            assertThat(result.getPostconditions()).isNull();
            assertThat(result.getEstimatedDurationSeconds()).isNull();
        }

        @Test
        void clearFlagTakesPrecedenceOverValue() {
            // When a client sends both clearXxx=true and a non-null xxx value the
            // clear semantics win — matching the UpdateFindingService convention.
            var existing = makeTestCase();
            when(testCaseRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));
            when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateTestCaseCommand(
                    null, null, null, "ignored description", null, null, 1234L, true, false, false, true);
            var result = testCaseService.update(projectId, existing.getId(), command);

            assertThat(result.getDescription()).isNull();
            assertThat(result.getEstimatedDurationSeconds()).isNull();
            assertThat(result.getPreconditions()).isEqualTo("User exists in identity provider");
            assertThat(result.getPostconditions()).isEqualTo("User redirected to dashboard");
        }
    }

    @Nested
    class GetById {

        @Test
        void returnsTestCase() {
            var existing = makeTestCase();
            when(testCaseRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));

            var result = testCaseService.getById(projectId, existing.getId());
            assertThat(result).isSameAs(existing);
        }

        @Test
        void throwsWhenNotFound() {
            var unknownId = UUID.randomUUID();
            when(testCaseRepository.findByIdAndProjectId(unknownId, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> testCaseService.getById(projectId, unknownId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class GetByUid {

        @Test
        void returnsTestCase() {
            var existing = makeTestCase();
            when(testCaseRepository.findByProjectIdAndUid(projectId, "TC-001")).thenReturn(Optional.of(existing));

            var result = testCaseService.getByUid("TC-001", projectId);
            assertThat(result).isSameAs(existing);
        }

        @Test
        void throwsWhenNotFound() {
            when(testCaseRepository.findByProjectIdAndUid(projectId, "TC-999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> testCaseService.getByUid("TC-999", projectId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ListByProject {

        @Test
        void returnsRepositoryResults() {
            var existing = makeTestCase();
            when(testCaseRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(existing));

            var result = testCaseService.listByProject(projectId);
            assertThat(result).containsExactly(existing);
        }
    }

    @Nested
    class TransitionStatus {

        @Test
        void transitionsDraftToApproved() {
            var existing = makeTestCase();
            when(testCaseRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));
            when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = testCaseService.transitionStatus(projectId, existing.getId(), TestCaseStatus.APPROVED);
            assertThat(result.getStatus()).isEqualTo(TestCaseStatus.APPROVED);
        }

        @Test
        void rejectsInvalidTransition() {
            var existing = makeTestCase();
            var id = existing.getId();
            when(testCaseRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> testCaseService.transitionStatus(projectId, id, TestCaseStatus.DEPRECATED))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class Delete {

        @Test
        void deletesTestCase() {
            var existing = makeTestCase();
            when(testCaseRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));

            testCaseService.delete(projectId, existing.getId());

            verify(testCaseRepository).delete(existing);
        }

        @Test
        void deleteRejectsConflictWhenRunResultsReferenceTheCase() {
            // TC-008 / ADR-049: TestRunCaseResult rows FK to this case. The
            // existence check raises a domain-aware ConflictException before
            // step/gherkin cascades start, so a parent delete that would
            // orphan execution evidence is rejected cleanly.
            var existing = makeTestCase();
            var id = existing.getId();
            when(testCaseRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.of(existing));
            when(testRunCaseResultRepository.existsByTestCaseId(id)).thenReturn(true);

            assertThatThrownBy(() -> testCaseService.delete(projectId, id))
                    .isInstanceOf(com.keplerops.groundcontrol.domain.exception.ConflictException.class)
                    .hasMessageContaining("test-run results");
            verify(testCaseStepService, never()).deleteAllByTestCase(id);
            verify(testCaseGherkinService, never()).cascadeDeleteByTestCase(id);
            verify(testCaseRepository, never()).delete(existing);
        }

        @Test
        void cascadesToDeleteSteps() {
            // Steps and Gherkin must be deleted via their services (so Envers
            // records each child delete) BEFORE the test case row goes away.
            // A future change that replaced this with a DB-level CASCADE would
            // silently lose the per-child audit trail, so the test pins the
            // order of calls.
            var existing = makeTestCase();
            var id = existing.getId();
            when(testCaseRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.of(existing));

            testCaseService.delete(projectId, id);

            var order = org.mockito.Mockito.inOrder(testCaseStepService, testCaseGherkinService, testCaseRepository);
            order.verify(testCaseStepService).deleteAllByTestCase(id);
            order.verify(testCaseGherkinService).cascadeDeleteByTestCase(id);
            order.verify(testCaseRepository).delete(existing);
        }

        @Test
        void throwsWhenNotFound() {
            var unknownId = UUID.randomUUID();
            when(testCaseRepository.findByIdAndProjectId(unknownId, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> testCaseService.delete(projectId, unknownId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Move {

        @Test
        void movesTestCaseToFolder() {
            var existing = makeTestCase();
            var folder = new TestCaseFolder(project, null, "Folder", null, 0);
            setField(folder, "id", UUID.randomUUID());
            when(testCaseRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));
            when(folderRepository.findByIdAndProjectId(folder.getId(), projectId))
                    .thenReturn(Optional.of(folder));
            when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));

            var result =
                    testCaseService.move(projectId, existing.getId(), new MoveTestCaseCommand(folder.getId(), null));

            assertThat(result.getParentFolder()).isSameAs(folder);
            // Target folder empty (default mock) → moved test case appends at pos=0.
            assertThat(result.getSortOrder()).isZero();
        }

        @Test
        void movesTestCaseToRoot() {
            var existing = makeTestCase();
            var folder = new TestCaseFolder(project, null, "Folder", null, 0);
            setField(folder, "id", UUID.randomUUID());
            existing.setParentFolder(folder);
            existing.setSortOrder(2);
            when(testCaseRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));
            when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = testCaseService.move(projectId, existing.getId(), new MoveTestCaseCommand(null, null));

            assertThat(result.getParentFolder()).isNull();
            assertThat(result.getSortOrder()).isZero();
        }

        @Test
        void rejectsMoveToFolderInOtherProject() {
            var existing = makeTestCase();
            UUID unknownFolder = UUID.randomUUID();
            when(testCaseRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));
            when(folderRepository.findByIdAndProjectId(unknownFolder, projectId))
                    .thenReturn(Optional.empty());

            var command = new MoveTestCaseCommand(unknownFolder, null);
            UUID existingId = existing.getId();
            assertThatThrownBy(() -> testCaseService.move(projectId, existingId, command))
                    .isInstanceOf(NotFoundException.class);
        }
    }
}

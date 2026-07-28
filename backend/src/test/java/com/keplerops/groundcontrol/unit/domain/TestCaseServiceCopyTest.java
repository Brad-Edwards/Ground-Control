package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.testcases.model.TestCase;
import com.keplerops.groundcontrol.domain.testcases.model.TestCaseFolder;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseFolderRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseRepository;
import com.keplerops.groundcontrol.domain.testcases.service.CopyTestCaseCommand;
import com.keplerops.groundcontrol.domain.testcases.service.ReorderTestCasesCommand;
import com.keplerops.groundcontrol.domain.testcases.service.TestCaseGherkinService;
import com.keplerops.groundcontrol.domain.testcases.service.TestCaseService;
import com.keplerops.groundcontrol.domain.testcases.service.TestCaseStepService;
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
class TestCaseServiceCopyTest {
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
    class Copy {

        @Test
        void copyCreatesNewTestCaseWithProvidedUid() {
            var source = makeTestCase();
            when(testCaseRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));
            when(testCaseRepository.existsByProjectIdAndUid(projectId, "TC-002"))
                    .thenReturn(false);
            when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = testCaseService.copy(projectId, source.getId(), new CopyTestCaseCommand("TC-002", null, null));

            assertThat(result.getUid()).isEqualTo("TC-002");
            assertThat(result.getTitle()).isEqualTo(source.getTitle());
            assertThat(result.getStatus()).isEqualTo(TestCaseStatus.DRAFT);
            assertThat(result.getDescription()).isEqualTo(source.getDescription());
            verify(testCaseStepService).copyStepsToTestCase(eq(source.getId()), any(TestCase.class));
            verify(testCaseGherkinService).copyGherkinToTestCase(eq(source.getId()), any(TestCase.class));
        }

        @Test
        void copyRejectsExistingUid() {
            var source = makeTestCase();
            when(testCaseRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));
            when(testCaseRepository.existsByProjectIdAndUid(projectId, "TC-002"))
                    .thenReturn(true);

            var command = new CopyTestCaseCommand("TC-002", null, null);
            UUID sourceId = source.getId();
            assertThatThrownBy(() -> testCaseService.copy(projectId, sourceId, command))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("TC-002");
        }

        @Test
        void copyRejectsBlankUid() {
            var source = makeTestCase();
            when(testCaseRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));

            var command = new CopyTestCaseCommand("  ", null, null);
            UUID sourceId = source.getId();
            assertThatThrownBy(() -> testCaseService.copy(projectId, sourceId, command))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void copyPlacesIntoSpecifiedFolder() {
            var source = makeTestCase();
            var folder = new TestCaseFolder(project, null, "Folder", null, 0);
            setField(folder, "id", UUID.randomUUID());
            when(testCaseRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));
            when(testCaseRepository.existsByProjectIdAndUid(projectId, "TC-002"))
                    .thenReturn(false);
            when(folderRepository.findByIdAndProjectId(folder.getId(), projectId))
                    .thenReturn(Optional.of(folder));
            when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = testCaseService.copy(
                    projectId, source.getId(), new CopyTestCaseCommand("TC-002", folder.getId(), null));

            assertThat(result.getParentFolder()).isSameAs(folder);
            // Target folder empty (default mock); copy appends at pos=0.
            assertThat(result.getSortOrder()).isZero();
        }
    }

    @Nested
    class Reorder {

        private TestCase tcWithId(String uid) {
            var tc = new TestCase(project, uid, "t", TestCaseType.MANUAL, TestCasePriority.LOW);
            setField(tc, "id", UUID.randomUUID());
            return tc;
        }

        @Test
        void renumbersRootTestCasesInRequestedOrder() {
            // Reorder lives in TestCaseService now (codex cycle-2 finding —
            // aggregate-boundary fix; SiblingOrderingHelper carries the
            // shared algorithm).
            var a = tcWithId("TC-A");
            var b = tcWithId("TC-B");
            var c = tcWithId("TC-C");
            when(testCaseRepository.findRootByProjectIdOrderBySortOrder(projectId))
                    .thenReturn(List.of(a, b, c));
            when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));

            testCaseService.reorder(
                    projectId, new ReorderTestCasesCommand(null, List.of(c.getId(), a.getId(), b.getId())));

            assertThat(c.getSortOrder()).isZero();
            assertThat(a.getSortOrder()).isEqualTo(1);
            assertThat(b.getSortOrder()).isEqualTo(2);
        }

        @Test
        void renumbersFolderContainerInRequestedOrder() {
            UUID folderId = UUID.randomUUID();
            var folder = new TestCaseFolder(project, null, "f", null, 0);
            setField(folder, "id", folderId);
            var a = tcWithId("TC-A");
            var b = tcWithId("TC-B");
            when(folderRepository.findByIdAndProjectId(folderId, projectId)).thenReturn(Optional.of(folder));
            when(testCaseRepository.findByProjectIdAndParentFolderIdOrderBySortOrder(projectId, folderId))
                    .thenReturn(List.of(a, b));
            when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));

            testCaseService.reorder(projectId, new ReorderTestCasesCommand(folderId, List.of(b.getId(), a.getId())));

            assertThat(b.getSortOrder()).isZero();
            assertThat(a.getSortOrder()).isEqualTo(1);
        }

        @Test
        void verifiesFolderInProjectWhenSpecified() {
            UUID unknownFolder = UUID.randomUUID();
            when(folderRepository.findByIdAndProjectId(unknownFolder, projectId))
                    .thenReturn(Optional.empty());

            var command = new ReorderTestCasesCommand(unknownFolder, List.of());
            assertThatThrownBy(() -> testCaseService.reorder(projectId, command))
                    .isInstanceOf(NotFoundException.class);
        }
    }
}

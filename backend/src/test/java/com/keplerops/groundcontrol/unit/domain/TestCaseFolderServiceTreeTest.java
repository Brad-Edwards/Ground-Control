package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.testcases.model.TestCase;
import com.keplerops.groundcontrol.domain.testcases.model.TestCaseFolder;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseFolderRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseRepository;
import com.keplerops.groundcontrol.domain.testcases.service.TestCaseFolderService;
import com.keplerops.groundcontrol.domain.testcases.service.TestCaseTreeNode;
import com.keplerops.groundcontrol.domain.testcases.state.TestCasePriority;
import com.keplerops.groundcontrol.domain.testcases.state.TestCaseType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from TestCaseFolderServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class TestCaseFolderServiceTreeTest {
    @Mock
    private TestCaseFolderRepository folderRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private TestCaseFolderService folderService;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private TestCaseFolder folder(String title, TestCaseFolder parent, int sortOrder) {
        var folder = new TestCaseFolder(project, parent, title, null, sortOrder);
        setField(folder, "id", UUID.randomUUID());
        // createdAt populated so the (sortOrder, createdAt, id) tie-breaker
        // comparator in TestCaseFolderService.getTree has a non-null value
        // to compare; without it the in-memory sort would NPE on tied rows.
        setField(folder, "createdAt", java.time.Instant.now());
        return folder;
    }

    private TestCase testCase(String uid, TestCaseFolder parent, int sortOrder) {
        var testCase = new TestCase(project, uid, "title", TestCaseType.MANUAL, TestCasePriority.LOW);
        testCase.setParentFolder(parent);
        testCase.setSortOrder(sortOrder);
        setField(testCase, "id", UUID.randomUUID());
        setField(testCase, "createdAt", java.time.Instant.now());
        return testCase;
    }

    @Nested
    class Tree {

        @Test
        void emptyProjectReturnsEmptyTree() {
            when(folderRepository.findByProjectIdOrderBySortOrder(projectId)).thenReturn(List.of());
            when(testCaseRepository.findAllByProjectIdOrderBySortOrder(projectId))
                    .thenReturn(List.of());

            assertThat(folderService.getTree(projectId)).isEmpty();
        }

        @Test
        void deeplyNestedTreeAssemblesIterativelyWithoutStackOverflow() {
            // Codex cycle-1 class finding: TC-005 promises unlimited
            // nesting. A 5000-folder linear chain would blow the JVM
            // stack under recursive descent; the iterative builder must
            // handle it without StackOverflowError.
            List<TestCaseFolder> chain = new ArrayList<>();
            TestCaseFolder parent = null;
            for (int i = 0; i < 5000; i++) {
                var node = new TestCaseFolder(project, parent, "F" + i, null, 0);
                setField(node, "id", UUID.randomUUID());
                setField(node, "createdAt", java.time.Instant.now());
                chain.add(node);
                parent = node;
            }
            when(folderRepository.findByProjectIdOrderBySortOrder(projectId)).thenReturn(chain);
            when(testCaseRepository.findAllByProjectIdOrderBySortOrder(projectId))
                    .thenReturn(List.of());

            var tree = folderService.getTree(projectId);

            assertThat(tree).hasSize(1);
            // Walk down to confirm full depth is materialised.
            var cursor = tree.get(0);
            int depth = 0;
            while (!cursor.children().isEmpty()) {
                cursor = cursor.children().get(0);
                depth++;
            }
            assertThat(depth).isEqualTo(4999);
        }

        @Test
        void treeReturnsFoldersAndTestCasesInDeterministicOrder() {
            var rootA = folder("RootA", null, 0);
            var rootB = folder("RootB", null, 1);
            var childOfA = folder("ChildA", rootA, 0);
            var caseInA = testCase("TC-1", rootA, 0);
            var rootCase = testCase("TC-2", null, 0);
            when(folderRepository.findByProjectIdOrderBySortOrder(projectId))
                    .thenReturn(List.of(rootA, rootB, childOfA));
            when(testCaseRepository.findAllByProjectIdOrderBySortOrder(projectId))
                    .thenReturn(List.of(caseInA, rootCase));

            var tree = folderService.getTree(projectId);
            assertThat(tree).hasSize(3);
            assertThat(tree.get(0).kind()).isEqualTo(TestCaseTreeNode.Kind.FOLDER);
            assertThat(tree.get(0).id()).isEqualTo(rootA.getId());
            assertThat(tree.get(0).children()).hasSize(2);
            assertThat(tree.get(0).children().get(0).kind()).isEqualTo(TestCaseTreeNode.Kind.FOLDER);
            assertThat(tree.get(0).children().get(0).id()).isEqualTo(childOfA.getId());
            assertThat(tree.get(0).children().get(1).kind()).isEqualTo(TestCaseTreeNode.Kind.TEST_CASE);
            assertThat(tree.get(0).children().get(1).id()).isEqualTo(caseInA.getId());

            assertThat(tree.get(1).kind()).isEqualTo(TestCaseTreeNode.Kind.FOLDER);
            assertThat(tree.get(1).id()).isEqualTo(rootB.getId());

            assertThat(tree.get(2).kind()).isEqualTo(TestCaseTreeNode.Kind.TEST_CASE);
            assertThat(tree.get(2).id()).isEqualTo(rootCase.getId());
        }
    }
}

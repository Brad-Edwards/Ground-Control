package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.testcases.model.TestCaseFolder;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseFolderRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseRepository;
import com.keplerops.groundcontrol.domain.testcases.service.CreateTestCaseFolderCommand;
import com.keplerops.groundcontrol.domain.testcases.service.MoveTestCaseFolderCommand;
import com.keplerops.groundcontrol.domain.testcases.service.ReorderTestCaseFoldersCommand;
import com.keplerops.groundcontrol.domain.testcases.service.TestCaseFolderService;
import com.keplerops.groundcontrol.domain.testcases.service.UpdateTestCaseFolderCommand;
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

/** Split from TestCaseFolderServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class TestCaseFolderServiceTest {
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

    @Nested
    class Create {

        @Test
        void createsRootFolder() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(folderRepository.existsRootByProjectIdAndTitle(projectId, "Smoke"))
                    .thenReturn(false);
            when(folderRepository.save(any(TestCaseFolder.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = folderService.create(new CreateTestCaseFolderCommand(projectId, null, "Smoke", "desc", null));

            assertThat(result.getTitle()).isEqualTo("Smoke");
            assertThat(result.getParent()).isNull();
            // Root container empty (default mock) → first folder appends at pos=0.
            assertThat(result.getSortOrder()).isZero();
        }

        @Test
        void createsChildFolderAppendsAtEndOfContainer() {
            var parent = folder("Suite", null, 0);
            var existingA = folder("A", parent, 0);
            var existingB = folder("B", parent, 1);
            var existingC = folder("C", parent, 2);
            when(projectService.getById(projectId)).thenReturn(project);
            when(folderRepository.findByIdAndProjectId(parent.getId(), projectId))
                    .thenReturn(Optional.of(parent));
            when(folderRepository.existsByProjectIdAndParentIdAndTitle(projectId, parent.getId(), "Login"))
                    .thenReturn(false);
            when(folderRepository.findByProjectIdAndParentIdOrderBySortOrder(projectId, parent.getId()))
                    .thenReturn(List.of(existingA, existingB, existingC));
            when(folderRepository.save(any(TestCaseFolder.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = folderService.create(
                    new CreateTestCaseFolderCommand(projectId, parent.getId(), "Login", null, null));

            // Insertion semantics with null requested → appended at end.
            assertThat(result.getParent()).isSameAs(parent);
            assertThat(result.getSortOrder()).isEqualTo(3);
            // Existing siblings keep their positions (0, 1, 2).
            assertThat(existingA.getSortOrder()).isZero();
            assertThat(existingB.getSortOrder()).isEqualTo(1);
            assertThat(existingC.getSortOrder()).isEqualTo(2);
        }

        @Test
        void createInsertsAtRequestedPositionAndShiftsSiblings() {
            // Codex cycle-2 finding: explicit sortOrder must shift target
            // siblings so the new folder lands at the requested position
            // and ties are not possible.
            var parent = folder("Suite", null, 0);
            var existingA = folder("A", parent, 0);
            var existingB = folder("B", parent, 1);
            when(projectService.getById(projectId)).thenReturn(project);
            when(folderRepository.findByIdAndProjectId(parent.getId(), projectId))
                    .thenReturn(Optional.of(parent));
            when(folderRepository.existsByProjectIdAndParentIdAndTitle(projectId, parent.getId(), "Mid"))
                    .thenReturn(false);
            when(folderRepository.findByProjectIdAndParentIdOrderBySortOrder(projectId, parent.getId()))
                    .thenReturn(List.of(existingA, existingB));
            when(folderRepository.save(any(TestCaseFolder.class))).thenAnswer(inv -> inv.getArgument(0));

            var result =
                    folderService.create(new CreateTestCaseFolderCommand(projectId, parent.getId(), "Mid", null, 1));

            assertThat(result.getSortOrder()).isEqualTo(1);
            // A stays at 0, B shifts from 1 → 2.
            assertThat(existingA.getSortOrder()).isZero();
            assertThat(existingB.getSortOrder()).isEqualTo(2);
        }

        @Test
        void rejectsDuplicateTitleAtRoot() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(folderRepository.existsRootByProjectIdAndTitle(projectId, "Smoke"))
                    .thenReturn(true);

            var command = new CreateTestCaseFolderCommand(projectId, null, "Smoke", null, null);
            assertThatThrownBy(() -> folderService.create(command))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void rejectsParentInDifferentProject() {
            when(projectService.getById(projectId)).thenReturn(project);
            UUID unknownParent = UUID.randomUUID();
            when(folderRepository.findByIdAndProjectId(unknownParent, projectId))
                    .thenReturn(Optional.empty());

            var command = new CreateTestCaseFolderCommand(projectId, unknownParent, "Smoke", null, null);
            assertThatThrownBy(() -> folderService.create(command)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Update {

        @Test
        void updatesTitleAndDescription() {
            var existing = folder("Old", null, 0);
            when(folderRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));
            when(folderRepository.existsRootByProjectIdAndTitle(projectId, "New"))
                    .thenReturn(false);
            when(folderRepository.save(any(TestCaseFolder.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = folderService.update(
                    projectId, existing.getId(), new UpdateTestCaseFolderCommand("New", "set", false));

            assertThat(result.getTitle()).isEqualTo("New");
            assertThat(result.getDescription()).isEqualTo("set");
        }

        @Test
        void rejectsRenameOntoExistingSiblingTitle() {
            var existing = folder("Old", null, 0);
            when(folderRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));
            when(folderRepository.existsRootByProjectIdAndTitle(projectId, "Taken"))
                    .thenReturn(true);

            var command = new UpdateTestCaseFolderCommand("Taken", null, false);
            UUID existingId = existing.getId();
            assertThatThrownBy(() -> folderService.update(projectId, existingId, command))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void clearsDescription() {
            var existing = folder("Old", null, 0);
            existing.setDescription("had");
            when(folderRepository.findByIdAndProjectId(existing.getId(), projectId))
                    .thenReturn(Optional.of(existing));
            when(folderRepository.save(any(TestCaseFolder.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = folderService.update(
                    projectId, existing.getId(), new UpdateTestCaseFolderCommand(null, null, true));

            assertThat(result.getDescription()).isNull();
        }
    }

    @Nested
    class Move {

        @Test
        void movesFolderToNewParent() {
            var source = folder("Login", null, 0);
            var target = folder("New parent", null, 0);
            when(folderRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));
            when(folderRepository.findByIdAndProjectId(target.getId(), projectId))
                    .thenReturn(Optional.of(target));
            when(folderRepository.findByProjectIdAndParentIdOrderBySortOrder(projectId, target.getId()))
                    .thenReturn(List.of());
            when(folderRepository.save(any(TestCaseFolder.class))).thenAnswer(inv -> inv.getArgument(0));

            var result =
                    folderService.move(projectId, source.getId(), new MoveTestCaseFolderCommand(target.getId(), null));

            assertThat(result.getParent()).isSameAs(target);
            // Empty target container → moved folder appends at pos=0.
            assertThat(result.getSortOrder()).isZero();
        }

        @Test
        void movesFolderToRoot() {
            var oldParent = folder("Old parent", null, 0);
            var source = folder("Login", oldParent, 0);
            when(folderRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));
            when(folderRepository.findRootByProjectIdOrderBySortOrder(projectId))
                    .thenReturn(List.of());
            when(folderRepository.save(any(TestCaseFolder.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = folderService.move(projectId, source.getId(), new MoveTestCaseFolderCommand(null, null));

            assertThat(result.getParent()).isNull();
            assertThat(result.getSortOrder()).isZero();
        }

        @Test
        void rejectsMoveUnderSelf() {
            var source = folder("Login", null, 0);
            UUID sourceId = source.getId();
            when(folderRepository.findByIdAndProjectId(sourceId, projectId)).thenReturn(Optional.of(source));

            var command = new MoveTestCaseFolderCommand(sourceId, null);
            assertThatThrownBy(() -> folderService.move(projectId, sourceId, command))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("itself");
        }

        @Test
        void rejectsMoveUnderDescendant() {
            var root = folder("Root", null, 0);
            var middle = folder("Middle", root, 0);
            var leaf = folder("Leaf", middle, 0);
            UUID rootId = root.getId();
            UUID leafId = leaf.getId();
            when(folderRepository.findByIdAndProjectId(rootId, projectId)).thenReturn(Optional.of(root));
            when(folderRepository.findByIdAndProjectId(leafId, projectId)).thenReturn(Optional.of(leaf));

            // Move root under leaf — cycle: leaf -> middle -> root.
            var command = new MoveTestCaseFolderCommand(leafId, null);
            assertThatThrownBy(() -> folderService.move(projectId, rootId, command))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("descendants");
        }

        @Test
        void rejectsMoveToUnknownFolder() {
            var source = folder("Login", null, 0);
            UUID sourceId = source.getId();
            UUID unknown = UUID.randomUUID();
            when(folderRepository.findByIdAndProjectId(sourceId, projectId)).thenReturn(Optional.of(source));
            when(folderRepository.findByIdAndProjectId(unknown, projectId)).thenReturn(Optional.empty());

            var command = new MoveTestCaseFolderCommand(unknown, null);
            assertThatThrownBy(() -> folderService.move(projectId, sourceId, command))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void rejectsMoveCausingDuplicateTitleAtDestination() {
            var source = folder("Login", null, 0);
            var dest = folder("Dest", null, 1);
            var existingChild = folder("Login", dest, 0); // same title under target
            UUID sourceId = source.getId();
            UUID destId = dest.getId();
            when(folderRepository.findByIdAndProjectId(sourceId, projectId)).thenReturn(Optional.of(source));
            when(folderRepository.findByIdAndProjectId(destId, projectId)).thenReturn(Optional.of(dest));
            when(folderRepository.findByProjectIdAndParentIdOrderBySortOrder(projectId, destId))
                    .thenReturn(List.of(existingChild));

            var command = new MoveTestCaseFolderCommand(destId, null);
            assertThatThrownBy(() -> folderService.move(projectId, sourceId, command))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already exists");
        }
    }

    @Nested
    class SameContainerMoveNoOp {

        @Test
        void omittedSortOrderPreservesCurrentPlacement() {
            var parent = folder("Parent", null, 0);
            var source = folder("Child", parent, 5);
            when(folderRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));
            when(folderRepository.findByIdAndProjectId(parent.getId(), projectId))
                    .thenReturn(Optional.of(parent));
            when(folderRepository.save(any(TestCaseFolder.class))).thenAnswer(inv -> inv.getArgument(0));

            var result =
                    folderService.move(projectId, source.getId(), new MoveTestCaseFolderCommand(parent.getId(), null));

            // Same container + omitted sortOrder → no rebalancing.
            assertThat(result.getSortOrder()).isEqualTo(5);
        }

        @Test
        void explicitSortOrderShiftsSiblingsInSameContainer() {
            // Codex cycle-2 insertion semantics: moving Child from pos 2 to
            // pos 0 in [A, B, Child] must result in [Child, A, B] at [0,1,2].
            var parent = folder("Parent", null, 0);
            var a = folder("A", parent, 0);
            var b = folder("B", parent, 1);
            var source = folder("Child", parent, 2);
            when(folderRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));
            when(folderRepository.findByIdAndProjectId(parent.getId(), projectId))
                    .thenReturn(Optional.of(parent));
            when(folderRepository.findByProjectIdAndParentIdOrderBySortOrder(projectId, parent.getId()))
                    .thenReturn(List.of(a, b, source));
            when(folderRepository.save(any(TestCaseFolder.class))).thenAnswer(inv -> inv.getArgument(0));

            var result =
                    folderService.move(projectId, source.getId(), new MoveTestCaseFolderCommand(parent.getId(), 0));

            assertThat(result.getSortOrder()).isZero();
            assertThat(a.getSortOrder()).isEqualTo(1);
            assertThat(b.getSortOrder()).isEqualTo(2);
        }
    }

    @Nested
    class Reorder {

        @Test
        void renumbersSiblingsInRequestedOrder() {
            var a = folder("A", null, 0);
            var b = folder("B", null, 1);
            var c = folder("C", null, 2);
            when(folderRepository.findRootByProjectIdOrderBySortOrder(projectId))
                    .thenReturn(List.of(a, b, c));
            when(folderRepository.save(any(TestCaseFolder.class))).thenAnswer(inv -> inv.getArgument(0));

            folderService.reorder(
                    projectId, new ReorderTestCaseFoldersCommand(null, List.of(c.getId(), a.getId(), b.getId())));

            assertThat(c.getSortOrder()).isZero();
            assertThat(a.getSortOrder()).isEqualTo(1);
            assertThat(b.getSortOrder()).isEqualTo(2);
            verify(folderRepository, atLeastOnce()).save(any(TestCaseFolder.class));
        }

        @Test
        void rejectsListThatOmitsASibling() {
            var a = folder("A", null, 0);
            var b = folder("B", null, 1);
            when(folderRepository.findRootByProjectIdOrderBySortOrder(projectId))
                    .thenReturn(List.of(a, b));

            var command = new ReorderTestCaseFoldersCommand(null, List.of(a.getId()));
            assertThatThrownBy(() -> folderService.reorder(projectId, command)).isInstanceOf(ConflictException.class);
        }

        @Test
        void rejectsListWithDuplicateIds() {
            var a = folder("A", null, 0);
            var b = folder("B", null, 1);
            when(folderRepository.findRootByProjectIdOrderBySortOrder(projectId))
                    .thenReturn(List.of(a, b));

            var command = new ReorderTestCaseFoldersCommand(null, List.of(a.getId(), a.getId()));
            assertThatThrownBy(() -> folderService.reorder(projectId, command))
                    .isInstanceOf(com.keplerops.groundcontrol.domain.exception.DomainValidationException.class);
        }

        @Test
        void rejectsCrossProjectOrMissingParent() {
            // When parentFolderId is supplied, the target must exist in
            // the requesting project — otherwise reorder would silently
            // return 204 against an empty sibling set.
            UUID unknown = UUID.randomUUID();
            when(folderRepository.findByIdAndProjectId(unknown, projectId)).thenReturn(Optional.empty());

            var command = new ReorderTestCaseFoldersCommand(unknown, List.of());
            assertThatThrownBy(() -> folderService.reorder(projectId, command)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Delete {

        @Test
        void deletesEmptyFolder() {
            var f = folder("Empty", null, 0);
            when(folderRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(folderRepository.countByParentId(f.getId())).thenReturn(0L);
            when(testCaseRepository.countByParentFolderId(f.getId())).thenReturn(0L);

            folderService.delete(projectId, f.getId());

            verify(folderRepository).delete(f);
        }

        @Test
        void rejectsDeletionWithSubfolder() {
            var f = folder("Parent", null, 0);
            UUID fid = f.getId();
            when(folderRepository.findByIdAndProjectId(fid, projectId)).thenReturn(Optional.of(f));
            when(folderRepository.countByParentId(fid)).thenReturn(1L);

            assertThatThrownBy(() -> folderService.delete(projectId, fid))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("subfolders");
            verify(folderRepository, never()).delete(any(TestCaseFolder.class));
        }

        @Test
        void rejectsDeletionWithTestCases() {
            var f = folder("Bucket", null, 0);
            UUID fid = f.getId();
            when(folderRepository.findByIdAndProjectId(fid, projectId)).thenReturn(Optional.of(f));
            when(folderRepository.countByParentId(fid)).thenReturn(0L);
            when(testCaseRepository.countByParentFolderId(fid)).thenReturn(3L);

            assertThatThrownBy(() -> folderService.delete(projectId, fid))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("test cases");
        }
    }
}

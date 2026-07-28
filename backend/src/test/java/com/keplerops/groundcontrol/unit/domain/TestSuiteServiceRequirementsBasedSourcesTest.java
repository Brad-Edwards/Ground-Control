package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.testcases.model.TestCase;
import com.keplerops.groundcontrol.domain.testcases.model.TestCaseFolder;
import com.keplerops.groundcontrol.domain.testcases.model.TestSuite;
import com.keplerops.groundcontrol.domain.testcases.model.TestSuiteMember;
import com.keplerops.groundcontrol.domain.testcases.model.TestSuiteSourceRequirement;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseFolderRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteMemberRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteSourceRequirementRepository;
import com.keplerops.groundcontrol.domain.testcases.service.AddTestSuiteMemberCommand;
import com.keplerops.groundcontrol.domain.testcases.service.TestSuiteService;
import com.keplerops.groundcontrol.domain.testcases.state.TestCasePriority;
import com.keplerops.groundcontrol.domain.testcases.state.TestCaseStatus;
import com.keplerops.groundcontrol.domain.testcases.state.TestCaseType;
import com.keplerops.groundcontrol.domain.testcases.state.TestSuitePopulationMode;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/** Split from TestSuiteServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class TestSuiteServiceRequirementsBasedSourcesTest {
    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private TestSuiteMemberRepository memberRepository;

    @Mock
    private TestSuiteSourceRequirementRepository sourceRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestCaseFolderRepository folderRepository;

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.testcases.repository.TestRunRepository testRunRepository;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private TestSuiteService testSuiteService;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private TestSuite suite(String uid, TestSuitePopulationMode mode) {
        var s = new TestSuite(project, uid, "name " + uid, mode);
        setField(s, "id", UUID.randomUUID());
        return s;
    }

    private TestCase testCase(String uid) {
        var tc = new TestCase(project, uid, "title " + uid, TestCaseType.MANUAL, TestCasePriority.MEDIUM);
        setField(tc, "id", UUID.randomUUID());
        return tc;
    }

    private Requirement requirement(String uid) {
        var req = new Requirement(project, uid, "title " + uid, "statement " + uid);
        setField(req, "id", UUID.randomUUID());
        return req;
    }

    @Nested
    class RequirementsBasedSources {

        @Test
        void rejectsAddSourceOnNonRequirementsBasedSuite() {
            var s = suite("TS-S-001", TestSuitePopulationMode.STATIC);
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            UUID suiteId = s.getId();
            UUID reqId = UUID.randomUUID();

            assertThatThrownBy(() -> testSuiteService.addSourceRequirement(projectId, suiteId, reqId))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("REQUIREMENTS_BASED");
        }

        @Test
        void addSourceCreatesSourceRow() {
            var s = suite("TS-R-001", TestSuitePopulationMode.REQUIREMENTS_BASED);
            var req = requirement("REQ-001");
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            when(requirementRepository.findByIdAndProjectId(req.getId(), projectId))
                    .thenReturn(Optional.of(req));
            when(sourceRepository.existsByTestSuiteIdAndRequirementId(s.getId(), req.getId()))
                    .thenReturn(false);
            when(sourceRepository.save(any(TestSuiteSourceRequirement.class))).thenAnswer(inv -> inv.getArgument(0));

            var source = testSuiteService.addSourceRequirement(projectId, s.getId(), req.getId());

            assertThat(source.getRequirement()).isSameAs(req);
        }

        @Test
        void addSourceRejectsRequirementFromAnotherProject() {
            var s = suite("TS-R-001", TestSuitePopulationMode.REQUIREMENTS_BASED);
            var foreignReqId = UUID.randomUUID();
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            when(requirementRepository.findByIdAndProjectId(foreignReqId, projectId))
                    .thenReturn(Optional.empty());
            UUID suiteId = s.getId();

            assertThatThrownBy(() -> testSuiteService.addSourceRequirement(projectId, suiteId, foreignReqId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class StaticResolve {

        @Test
        void returnsMembersInPositionOrder() {
            var s = suite("TS-S-001", TestSuitePopulationMode.STATIC);
            var tcA = testCase("TC-A");
            var tcB = testCase("TC-B");
            var mA = new TestSuiteMember(s, tcA, 0);
            var mB = new TestSuiteMember(s, tcB, 1);
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            // F3 fix: resolve uses the pageable variant so the cap reaches the DB.
            when(memberRepository.findByTestSuiteIdOrderByPosition(eq(s.getId()), any(Pageable.class)))
                    .thenReturn(List.of(mA, mB));

            var resolved = testSuiteService.resolveTestCases(projectId, s.getId());

            assertThat(resolved).containsExactly(tcA, tcB);
        }
    }

    @Nested
    class RequirementsBasedResolve {

        @Test
        void resolvesAcrossSourceRequirementsViaTraceability() {
            var s = suite("TS-R-001", TestSuitePopulationMode.REQUIREMENTS_BASED);
            var req1 = requirement("REQ-001");
            var req2 = requirement("REQ-002");
            var src1 = new TestSuiteSourceRequirement(s, req1);
            var src2 = new TestSuiteSourceRequirement(s, req2);
            var tcA = testCase("TC-A");
            var tcB = testCase("TC-B");

            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            when(sourceRepository.findByTestSuiteIdOrderByRequirementUid(s.getId()))
                    .thenReturn(List.of(src1, src2));
            // Codex pre-push cycle 3: REQUIREMENTS_BASED resolve dispatches
            // through a single filter+join+sort+cap repo query whose result
            // is already the live, project-scoped, UID-sorted set.
            when(testCaseRepository.findLinkedTestCasesForRequirements(
                            eq(projectId),
                            eq(List.of(req1.getId(), req2.getId())),
                            eq(LinkType.TESTS),
                            eq(ArtifactType.TEST),
                            any(Pageable.class)))
                    .thenReturn(List.of(tcA, tcB));

            var resolved = testSuiteService.resolveTestCases(projectId, s.getId());

            assertThat(resolved).containsExactlyInAnyOrder(tcA, tcB);
        }

        @Test
        void returnsEmptyWhenNoSources() {
            var s = suite("TS-R-002", TestSuitePopulationMode.REQUIREMENTS_BASED);
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            when(sourceRepository.findByTestSuiteIdOrderByRequirementUid(s.getId()))
                    .thenReturn(List.of());

            assertThat(testSuiteService.resolveTestCases(projectId, s.getId())).isEmpty();
        }
    }

    @Nested
    class QueryBasedResolve {

        @Test
        void composesSpecificationFromCriteriaAndDelegatesToRepository() {
            var s = suite("TS-Q-001", TestSuitePopulationMode.QUERY_BASED);
            s.setCriteriaStatus(TestCaseStatus.APPROVED);
            var tcA = testCase("TC-A");
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            // F3 fix: resolve uses Pageable so the cap reaches the DB. The
            // mock returns a Page so the .getContent() call on the service
            // side reaches our fixture.
            when(testCaseRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(tcA)));

            assertThat(testSuiteService.resolveTestCases(projectId, s.getId())).containsExactly(tcA);
        }

        @Test
        void composesEveryCriterionWhenAllSet() {
            var s = suite("TS-Q-FULL", TestSuitePopulationMode.QUERY_BASED);
            s.setCriteriaStatus(TestCaseStatus.APPROVED);
            s.setCriteriaType(TestCaseType.AUTOMATED);
            s.setCriteriaPriority(TestCasePriority.HIGH);
            s.setCriteriaFormat(com.keplerops.groundcontrol.domain.testcases.state.TestCaseFormat.STEP_BASED);
            UUID folderId = UUID.randomUUID();
            s.setCriteriaFolderId(folderId);
            s.setCriteriaTextSearch("payment");

            var folder = new TestCaseFolder(project, null, "Root", null, 0);
            setField(folder, "id", folderId);
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            when(folderRepository.findByProjectIdOrderBySortOrder(projectId)).thenReturn(List.of(folder));
            when(testCaseRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

            // Successfully composes every criterion branch (type / priority /
            // format / folder-tree / text search) without throwing.
            assertThat(testSuiteService.resolveTestCases(projectId, s.getId())).isEmpty();
        }
    }

    @Nested
    class CrudReads {

        @Test
        void getByIdDelegatesToRequireSuiteInProject() {
            var s = suite("TS-G-001", TestSuitePopulationMode.STATIC);
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));

            assertThat(testSuiteService.getById(projectId, s.getId())).isSameAs(s);
        }

        @Test
        void getByIdThrowsNotFoundWhenMissing() {
            var id = UUID.randomUUID();
            when(testSuiteRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> testSuiteService.getById(projectId, id))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining(id.toString());
        }

        @Test
        void getByUidReturnsExistingSuite() {
            var s = suite("TS-U-001", TestSuitePopulationMode.STATIC);
            when(testSuiteRepository.findByProjectIdAndUid(projectId, "TS-U-001"))
                    .thenReturn(Optional.of(s));

            assertThat(testSuiteService.getByUid(projectId, "TS-U-001")).isSameAs(s);
        }

        @Test
        void getByUidThrowsNotFoundWhenMissing() {
            when(testSuiteRepository.findByProjectIdAndUid(projectId, "TS-MISSING"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> testSuiteService.getByUid(projectId, "TS-MISSING"))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("TS-MISSING");
        }

        @Test
        void listByProjectDelegatesToRepository() {
            var a = suite("TS-L-001", TestSuitePopulationMode.STATIC);
            var b = suite("TS-L-002", TestSuitePopulationMode.QUERY_BASED);
            when(testSuiteRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(a, b));

            assertThat(testSuiteService.listByProject(projectId)).containsExactly(a, b);
        }

        @Test
        void deleteRemovesMembersSourcesAndSuite() {
            var s = suite("TS-D-001", TestSuitePopulationMode.STATIC);
            var member = new TestSuiteMember(s, testCase("TC-D-1"), 0);
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            when(memberRepository.findByTestSuiteId(s.getId())).thenReturn(List.of(member));
            when(sourceRepository.findByTestSuiteId(s.getId())).thenReturn(List.of());

            testSuiteService.delete(projectId, s.getId());

            verify(memberRepository).deleteAll(List.of(member));
            verify(sourceRepository).deleteAll(List.of());
            verify(testSuiteRepository).delete(s);
        }

        @Test
        void deleteRejectsConflictWhenTestRunsReferenceTheSuite() {
            // TC-008 / ADR-049: TestRun rows FK to this suite; the existence
            // check raises ConflictException before children are touched so
            // the operation is atomic.
            var s = suite("TS-RUN-001", TestSuitePopulationMode.STATIC);
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            when(testRunRepository.existsByTestSuiteId(s.getId())).thenReturn(true);

            assertThatThrownBy(() -> testSuiteService.delete(projectId, s.getId()))
                    .isInstanceOf(com.keplerops.groundcontrol.domain.exception.ConflictException.class)
                    .hasMessageContaining("associated test runs");
            verify(memberRepository, never()).deleteAll(anyList());
            verify(sourceRepository, never()).deleteAll(anyList());
            verify(testSuiteRepository, never()).delete(any(TestSuite.class));
        }

        @Test
        void listMembersDelegatesToRepository() {
            var s = suite("TS-LM-001", TestSuitePopulationMode.STATIC);
            var member = new TestSuiteMember(s, testCase("TC-X"), 0);
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            when(memberRepository.findByTestSuiteIdOrderByPosition(s.getId())).thenReturn(List.of(member));

            assertThat(testSuiteService.listMembers(projectId, s.getId())).containsExactly(member);
        }

        @Test
        void listSourceRequirementsDelegatesToRepository() {
            var s = suite("TS-LSR-001", TestSuitePopulationMode.REQUIREMENTS_BASED);
            var src = new TestSuiteSourceRequirement(s, requirement("REQ-X"));
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            when(sourceRepository.findByTestSuiteIdOrderByRequirementUid(s.getId()))
                    .thenReturn(List.of(src));

            assertThat(testSuiteService.listSourceRequirements(projectId, s.getId()))
                    .containsExactly(src);
        }
    }

    @Nested
    class MutationGuards {

        @Test
        void addMemberRejectsNullTestCaseId() {
            var s = suite("TS-S-001", TestSuitePopulationMode.STATIC);
            when(testSuiteRepository.findByIdAndProjectIdForUpdate(s.getId(), projectId))
                    .thenReturn(Optional.of(s));
            UUID suiteId = s.getId();
            var cmd = new AddTestSuiteMemberCommand(null, null);

            assertThatThrownBy(() -> testSuiteService.addMember(projectId, suiteId, cmd))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("test_case_id");
        }

        @Test
        void requireSuiteForMutationThrowsNotFoundWhenAbsent() {
            var missingId = UUID.randomUUID();
            when(testSuiteRepository.findByIdAndProjectIdForUpdate(missingId, projectId))
                    .thenReturn(Optional.empty());
            var cmd = new AddTestSuiteMemberCommand(UUID.randomUUID(), null);

            assertThatThrownBy(() -> testSuiteService.addMember(projectId, missingId, cmd))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining(missingId.toString());
        }

        @Test
        void addSourceRequirementRejectsNullRequirementId() {
            var s = suite("TS-R-001", TestSuitePopulationMode.REQUIREMENTS_BASED);
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            UUID suiteId = s.getId();

            assertThatThrownBy(() -> testSuiteService.addSourceRequirement(projectId, suiteId, null))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("requirement_id");
        }

        @Test
        void addSourceRequirementRejectsDuplicate() {
            var s = suite("TS-R-001", TestSuitePopulationMode.REQUIREMENTS_BASED);
            var req = requirement("REQ-DUP");
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            when(requirementRepository.findByIdAndProjectId(req.getId(), projectId))
                    .thenReturn(Optional.of(req));
            when(sourceRepository.existsByTestSuiteIdAndRequirementId(s.getId(), req.getId()))
                    .thenReturn(true);
            UUID suiteId = s.getId();
            UUID reqId = req.getId();

            assertThatThrownBy(() -> testSuiteService.addSourceRequirement(projectId, suiteId, reqId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("REQ-DUP");
        }

        @Test
        void removeSourceRequirementSucceedsWhenPresent() {
            var s = suite("TS-R-001", TestSuitePopulationMode.REQUIREMENTS_BASED);
            var req = requirement("REQ-RM");
            var src = new TestSuiteSourceRequirement(s, req);
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            when(sourceRepository.findByTestSuiteIdAndRequirementId(s.getId(), req.getId()))
                    .thenReturn(Optional.of(src));

            testSuiteService.removeSourceRequirement(projectId, s.getId(), req.getId());

            verify(sourceRepository).delete(src);
        }

        @Test
        void removeSourceRequirementRejectsMissing() {
            var s = suite("TS-R-001", TestSuitePopulationMode.REQUIREMENTS_BASED);
            var reqId = UUID.randomUUID();
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            when(sourceRepository.findByTestSuiteIdAndRequirementId(s.getId(), reqId))
                    .thenReturn(Optional.empty());
            UUID suiteId = s.getId();

            assertThatThrownBy(() -> testSuiteService.removeSourceRequirement(projectId, suiteId, reqId))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void requireSuiteInModeThrowsWhenModeMismatch() {
            // listMembers requires STATIC; calling it on REQUIREMENTS_BASED
            // exercises the requireSuiteInMode mode-mismatch path.
            var s = suite("TS-R-001", TestSuitePopulationMode.REQUIREMENTS_BASED);
            when(testSuiteRepository.findByIdAndProjectId(s.getId(), projectId)).thenReturn(Optional.of(s));
            UUID suiteId = s.getId();

            assertThatThrownBy(() -> testSuiteService.listMembers(projectId, suiteId))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("STATIC");
        }
    }

    @Nested
    class ReorderResorts {

        @Test
        void reorderReturnsMembersSortedByNewPosition() {
            var s = suite("TS-S-RO", TestSuitePopulationMode.STATIC);
            var tcA = testCase("TC-A");
            var tcB = testCase("TC-B");
            var mA = new TestSuiteMember(s, tcA, 0);
            var mB = new TestSuiteMember(s, tcB, 1);
            when(testSuiteRepository.findByIdAndProjectIdForUpdate(s.getId(), projectId))
                    .thenReturn(Optional.of(s));
            // SiblingOrderingHelper inspects current; resolve / re-sort runs
            // against the in-memory list.
            when(memberRepository.findByTestSuiteIdOrderByPosition(s.getId())).thenReturn(List.of(mA, mB));

            var reordered = testSuiteService.reorderMembers(projectId, s.getId(), List.of(tcB.getId(), tcA.getId()));

            // Returned in NEW position order: mB at 0, mA at 1.
            assertThat(reordered).containsExactly(mB, mA);
            assertThat(mB.getPosition()).isZero();
            assertThat(mA.getPosition()).isEqualTo(1);
        }
    }
}

package com.keplerops.groundcontrol.domain.testcases.service;

import static com.keplerops.groundcontrol.domain.testcases.service.TestSuiteServiceSupport.rejectAnyCriteriaPatchOnNonQuerySuite;
import static com.keplerops.groundcontrol.domain.testcases.service.TestSuiteServiceSupport.resolveNullable;
import static com.keplerops.groundcontrol.domain.testcases.service.TestSuiteServiceSupport.validateCriteriaForMode;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.testcases.model.TestCase;
import com.keplerops.groundcontrol.domain.testcases.model.TestCaseFolder;
import com.keplerops.groundcontrol.domain.testcases.model.TestSuite;
import com.keplerops.groundcontrol.domain.testcases.model.TestSuiteMember;
import com.keplerops.groundcontrol.domain.testcases.model.TestSuiteSourceRequirement;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseFolderRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestRunRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteMemberRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteSourceRequirementRepository;
import com.keplerops.groundcontrol.domain.testcases.state.TestSuitePopulationMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TC-007 / ADR-047 — Application service for {@link TestSuite}.
 *
 * <p>Owns CRUD, mode-specific population operations (static membership,
 * requirements-based sources), and the load-bearing read-time
 * resolution that dispatches on {@code population_mode}.
 *
 * <p>Mode is a suite-level invariant set at create and immutable
 * thereafter — there is no mode-transition method. Each mode-specific
 * operation rejects mismatching modes with
 * {@code invalid_test_suite_mode_operation}, and the entity rejects
 * non-null criteria assignments on non-QUERY_BASED suites with
 * {@code invalid_test_suite_mode_field}.
 */
@Service
@Transactional
public class TestSuiteService {

    private static final Logger log = LoggerFactory.getLogger(TestSuiteService.class);

    /**
     * Hard cap on resolve-time results, identical across modes (ADR-047).
     * A future pageable read can promote this to a config knob; today the
     * resolve endpoint is a "show me up to N matches" surface and the
     * cap keeps unbounded query criteria from returning a project's
     * entire test-case corpus.
     */
    static final int MAX_RESOLVED_TEST_CASES = 500;

    private static final String SUITE_NOT_FOUND = "Test suite not found: ";

    private final TestSuiteRepository testSuiteRepository;
    private final TestSuiteMemberRepository memberRepository;
    private final TestSuiteSourceRequirementRepository sourceRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseFolderRepository folderRepository;
    private final RequirementRepository requirementRepository;
    private final TestRunRepository testRunRepository;
    private final ProjectService projectService;
    private final TestSuiteResolver resolver;

    public TestSuiteService(
            TestSuiteRepository testSuiteRepository,
            TestSuiteMemberRepository memberRepository,
            TestSuiteSourceRequirementRepository sourceRepository,
            TestCaseRepository testCaseRepository,
            TestCaseFolderRepository folderRepository,
            RequirementRepository requirementRepository,
            TestRunRepository testRunRepository,
            ProjectService projectService) {
        this.testSuiteRepository = testSuiteRepository;
        this.memberRepository = memberRepository;
        this.sourceRepository = sourceRepository;
        this.testCaseRepository = testCaseRepository;
        this.folderRepository = folderRepository;
        this.requirementRepository = requirementRepository;
        this.testRunRepository = testRunRepository;
        this.projectService = projectService;
        this.resolver = new TestSuiteResolver(memberRepository, sourceRepository, testCaseRepository, folderRepository);
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    public TestSuite create(CreateTestSuiteCommand command) {
        var project = projectService.getById(command.projectId());
        if (testSuiteRepository.existsByProjectIdAndUid(project.getId(), command.uid())) {
            throw new ConflictException("Test suite with UID " + command.uid() + " already exists in this project");
        }
        var criteria = command.criteria() == null ? TestSuiteCriteriaCommand.empty() : command.criteria();
        validateCriteriaForMode(command.populationMode(), criteria);

        var suite = new TestSuite(project, command.uid(), command.name(), command.populationMode());
        suite.setDescription(command.description());
        if (command.populationMode() == TestSuitePopulationMode.QUERY_BASED) {
            applyCriteria(suite, criteria);
        }
        suite = testSuiteRepository.save(suite);
        log.info(
                "test_suite_created: uid={} project={} id={} mode={}",
                suite.getUid(),
                project.getIdentifier(),
                suite.getId(),
                suite.getPopulationMode());
        return suite;
    }

    @Transactional(readOnly = true)
    public TestSuite getById(UUID projectId, UUID id) {
        return requireSuiteInProject(projectId, id);
    }

    @Transactional(readOnly = true)
    public TestSuite getByUid(UUID projectId, String uid) {
        return testSuiteRepository
                .findByProjectIdAndUid(projectId, uid)
                .orElseThrow(() -> new NotFoundException(SUITE_NOT_FOUND + uid));
    }

    @Transactional(readOnly = true)
    public List<TestSuite> listByProject(UUID projectId) {
        return testSuiteRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public TestSuite update(UUID projectId, UUID id, UpdateTestSuiteCommand command) {
        var suite = requireSuiteInProject(projectId, id);
        if (command.name() != null) {
            suite.setName(command.name());
        }
        suite.setDescription(
                resolveNullable(command.clearDescription(), command.description(), suite.getDescription()));

        if (suite.getPopulationMode() == TestSuitePopulationMode.QUERY_BASED) {
            applyCriteriaPatch(suite, command);
            if (!suite.hasAnyCriteria()) {
                throw new DomainValidationException(
                        "QUERY_BASED test suite must have at least one criterion",
                        "invalid_test_suite_query",
                        Map.of("suite_id", suite.getId().toString()));
            }
        } else {
            rejectAnyCriteriaPatchOnNonQuerySuite(command, suite.getPopulationMode());
        }

        suite = testSuiteRepository.save(suite);
        log.info("test_suite_updated: id={} uid={}", suite.getId(), suite.getUid());
        return suite;
    }

    /**
     * Delete a test suite after rejecting it if any test runs reference it.
     *
     * <p>Per TC-008 / ADR-049, the run-side FK is non-null; this check raises
     * a domain-aware {@link ConflictException} before any child rows are
     * touched, so the operation stays atomic and the caller receives a
     * useful message rather than a late persistence integrity violation.
     *
     * <p>Children are loaded and entity-deleted (rather than bulk-deleted via
     * JPQL) so the persistence context stays consistent — a bulk delete
     * would leave stale instances pointing at the about-to-be-removed parent
     * and Hibernate would trip on a {@code TransientObjectException} when
     * the suite is flushed in the same transaction. The schema-level
     * cascade still backs this path up if a future caller side-steps it.
     */
    public void delete(UUID projectId, UUID id) {
        var suite = requireSuiteInProject(projectId, id);
        if (testRunRepository.existsByTestSuiteId(suite.getId())) {
            throw new ConflictException(
                    "Test suite " + suite.getUid() + " has associated test runs; archive or delete those first");
        }
        memberRepository.deleteAll(memberRepository.findByTestSuiteId(suite.getId()));
        sourceRepository.deleteAll(sourceRepository.findByTestSuiteId(suite.getId()));
        testSuiteRepository.delete(suite);
        log.info("test_suite_deleted: id={} uid={} mode={}", suite.getId(), suite.getUid(), suite.getPopulationMode());
    }

    // ------------------------------------------------------------------
    // STATIC members
    // ------------------------------------------------------------------

    public TestSuiteMember addMember(UUID projectId, UUID suiteId, AddTestSuiteMemberCommand command) {
        var suite = requireSuiteForMutation(projectId, suiteId, "addMember");
        if (command.testCaseId() == null) {
            throw new DomainValidationException("test_case_id must not be null", "invalid_test_suite_member", Map.of());
        }
        var testCase = testCaseRepository
                .findByIdAndProjectId(command.testCaseId(), projectId)
                .orElseThrow(() -> new NotFoundException("Test case not found: " + command.testCaseId()));
        if (memberRepository.existsByTestSuiteIdAndTestCaseId(suite.getId(), testCase.getId())) {
            throw new ConflictException("Test case " + testCase.getUid() + " is already a member of this suite");
        }
        // Shifts run in descending position order so the DEFERRABLE
        // UNIQUE(suite, position) constraint never sees a collision while
        // checked at commit time.
        var current = memberRepository.findByTestSuiteIdOrderByPosition(suite.getId());
        int target;
        if (command.position() == null || command.position() >= current.size()) {
            target = current.size();
        } else {
            target = command.position();
            for (int i = current.size() - 1; i >= 0; i--) {
                TestSuiteMember m = current.get(i);
                if (m.getPosition() >= target) {
                    m.setPosition(m.getPosition() + 1);
                    memberRepository.save(m);
                }
            }
        }
        var member = memberRepository.save(new TestSuiteMember(suite, testCase, target));
        log.info(
                "test_suite_member_added: suite_id={} test_case_id={} position={}",
                suite.getId(),
                testCase.getId(),
                target);
        return member;
    }

    public void removeMember(UUID projectId, UUID suiteId, UUID testCaseId) {
        var suite = requireSuiteForMutation(projectId, suiteId, "removeMember");
        var member = memberRepository
                .findByTestSuiteIdAndTestCaseId(suite.getId(), testCaseId)
                .orElseThrow(() -> new NotFoundException(
                        "Test case " + testCaseId + " is not a member of suite " + suite.getId()));
        // Compaction runs in ascending position order so each row moves
        // into the slot just vacated by its predecessor; the DEFERRABLE
        // UNIQUE(suite, position) constraint never sees a collision.
        var remaining = memberRepository.findByTestSuiteIdOrderByPosition(suite.getId());
        memberRepository.delete(member);
        int position = 0;
        for (TestSuiteMember m : remaining) {
            if (m.getId().equals(member.getId())) {
                continue;
            }
            if (m.getPosition() != position) {
                m.setPosition(position);
                memberRepository.save(m);
            }
            position++;
        }
        log.info("test_suite_member_removed: suite_id={} test_case_id={}", suite.getId(), testCaseId);
    }

    public List<TestSuiteMember> reorderMembers(UUID projectId, UUID suiteId, List<UUID> orderedTestCaseIds) {
        var suite = requireSuiteForMutation(projectId, suiteId, "reorderMembers");
        var current = memberRepository.findByTestSuiteIdOrderByPosition(suite.getId());
        SiblingOrderingHelper.applyOrdering(
                "test suite member",
                orderedTestCaseIds,
                current,
                m -> m.getTestCase().getId(),
                TestSuiteMember::setPosition);
        memberRepository.saveAll(current);
        // Return the rows in their new author-defined order rather than the
        // original load order, so the controller response matches the input
        // sequence the caller asked for.
        var resorted = new ArrayList<>(current);
        resorted.sort(Comparator.comparingInt(TestSuiteMember::getPosition));
        log.info("test_suite_members_reordered: suite_id={} count={}", suite.getId(), resorted.size());
        return resorted;
    }

    /**
     * Project-scoped, pessimistically-locked fetch used by every member /
     * source mutation so per-suite mutations serialize (codex pre-push
     * cycle 2 F3). Mode-mismatch checks still happen on the result so a
     * caller hitting an addMember on a REQUIREMENTS_BASED suite gets the
     * documented 422.
     */
    private TestSuite requireSuiteForMutation(UUID projectId, UUID suiteId, String operation) {
        var suite = testSuiteRepository
                .findByIdAndProjectIdForUpdate(suiteId, projectId)
                .orElseThrow(() -> new NotFoundException(SUITE_NOT_FOUND + suiteId));
        if (suite.getPopulationMode() != TestSuitePopulationMode.STATIC) {
            throw new DomainValidationException(
                    operation + " is only valid for STATIC suites",
                    "invalid_test_suite_mode_operation",
                    Map.of(
                            "operation", operation,
                            "required_mode", TestSuitePopulationMode.STATIC.name(),
                            "actual_mode", suite.getPopulationMode().name()));
        }
        return suite;
    }

    @Transactional(readOnly = true)
    public List<TestSuiteMember> listMembers(UUID projectId, UUID suiteId) {
        var suite = requireSuiteInMode(projectId, suiteId, TestSuitePopulationMode.STATIC, "listMembers");
        return memberRepository.findByTestSuiteIdOrderByPosition(suite.getId());
    }

    // ------------------------------------------------------------------
    // REQUIREMENTS_BASED source requirements
    // ------------------------------------------------------------------

    public TestSuiteSourceRequirement addSourceRequirement(UUID projectId, UUID suiteId, UUID requirementId) {
        var suite = requireSuiteInMode(
                projectId, suiteId, TestSuitePopulationMode.REQUIREMENTS_BASED, "addSourceRequirement");
        if (requirementId == null) {
            throw new DomainValidationException(
                    "requirement_id must not be null", "invalid_test_suite_source_requirement", Map.of());
        }
        var requirement = requirementRepository
                .findByIdAndProjectId(requirementId, projectId)
                .orElseThrow(() -> new NotFoundException("Requirement not found: " + requirementId));
        if (sourceRepository.existsByTestSuiteIdAndRequirementId(suite.getId(), requirement.getId())) {
            throw new ConflictException("Requirement " + requirement.getUid() + " is already a source of this suite");
        }
        var source = sourceRepository.save(new TestSuiteSourceRequirement(suite, requirement));
        log.info(
                "test_suite_source_requirement_added: suite_id={} requirement_id={}",
                suite.getId(),
                requirement.getId());
        return source;
    }

    public void removeSourceRequirement(UUID projectId, UUID suiteId, UUID requirementId) {
        var suite = requireSuiteInMode(
                projectId, suiteId, TestSuitePopulationMode.REQUIREMENTS_BASED, "removeSourceRequirement");
        var source = sourceRepository
                .findByTestSuiteIdAndRequirementId(suite.getId(), requirementId)
                .orElseThrow(() -> new NotFoundException(
                        "Requirement " + requirementId + " is not a source of suite " + suite.getId()));
        sourceRepository.delete(source);
        log.info("test_suite_source_requirement_removed: suite_id={} requirement_id={}", suite.getId(), requirementId);
    }

    @Transactional(readOnly = true)
    public List<TestSuiteSourceRequirement> listSourceRequirements(UUID projectId, UUID suiteId) {
        var suite = requireSuiteInMode(
                projectId, suiteId, TestSuitePopulationMode.REQUIREMENTS_BASED, "listSourceRequirements");
        return sourceRepository.findByTestSuiteIdOrderByRequirementUid(suite.getId());
    }

    // ------------------------------------------------------------------
    // Resolution — the load-bearing read
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TestCase> resolveTestCases(UUID projectId, UUID suiteId) {
        var suite = requireSuiteInProject(projectId, suiteId);
        return resolver.resolve(suite);
    }

    // ------------------------------------------------------------------
    // Mode-invariant helpers
    // ------------------------------------------------------------------

    private TestSuite requireSuiteInProject(UUID projectId, UUID id) {
        return testSuiteRepository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException(SUITE_NOT_FOUND + id));
    }

    private TestSuite requireSuiteInMode(
            UUID projectId, UUID suiteId, TestSuitePopulationMode requiredMode, String operation) {
        var suite = requireSuiteInProject(projectId, suiteId);
        if (suite.getPopulationMode() != requiredMode) {
            throw new DomainValidationException(
                    operation + " is only valid for " + requiredMode + " suites",
                    "invalid_test_suite_mode_operation",
                    Map.of(
                            "operation", operation,
                            "required_mode", requiredMode.name(),
                            "actual_mode", suite.getPopulationMode().name()));
        }
        return suite;
    }

    private void applyCriteria(TestSuite suite, TestSuiteCriteriaCommand criteria) {
        if (criteria.folderId() != null) {
            requireFolderInProject(suite.getProject().getId(), criteria.folderId());
        }
        suite.setCriteriaStatus(criteria.status());
        suite.setCriteriaType(criteria.type());
        suite.setCriteriaPriority(criteria.priority());
        suite.setCriteriaFormat(criteria.format());
        suite.setCriteriaFolderId(criteria.folderId());
        suite.setCriteriaTextSearch(criteria.textSearch());
    }

    private void applyCriteriaPatch(TestSuite suite, UpdateTestSuiteCommand command) {
        // The folder reference is the only criterion that requires a
        // cross-table existence check; clearing it skips the lookup.
        UUID targetFolderId = resolveNullable(
                command.clearCriteriaFolderId(), command.criteriaFolderId(), suite.getCriteriaFolderId());
        if (targetFolderId != null && !targetFolderId.equals(suite.getCriteriaFolderId())) {
            requireFolderInProject(suite.getProject().getId(), targetFolderId);
        }
        suite.setCriteriaStatus(
                resolveNullable(command.clearCriteriaStatus(), command.criteriaStatus(), suite.getCriteriaStatus()));
        suite.setCriteriaType(
                resolveNullable(command.clearCriteriaType(), command.criteriaType(), suite.getCriteriaType()));
        suite.setCriteriaPriority(resolveNullable(
                command.clearCriteriaPriority(), command.criteriaPriority(), suite.getCriteriaPriority()));
        suite.setCriteriaFormat(
                resolveNullable(command.clearCriteriaFormat(), command.criteriaFormat(), suite.getCriteriaFormat()));
        suite.setCriteriaFolderId(targetFolderId);
        suite.setCriteriaTextSearch(resolveNullable(
                command.clearCriteriaTextSearch(), command.criteriaTextSearch(), suite.getCriteriaTextSearch()));
    }

    private TestCaseFolder requireFolderInProject(UUID projectId, UUID folderId) {
        var folder = folderRepository
                .findById(folderId)
                .orElseThrow(() -> new NotFoundException("Test case folder not found: " + folderId));
        if (folder.getProject() == null || !projectId.equals(folder.getProject().getId())) {
            // 404, not 403 — same project-scoping pattern as the rest of the
            // test-management domain.
            throw new NotFoundException("Test case folder not found: " + folderId);
        }
        return folder;
    }
}

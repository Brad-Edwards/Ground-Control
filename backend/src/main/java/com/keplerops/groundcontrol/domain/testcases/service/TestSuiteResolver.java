package com.keplerops.groundcontrol.domain.testcases.service;

import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.testcases.model.TestCase;
import com.keplerops.groundcontrol.domain.testcases.model.TestCaseFolder;
import com.keplerops.groundcontrol.domain.testcases.model.TestSuite;
import com.keplerops.groundcontrol.domain.testcases.model.TestSuiteMember;
import com.keplerops.groundcontrol.domain.testcases.model.TestSuiteSourceRequirement;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseFolderRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseSpecifications;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteMemberRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteSourceRequirementRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Resolves a test suite to its member test cases, one strategy per population
 * mode (ADR-047).
 *
 * Split out of {@link TestSuiteService} under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). The service still owns the transaction and the
 * project-scoped lookup; this owns only the mode-to-query mapping, so the
 * public surface is unchanged.
 */
final class TestSuiteResolver {

    private final TestSuiteMemberRepository memberRepository;
    private final TestSuiteSourceRequirementRepository sourceRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseFolderRepository folderRepository;

    TestSuiteResolver(
            TestSuiteMemberRepository memberRepository,
            TestSuiteSourceRequirementRepository sourceRepository,
            TestCaseRepository testCaseRepository,
            TestCaseFolderRepository folderRepository) {
        this.memberRepository = memberRepository;
        this.sourceRepository = sourceRepository;
        this.testCaseRepository = testCaseRepository;
        this.folderRepository = folderRepository;
    }

    List<TestCase> resolve(TestSuite suite) {
        return switch (suite.getPopulationMode()) {
            case STATIC -> resolveStatic(suite);
            case REQUIREMENTS_BASED -> resolveRequirementsBased(suite);
            case QUERY_BASED -> resolveQueryBased(suite);
        };
    }

    private List<TestCase> resolveStatic(TestSuite suite) {
        Pageable cap = PageRequest.of(0, TestSuiteService.MAX_RESOLVED_TEST_CASES);
        return memberRepository.findByTestSuiteIdOrderByPosition(suite.getId(), cap).stream()
                .map(TestSuiteMember::getTestCase)
                .toList();
    }

    private List<TestCase> resolveRequirementsBased(TestSuite suite) {
        var sources = sourceRepository.findByTestSuiteIdOrderByRequirementUid(suite.getId());
        if (sources.isEmpty()) {
            return List.of();
        }
        var requirementIds = sources.stream()
                .map(TestSuiteSourceRequirement::getRequirement)
                .map(Requirement::getId)
                .toList();
        // A single filter+join+sort+limit query joins TraceabilityLink to
        // TestCase by uid in the same project, so stale identifiers
        // (deleted or foreign-project UIDs) drop out before the cap
        // applies. The prior in-memory union of "identifier set ∩ live
        // test cases" could silently truncate live matches when stale
        // identifiers occupied the first TestSuiteService.MAX_RESOLVED_TEST_CASES slots.
        return testCaseRepository.findLinkedTestCasesForRequirements(
                suite.getProject().getId(),
                requirementIds,
                LinkType.TESTS,
                ArtifactType.TEST,
                PageRequest.of(0, TestSuiteService.MAX_RESOLVED_TEST_CASES));
    }

    private List<TestCase> resolveQueryBased(TestSuite suite) {
        Specification<TestCase> spec =
                TestCaseSpecifications.hasProject(suite.getProject().getId());
        if (suite.getCriteriaStatus() != null) {
            spec = spec.and(TestCaseSpecifications.hasStatus(suite.getCriteriaStatus()));
        }
        if (suite.getCriteriaType() != null) {
            spec = spec.and(TestCaseSpecifications.hasType(suite.getCriteriaType()));
        }
        if (suite.getCriteriaPriority() != null) {
            spec = spec.and(TestCaseSpecifications.hasPriority(suite.getCriteriaPriority()));
        }
        if (suite.getCriteriaFormat() != null) {
            spec = spec.and(TestCaseSpecifications.hasFormat(suite.getCriteriaFormat()));
        }
        if (suite.getCriteriaFolderId() != null) {
            // ADR-047: folder criteria resolve to the named folder AND
            // every descendant. Expand the subtree first so the predicate
            // is an IN over the full set; a single-id equality would
            // silently omit nested cases.
            var subtree = collectFolderSubtreeIds(suite.getProject().getId(), suite.getCriteriaFolderId());
            spec = spec.and(TestCaseSpecifications.inFolderTree(subtree));
        }
        if (suite.getCriteriaTextSearch() != null
                && !suite.getCriteriaTextSearch().isBlank()) {
            spec = spec.and(TestCaseSpecifications.searchTitleOrDescription(suite.getCriteriaTextSearch()));
        }
        Pageable cap = PageRequest.of(0, TestSuiteService.MAX_RESOLVED_TEST_CASES, Sort.by(Sort.Order.asc("uid")));
        return testCaseRepository.findAll(spec, cap).getContent();
    }

    /**
     * Expand a folder id into the set of IDs covering the folder itself and
     * every descendant in the project. Fetches all project folders once and
     * walks the parent graph in Java so a deep tree never triggers a
     * cascade of SQL queries.
     */
    private Set<UUID> collectFolderSubtreeIds(UUID projectId, UUID rootFolderId) {
        var all = folderRepository.findByProjectIdOrderBySortOrder(projectId);
        Map<UUID, List<UUID>> childrenByParent = new HashMap<>();
        for (TestCaseFolder folder : all) {
            UUID parentId =
                    folder.getParent() == null ? null : folder.getParent().getId();
            childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(folder.getId());
        }
        Set<UUID> collected = new LinkedHashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(rootFolderId);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (!collected.add(current)) {
                continue;
            }
            List<UUID> children = childrenByParent.get(current);
            if (children != null) {
                queue.addAll(children);
            }
        }
        return collected;
    }
}

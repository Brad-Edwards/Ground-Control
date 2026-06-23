package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.service.CreateRequirementCommand;
import com.keplerops.groundcontrol.domain.requirements.service.RequirementService;
import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import com.keplerops.groundcontrol.domain.requirements.state.RequirementType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for server-side UID allocation via {@code uidPrefix} in
 * {@link CreateRequirementCommand}. These tests run against a live Postgres
 * instance via Testcontainers.
 *
 * <p>Per the repo plan-rules, @SpringBootTest + Testcontainers integration tests
 * do NOT contribute Sonar coverage; @WebMvcTest slices do. Both layers are present
 * for this feature: RequirementControllerTest covers the slice, this class covers
 * correctness of the allocation algorithm and concurrency safety.
 */
@Transactional
class RequirementUidAllocationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private ProjectRepository projectRepository;

    private Project testProject;

    @BeforeEach
    void setUp() {
        testProject = projectRepository.findByIdentifier("ground-control").orElseThrow();
    }

    private CreateRequirementCommand prefixCommand(String prefix) {
        return new CreateRequirementCommand(
                testProject.getId(),
                null,
                prefix,
                "Title for " + prefix,
                "Statement for " + prefix,
                null,
                RequirementType.FUNCTIONAL,
                Priority.MUST,
                null);
    }

    private CreateRequirementCommand uidCommand(String uid) {
        return new CreateRequirementCommand(
                testProject.getId(),
                uid,
                null,
                "Title for " + uid,
                "Statement for " + uid,
                null,
                RequirementType.FUNCTIONAL,
                Priority.MUST,
                null);
    }

    // -------------------------------------------------------------------------
    // Section A: basic sequential allocation
    // -------------------------------------------------------------------------

    @Test
    void prefixAllocationStartsAtOne() {
        var req = requirementService.create(prefixCommand("INTPLAT"));
        assertThat(req.getUid()).isEqualTo("INTPLAT-1");
    }

    @Test
    void prefixAllocationIncrementsSequentially() {
        var r1 = requirementService.create(prefixCommand("INTSEQ"));
        var r2 = requirementService.create(prefixCommand("INTSEQ"));
        assertThat(r1.getUid()).isEqualTo("INTSEQ-1");
        assertThat(r2.getUid()).isEqualTo("INTSEQ-2");
    }

    @Test
    void explicitUidPrecededByPrefixDoesNotAffectNextAllocation() {
        // explicit PLAT-005 then prefix PLAT → PLAT-006
        requirementService.create(uidCommand("INTPLT-5"));
        var next = requirementService.create(prefixCommand("INTPLT"));
        assertThat(next.getUid()).isEqualTo("INTPLT-6");
    }

    // -------------------------------------------------------------------------
    // Section B: archived rows stay reserved
    // -------------------------------------------------------------------------

    @Test
    void archivedUidStaysReservedPrefixSkipsIt() {
        // create INTARCH-1, then transition it to ARCHIVED
        var created = requirementService.create(prefixCommand("INTARCH"));
        assertThat(created.getUid()).isEqualTo("INTARCH-1");

        // archive it (must go DRAFT->ACTIVE->ARCHIVED)
        requirementService.transitionStatus(created.getId(), Status.ACTIVE);
        requirementService.archive(created.getId());

        // next prefix allocation must skip the archived slot and return INTARCH-2
        var next = requirementService.create(prefixCommand("INTARCH"));
        assertThat(next.getUid()).isEqualTo("INTARCH-2");
    }

    // -------------------------------------------------------------------------
    // Section C: prefix anchoring — PLAT != PLATFORM
    // -------------------------------------------------------------------------

    @Test
    void prefixAnchoringPlatformDoesNotCountAsPlatSuffix() {
        // Create INTPLATFORM-001 explicitly; prefix INTPFX should still start at INTPFX-1
        requirementService.create(uidCommand("INTPLATFORM-001"));
        var req = requirementService.create(prefixCommand("INTPFX"));
        assertThat(req.getUid()).isEqualTo("INTPFX-1");
    }

    // -------------------------------------------------------------------------
    // Section D: numeric ordering (not lexicographic)
    // -------------------------------------------------------------------------

    @Test
    void numericOrderingNotLexicographicAfterNine() {
        // Create 9 requirements with prefix INTNUM, then the 10th must be INTNUM-10 not INTNUM-2
        for (int i = 0; i < 9; i++) {
            requirementService.create(prefixCommand("INTNUM"));
        }
        var tenth = requirementService.create(prefixCommand("INTNUM"));
        assertThat(tenth.getUid()).isEqualTo("INTNUM-10");
    }

    // -------------------------------------------------------------------------
    // Section E: invalid prefix rejected
    // -------------------------------------------------------------------------

    @Test
    void invalidPrefixThrowsDomainValidationException() {
        // lowercase prefix is invalid
        var cmd = new CreateRequirementCommand(
                testProject.getId(), null, "invalid-prefix!", "Title", "Statement", null, null, null, null);
        assertThatThrownBy(() -> requirementService.create(cmd))
                .isInstanceOf(DomainValidationException.class)
                .satisfies(e ->
                        assertThat(((DomainValidationException) e).getDetail()).containsKey("prefix"));
    }

    // -------------------------------------------------------------------------
    // Section F: concurrent allocation produces distinct UIDs
    // -------------------------------------------------------------------------

    @Test
    void concurrentAllocationProducesDistinctUids() throws Exception {
        // Each thread runs its own transaction to properly test the lock.
        // We use a unique prefix so these threads don't collide with other tests.
        String prefix = "INTCONC"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        int threads = 4;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String p = prefix;
            final Project project = testProject;
            tasks.add(() -> {
                // Each task needs its own Spring transaction — inject the service via the outer
                // test's @SpringBootTest context (which handles the per-thread TX boundary).
                var cmd = new CreateRequirementCommand(
                        project.getId(),
                        null,
                        p,
                        "Concurrent Title",
                        "Concurrent Statement",
                        null,
                        RequirementType.FUNCTIONAL,
                        Priority.MUST,
                        null);
                return requirementService.create(cmd).getUid();
            });
        }

        List<Future<String>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        List<String> uids = new ArrayList<>();
        for (Future<String> f : futures) {
            uids.add(f.get());
        }

        // All UIDs must be distinct — no two threads should have gotten the same number
        assertThat(uids).doesNotHaveDuplicates().allMatch(uid -> uid.startsWith(prefix + "-"));
    }
}

package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.graph.service.MixedGraphService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.service.GraphClient;
import com.keplerops.groundcontrol.infrastructure.age.AgeGraphSnapshotRepository;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-062 / issue #252: proves the versioned-snapshot publication is atomic from the reader's
 * perspective and non-destructive.
 *
 * <p>Deliberately NOT {@code @Transactional}: the behaviour under test is cross-transaction
 * visibility — a reader in one transaction must never see a publisher's half-built graph — so each
 * materialization and read has to really commit. A test-wide rollback would hide exactly the
 * property being verified.
 */
class AgeGraphSnapshotConcurrentRefreshIntegrationTest extends BaseAgeIntegrationTest {

    @Autowired
    private GraphClient graphClient;

    @Autowired
    private MixedGraphService mixedGraphService;

    @Autowired
    private RequirementRepository requirementRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AgeGraphSnapshotRepository snapshotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void publishingNewSnapshotLeavesThePreviousSnapshotIntact() {
        Project project = newProject();
        saveRequirements(project, 2);
        graphClient.materializeGraph();
        String v1Graph = snapshotRepository.findActiveGraphName().orElseThrow();
        long v1NodeCount = countNodes(v1Graph);
        assertThat(v1NodeCount)
                .as("v1 snapshot should contain the materialized nodes")
                .isPositive();

        saveRequirements(project, 1);
        graphClient.materializeGraph();
        String v2Graph = snapshotRepository.findActiveGraphName().orElseThrow();

        assertThat(v2Graph).as("publication builds a NEW snapshot graph").isNotEqualTo(v1Graph);
        // Non-destructive: the previous snapshot graph still exists and is byte-for-byte unchanged —
        // it was never DETACH DELETEd or rebuilt in place.
        assertThat(countNodes(v1Graph))
                .as("the previous snapshot must be untouched by a new publication")
                .isEqualTo(v1NodeCount);
        // The new snapshot reflects the added requirement.
        assertThat(countNodes(v2Graph))
                .as("the new snapshot reflects the added requirement")
                .isGreaterThan(v1NodeCount);
    }

    @Test
    void concurrentReadsNeverObservePartialGraphDuringRefresh() throws Exception {
        Project project = newProject();
        saveRequirements(project, 2);
        graphClient.materializeGraph();
        int v1Count = projectNodeCount(project);
        assertThat(v1Count).as("v1 is this project's two requirements").isEqualTo(2);

        // Stage the data the next snapshot will capture, but do not publish it yet.
        saveRequirements(project, 3);
        int expectedV2Count = 5;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<List<Integer>> reader = () -> {
                start.await();
                List<Integer> observed = new ArrayList<>();
                for (int i = 0; i < 300; i++) {
                    observed.add(projectNodeCount(project));
                }
                return observed;
            };
            Callable<Void> publisher = () -> {
                start.await();
                graphClient.materializeGraph();
                return null;
            };

            Future<List<Integer>> readerFuture = pool.submit(reader);
            Future<Void> publisherFuture = pool.submit(publisher);
            start.countDown();

            publisherFuture.get(60, TimeUnit.SECONDS);
            List<Integer> observed = readerFuture.get(60, TimeUnit.SECONDS);

            // Every observation is a COMPLETE snapshot: either the old graph (2) or the newly
            // published one (5), never a partial or empty count in between. This is the atomicity
            // guarantee — it holds regardless of how the two threads interleave.
            assertThat(observed)
                    .isNotEmpty()
                    .allMatch(
                            count -> count == v1Count || count == expectedV2Count,
                            "every read sees a complete snapshot (" + v1Count + " or " + expectedV2Count + ")");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cleanupEligibilityIsBeyondRetentionAndRetiredPastGrace() {
        Project project = newProject();
        saveRequirements(project, 1);
        graphClient.materializeGraph();
        String firstSnapshot = snapshotRepository.findActiveGraphName().orElseThrow();
        saveRequirements(project, 1);
        graphClient.materializeGraph();
        saveRequirements(project, 1);
        graphClient.materializeGraph();

        // With retention 2 and a zero grace, a snapshot that is beyond the newest two AND already
        // retired (it has a successor) is eligible to drop. This exercises the lead()-over-version
        // retirement query against real PostgreSQL. The current globally-active snapshot has no
        // successor, so its retired_at is NULL and it is never eligible — regardless of how many
        // snapshots other test methods left in the table.
        String globalActive = snapshotRepository.findActiveGraphName().orElseThrow();
        assertThat(snapshotRepository.graphsToDrop(2, 0L))
                .contains(firstSnapshot)
                .doesNotContain(globalActive);
        // And the default grace protects the just-retired snapshot from being dropped.
        assertThat(snapshotRepository.graphsToDrop(2, 300L)).doesNotContain(globalActive);
    }

    private Project newProject() {
        return projectRepository.save(new Project("snap-" + shortId(), "Snapshot Test " + shortId()));
    }

    private void saveRequirements(Project project, int count) {
        for (int i = 0; i < count; i++) {
            String uid = "SNAP-" + shortId();
            requirementRepository.save(new Requirement(project, uid, "Req " + uid, "Statement " + uid));
        }
    }

    private int projectNodeCount(Project project) {
        return mixedGraphService
                .getVisualization(project.getId(), List.of())
                .nodes()
                .size();
    }

    /** Count all vertices in a specific snapshot graph, on a single pinned connection. */
    private long countNodes(String graph) {
        Long count = jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("LOAD 'age'");
                statement.execute("SET search_path = ag_catalog, \"$user\", public");
                try (ResultSet rs = statement.executeQuery(
                        "SELECT count(*) FROM cypher('" + graph + "', $$ MATCH (n) RETURN n $$) AS (n agtype)")) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        return count == null ? 0L : count;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

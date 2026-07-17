package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.ObservationCategory;
import com.keplerops.groundcontrol.domain.audit.service.AsOfRevisionResolver;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.test.oracle.AbstractPortConformanceSuite;
import com.keplerops.groundcontrol.test.oracle.PortImplementation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-084 §5 conformance suite: the canonical as-of coordinate is the Envers revision number,
 * resolved by {@link AsOfRevisionResolver} against the real {@code revinfo} table.
 *
 * <p>Only one implementation is registered — the real JPA/Envers-backed resolver over real
 * PostgreSQL. This suite deliberately does not manufacture an in-memory fake resolver: revision
 * semantics (inclusive boundary, tie-breaking, SQL translation) are a property of the real
 * {@code revinfo} table and Hibernate/JPA query behavior, and a hand-rolled fake would prove
 * nothing about them (see issue #1309 plan, "Tests").
 *
 * <p>Deliberately NOT {@code @Transactional} and never uses Spring's {@code TestTransaction}
 * static utility: {@code @TestFactory}-produced {@link DynamicTest}s execute after their factory
 * method returns, outside the window Spring's {@code TransactionalTestExecutionListener} binds to
 * the "current" test method — combining them corrupted transaction-synchronization state badly
 * enough to cascade failures into unrelated test classes later in the same JVM run. Instead, each
 * repository call here is a plain, ordinary Spring Data call: with no ambient test transaction,
 * {@code SimpleJpaRepository} commits it immediately in its own transaction, which is exactly what
 * "force a real, distinct Envers revision per call" requires anyway (Envers assigns one revision
 * per transaction).
 *
 * <p>Uses the shared seeded "ground-control" project rather than creating a new one:
 * {@code ProjectService.resolveProject} treats "exactly one project exists" as the implicit
 * default and throws a 422 the moment a second project exists, so creating even an isolated,
 * uniquely-named project here breaks every other controller test in the suite that omits an
 * explicit {@code project} query parameter (discovered the hard way — it cascaded into dozens of
 * unrelated failures). Because {@link BaseIntegrationTest} shares one Testcontainers Postgres
 * instance across the whole suite and every row here is permanently committed, each scenario
 * deletes exactly the rows it created (by its own unique UID prefix) after asserting, so
 * "ground-control"'s requirement listing/count is unchanged for every other test. Tests avoid
 * asserting on absolute revision numbers seeded by other tests: boundary scenarios pin their own
 * revisions' {@code revtstmp} to controlled values via direct SQL after a real Envers commit, and
 * the "after latest" / "before all history" scenarios compare against a ground-truth SQL snapshot
 * taken immediately before invoking the resolver.
 */
class AsOfRevisionResolverConformanceTest extends BaseIntegrationTest
        implements AbstractPortConformanceSuite<AsOfRevisionResolver> {

    @Autowired
    private AsOfRevisionResolver resolver;

    @Autowired
    private RequirementRepository requirementRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private OperationalAssetRepository operationalAssetRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Project testProject;

    @Override
    public List<PortImplementation<AsOfRevisionResolver>> implementations() {
        return List.of(new PortImplementation<>("jpa-envers", () -> resolver));
    }

    @BeforeEach
    void setUp() {
        testProject = projectRepository.findByIdentifier("ground-control").orElseThrow();
    }

    /**
     * Commits a trivial audited mutation (a new {@link Requirement}) and returns the resulting
     * revision number. No ambient transaction wraps this call, so the repository save commits
     * immediately in its own transaction — Envers assigns one revision per transaction, so this
     * reliably produces a distinct revision per call.
     */
    private int commitRevision(String uid) {
        requirementRepository.save(new Requirement(testProject, uid, "Title " + uid, "Statement " + uid));
        return jdbcTemplate.queryForObject("SELECT MAX(rev) FROM revinfo", Integer.class);
    }

    private void pinRevisionTimestamp(int rev, long epochMillis) {
        jdbcTemplate.update("UPDATE revinfo SET revtstmp = ? WHERE rev = ?", epochMillis, rev);
    }

    /** Deletes every requirement whose UID starts with {@code prefix} — scenario-scoped cleanup. */
    private void deleteRequirementsWithPrefix(String prefix) {
        jdbcTemplate.update("DELETE FROM requirement WHERE uid LIKE ?", prefix + "%");
    }

    @TestFactory
    Stream<DynamicTest> exactTimestampBoundaryIsInclusive() {
        return conformanceCase("exact timestamp boundary is inclusive", port -> {
            try {
                int rev = commitRevision("ASOF-EXACT-" + UUID.randomUUID());
                long ts = 4_000_000_000_000L; // far-future control point, isolated from real wall-clock revisions
                pinRevisionTimestamp(rev, ts);

                assertThat(port.resolveAsOf(Instant.ofEpochMilli(ts))).contains(rev);
            } finally {
                deleteRequirementsWithPrefix("ASOF-EXACT-");
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> betweenTwoRevisionsSelectsTheLower() {
        return conformanceCase("between two revisions selects the lower", port -> {
            try {
                int revA = commitRevision("ASOF-A-" + UUID.randomUUID());
                int revB = commitRevision("ASOF-B-" + UUID.randomUUID());
                long tsA = 4_100_000_000_000L;
                long tsB = 4_200_000_000_000L;
                pinRevisionTimestamp(revA, tsA);
                pinRevisionTimestamp(revB, tsB);

                long between = (tsA + tsB) / 2;
                assertThat(port.resolveAsOf(Instant.ofEpochMilli(between))).contains(revA);
            } finally {
                deleteRequirementsWithPrefix("ASOF-A-");
                deleteRequirementsWithPrefix("ASOF-B-");
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> afterLatestSelectsTheLatest() {
        return conformanceCase("after the latest selects the latest", port -> {
            try {
                commitRevision("ASOF-LATEST-" + UUID.randomUUID());
                Integer groundTruthLatest = jdbcTemplate.queryForObject("SELECT MAX(rev) FROM revinfo", Integer.class);

                assertThat(port.resolveAsOf(Instant.now().plusSeconds(3600))).contains(groundTruthLatest);
                assertThat(port.currentRevision()).contains(groundTruthLatest);
            } finally {
                deleteRequirementsWithPrefix("ASOF-LATEST-");
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> beforeAllHistoryResolvesToEmpty() {
        return conformanceCase("before all history resolves to empty", port -> {
            assertThat(port.resolveAsOf(Instant.EPOCH)).isEmpty();
        });
    }

    @TestFactory
    Stream<DynamicTest> sameMillisecondRevisionsSelectGreatestRev() {
        return conformanceCase("same-millisecond revisions select the greatest rev", port -> {
            try {
                int revA = commitRevision("ASOF-TIE-A-" + UUID.randomUUID());
                int revB = commitRevision("ASOF-TIE-B-" + UUID.randomUUID());
                long tieTs = 4_300_000_000_000L;
                pinRevisionTimestamp(revA, tieTs);
                pinRevisionTimestamp(revB, tieTs);

                assertThat(revB).isGreaterThan(revA);
                assertThat(port.resolveAsOf(Instant.ofEpochMilli(tieTs))).contains(revB);
            } finally {
                deleteRequirementsWithPrefix("ASOF-TIE-A-");
                deleteRequirementsWithPrefix("ASOF-TIE-B-");
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> backdatedBusinessEventsDoNotBypassRevisionTimeVisibility() {
        return conformanceCase("backdated business events do not bypass revision-time visibility", port -> {
            OperationalAsset asset = null;
            try {
                // Observation.observedAt is a caller-controlled BUSINESS timestamp that can be set
                // to any past instant (e.g. importing historical telemetry). The revision this
                // write is recorded under is always assigned at real commit time, never at the
                // business timestamp the caller supplied. Prove the resolver honors that:
                // resolving at the (far-past) business timestamp must NOT surface a revision only
                // visible at the real, recent commit time.
                asset = operationalAssetRepository.save(
                        new OperationalAsset(testProject, "ASOF-ASSET-" + UUID.randomUUID(), "As-of test asset"));

                Instant backdatedBusinessTime = Instant.now().minusSeconds(60L * 60 * 24 * 365);
                observationRepository.save(new Observation(
                        asset,
                        ObservationCategory.OTHER,
                        "asof-key",
                        "asof-value",
                        "asof-source",
                        backdatedBusinessTime));
                Integer rev = jdbcTemplate.queryForObject("SELECT MAX(rev) FROM revinfo", Integer.class);
                Long realRevisionTs =
                        jdbcTemplate.queryForObject("SELECT revtstmp FROM revinfo WHERE rev = ?", Long.class, rev);

                // realRevisionTs is real commit time (now), far later than the backdated business
                // claim — resolving at the business timestamp must exclude this revision.
                assertThat(realRevisionTs).isGreaterThan(backdatedBusinessTime.toEpochMilli());
                assertThat(port.resolveAsOf(backdatedBusinessTime)).isNotEqualTo(Optional.of(rev));
                assertThat(port.resolveAsOf(Instant.ofEpochMilli(realRevisionTs)))
                        .contains(rev);
            } finally {
                if (asset != null) {
                    jdbcTemplate.update("DELETE FROM observation WHERE asset_id = ?", asset.getId());
                    jdbcTemplate.update("DELETE FROM operational_asset WHERE id = ?", asset.getId());
                }
            }
        });
    }
}

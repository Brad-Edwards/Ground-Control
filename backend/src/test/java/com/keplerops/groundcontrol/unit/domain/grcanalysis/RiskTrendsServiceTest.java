package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTrendsBucket;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTrendsResult;
import com.keplerops.groundcontrol.domain.grcanalysis.service.RiskTrendsService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskRegisterStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditQuery;
import org.hibernate.envers.query.AuditQueryCreator;
import org.hibernate.envers.query.criteria.AuditCriterion;
import org.hibernate.envers.query.order.AuditOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskTrendsServiceTest {

    @Mock
    private RiskRegisterRecordRepository registerRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private RiskTrendsService service;

    private Project project;
    private UUID projectId;
    private static final Instant ASOF = Instant.parse("2026-05-30T00:00:00Z");

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
    }

    @Test
    void noRecords_shortCircuitsWithoutEnversCall() {
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());

        RiskTrendsResult result = service.trends(projectId, ASOF, null, null, RiskTrendsBucket.MONTH);

        assertThat(result.analysisKind()).isEqualTo("risk_trends");
        assertThat(result.derivationMethod()).isEqualTo("risk-register-envers-audit-trends-v1");
        assertThat(result.scale()).isEqualTo("count");
        assertThat(result.units()).isEqualTo("audit revisions per bucket");
        assertThat(result.counts().totalEvents()).isZero();
        assertThat(result.counts().totalBuckets()).isZero();
        assertThat(result.inputs().bucket()).isEqualTo("MONTH");
        assertThat(result.inputs().entity()).isEqualTo("RiskRegisterRecord");
        // No EntityManager calls when there are no project record ids to query.
        verifyNoInteractions(entityManager);
    }

    @Test
    void defaultedFrom_emitsLimitation() {
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());

        RiskTrendsResult result = service.trends(projectId, ASOF, null, ASOF, RiskTrendsBucket.MONTH);

        assertThat(result.limitations()).anyMatch(s -> s.contains("12 months"));
        assertThat(result.inputs().from()).isEqualTo(ASOF.minus(365, ChronoUnit.DAYS));
        assertThat(result.inputs().to()).isEqualTo(ASOF);
    }

    /**
     * Adversarial-review finding #1: the audit table for {@code RiskRegisterRecord}
     * does not carry {@code project_id} (the Project association is
     * {@code @NotAudited}), so trends are bounded to records currently in the
     * project. Every envelope MUST surface this limitation so consumers cannot
     * mistake the counts for an exhaustive audit log.
     */
    @Test
    void everyEnvelope_carries_deletedRecordsBoundLimitation() {
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());

        RiskTrendsResult result = service.trends(projectId, ASOF, null, null, RiskTrendsBucket.MONTH);

        assertThat(result.limitations())
                .anyMatch(
                        s -> s.contains("revisions of deleted records are not included") && s.contains("@NotAudited"));
    }

    @Test
    void invalidWindow_fromAfterTo_throws() {
        Instant from = ASOF.plus(1, ChronoUnit.DAYS);
        Instant to = ASOF;
        assertThatThrownBy(() -> service.trends(projectId, ASOF, from, to, RiskTrendsBucket.MONTH))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void invalidWindow_tooLarge_throws() {
        Instant from = ASOF.minus(365L * 10, ChronoUnit.DAYS);
        assertThatThrownBy(() -> service.trends(projectId, ASOF, from, ASOF, RiskTrendsBucket.MONTH))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void projectNotFound_throws() {
        UUID missing = UUID.randomUUID();
        when(projectRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.trends(missing, null, null, null, RiskTrendsBucket.MONTH))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void bucketAndEntityCarriedInInputs() {
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of());
        Instant from = ASOF.minus(30, ChronoUnit.DAYS);

        RiskTrendsResult result = service.trends(projectId, ASOF, from, ASOF, RiskTrendsBucket.WEEK);

        assertThat(result.inputs().bucket()).isEqualTo("WEEK");
        assertThat(result.inputs().from()).isEqualTo(from);
        assertThat(result.inputs().to()).isEqualTo(ASOF);
        verify(registerRepository).findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId);
    }

    // ------------------------------------------------------------------
    // Envers projection coverage (adversarial-review finding #5).
    //
    // Drives the AuditReaderFactory.get -> createQuery -> forRevisionsOfEntity ->
    // add(...) -> getResultList() chain with stubbed revision rows so we exercise
    // bucketStart for WEEK/MONTH/QUARTER, byStatus and byRevisionType accumulation,
    // and the readTimestamp reflective code path.
    // ------------------------------------------------------------------

    @Test
    void enversProjection_bucketsMonthly_accumulatesByStatusAndRevisionType() {
        RiskRegisterRecord live = registerRecord("R-1", RiskRegisterStatus.ASSESSED);

        // Two revisions in March 2026, one in April 2026 — should land in two MONTH buckets.
        Instant march15 = LocalDate.of(2026, 3, 15).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant march28 = LocalDate.of(2026, 3, 28).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant april5 = LocalDate.of(2026, 4, 5).atStartOfDay().toInstant(ZoneOffset.UTC);

        runWithStubbedAudit(
                List.of(live),
                List.of(
                        new Row(live, march15, RevisionType.ADD),
                        new Row(live, march28, RevisionType.MOD),
                        new Row(live, april5, RevisionType.MOD)),
                () -> {
                    Instant from = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant to = LocalDate.of(2026, 5, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

                    RiskTrendsResult result = service.trends(projectId, ASOF, from, to, RiskTrendsBucket.MONTH);

                    assertThat(result.counts().totalEvents()).isEqualTo(3);
                    assertThat(result.points()).hasSize(2);

                    RiskTrendsResult.TrendPoint marchPoint = result.points().get(0);
                    Instant marchStart = LocalDate.of(2026, 3, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant aprilStart = LocalDate.of(2026, 4, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    assertThat(marchPoint.windowStart()).isEqualTo(marchStart);
                    assertThat(marchPoint.windowEnd()).isEqualTo(aprilStart);
                    assertThat(marchPoint.totalRevisions()).isEqualTo(2);
                    assertThat(marchPoint.byRevisionType())
                            .containsEntry("ADD", 1)
                            .containsEntry("MOD", 1);
                    assertThat(marchPoint.byStatus()).containsEntry("ASSESSED", 2);

                    RiskTrendsResult.TrendPoint aprilPoint = result.points().get(1);
                    Instant mayStart = LocalDate.of(2026, 5, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    assertThat(aprilPoint.windowStart()).isEqualTo(aprilStart);
                    assertThat(aprilPoint.windowEnd()).isEqualTo(mayStart);
                    assertThat(aprilPoint.totalRevisions()).isEqualTo(1);
                    assertThat(aprilPoint.byRevisionType()).containsEntry("MOD", 1);
                });
    }

    @Test
    void enversProjection_bucketsWeekly_alignsOnIsoMonday() {
        RiskRegisterRecord live = registerRecord("R-2", RiskRegisterStatus.IDENTIFIED);

        // 2026-03-04 is a Wednesday; ISO week starts Monday 2026-03-02.
        Instant wednesday = LocalDate.of(2026, 3, 4).atStartOfDay().toInstant(ZoneOffset.UTC);
        // 2026-03-09 is the following Monday (next ISO week).
        Instant nextMonday = LocalDate.of(2026, 3, 9).atStartOfDay().toInstant(ZoneOffset.UTC);

        runWithStubbedAudit(
                List.of(live),
                List.of(new Row(live, wednesday, RevisionType.MOD), new Row(live, nextMonday, RevisionType.MOD)),
                () -> {
                    Instant from = LocalDate.of(2026, 2, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant to = LocalDate.of(2026, 4, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

                    RiskTrendsResult result = service.trends(projectId, ASOF, from, to, RiskTrendsBucket.WEEK);

                    assertThat(result.points()).hasSize(2);
                    Instant firstWeekStart =
                            LocalDate.of(2026, 3, 2).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant secondWeekStart =
                            LocalDate.of(2026, 3, 9).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant secondWeekEnd =
                            LocalDate.of(2026, 3, 16).atStartOfDay().toInstant(ZoneOffset.UTC);
                    assertThat(result.points().get(0).windowStart()).isEqualTo(firstWeekStart);
                    assertThat(result.points().get(0).windowEnd()).isEqualTo(secondWeekStart);
                    assertThat(result.points().get(1).windowStart()).isEqualTo(secondWeekStart);
                    assertThat(result.points().get(1).windowEnd()).isEqualTo(secondWeekEnd);
                });
    }

    @Test
    void enversProjection_bucketsQuarterly_alignsOnCalendarQuarters() {
        RiskRegisterRecord live = registerRecord("R-3", RiskRegisterStatus.ASSESSED);

        // 2026-02-10 → Q1 (Jan 1 .. Apr 1); 2026-08-15 → Q3 (Jul 1 .. Oct 1).
        Instant februaryEvent = LocalDate.of(2026, 2, 10).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant augustEvent = LocalDate.of(2026, 8, 15).atStartOfDay().toInstant(ZoneOffset.UTC);

        runWithStubbedAudit(
                List.of(live),
                List.of(new Row(live, februaryEvent, RevisionType.ADD), new Row(live, augustEvent, RevisionType.MOD)),
                () -> {
                    Instant from = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant to = LocalDate.of(2026, 10, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

                    RiskTrendsResult result = service.trends(projectId, ASOF, from, to, RiskTrendsBucket.QUARTER);

                    assertThat(result.points()).hasSize(2);
                    Instant q1Start = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant q2Start = LocalDate.of(2026, 4, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant q3Start = LocalDate.of(2026, 7, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant q4Start = LocalDate.of(2026, 10, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    assertThat(result.points().get(0).windowStart()).isEqualTo(q1Start);
                    assertThat(result.points().get(0).windowEnd()).isEqualTo(q2Start);
                    assertThat(result.points().get(1).windowStart()).isEqualTo(q3Start);
                    assertThat(result.points().get(1).windowEnd()).isEqualTo(q4Start);
                });
    }

    @Test
    void enversProjection_deletionRevision_isCountedWithDelRevisionType() {
        RiskRegisterRecord live = registerRecord("R-4", RiskRegisterStatus.ACCEPTED);

        Instant createdAt = LocalDate.of(2026, 1, 10).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant deletedAt = LocalDate.of(2026, 1, 20).atStartOfDay().toInstant(ZoneOffset.UTC);

        runWithStubbedAudit(
                List.of(live),
                List.of(new Row(live, createdAt, RevisionType.ADD), new Row(live, deletedAt, RevisionType.DEL)),
                () -> {
                    Instant from = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant to = LocalDate.of(2026, 2, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

                    RiskTrendsResult result = service.trends(projectId, ASOF, from, to, RiskTrendsBucket.MONTH);

                    assertThat(result.points()).hasSize(1);
                    RiskTrendsResult.TrendPoint januaryPoint = result.points().get(0);
                    assertThat(januaryPoint.totalRevisions()).isEqualTo(2);
                    assertThat(januaryPoint.byRevisionType())
                            .containsEntry("ADD", 1)
                            .containsEntry("DEL", 1);
                });
    }

    @Test
    void enversProjection_revisionOutsideWindow_isSkipped() {
        RiskRegisterRecord live = registerRecord("R-5", RiskRegisterStatus.ASSESSED);

        Instant inWindow = LocalDate.of(2026, 3, 15).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant beforeWindow = LocalDate.of(2025, 12, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant afterWindow = LocalDate.of(2026, 7, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

        runWithStubbedAudit(
                List.of(live),
                List.of(
                        new Row(live, beforeWindow, RevisionType.ADD),
                        new Row(live, inWindow, RevisionType.MOD),
                        new Row(live, afterWindow, RevisionType.MOD)),
                () -> {
                    Instant from = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant to = LocalDate.of(2026, 6, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

                    RiskTrendsResult result = service.trends(projectId, ASOF, from, to, RiskTrendsBucket.MONTH);

                    assertThat(result.counts().totalEvents()).isEqualTo(1);
                    assertThat(result.points()).hasSize(1);
                    assertThat(result.points().get(0).totalRevisions()).isEqualTo(1);
                    assertThat(result.points().get(0).byRevisionType()).containsEntry("MOD", 1);
                });
    }

    @Test
    void enversProjection_revisionInfoMissingTimestamp_isSkipped() {
        RiskRegisterRecord live = registerRecord("R-6", RiskRegisterStatus.ASSESSED);

        Instant validEvent = LocalDate.of(2026, 3, 15).atStartOfDay().toInstant(ZoneOffset.UTC);

        runWithStubbedAudit(
                List.of(live),
                List.of(
                        new Row(live, null, RevisionType.MOD), // revInfo lacks getTimestamp -> skip
                        new Row(live, validEvent, RevisionType.MOD)),
                () -> {
                    Instant from = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant to = LocalDate.of(2026, 6, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

                    RiskTrendsResult result = service.trends(projectId, ASOF, from, to, RiskTrendsBucket.MONTH);

                    assertThat(result.counts().totalEvents()).isEqualTo(1);
                    assertThat(result.points()).hasSize(1);
                });
    }

    @Test
    void enversProjection_revisionWithNullEntity_doesNotIncrementByStatus() {
        RiskRegisterRecord live = registerRecord("R-7", RiskRegisterStatus.ASSESSED);

        Instant event = LocalDate.of(2026, 3, 15).atStartOfDay().toInstant(ZoneOffset.UTC);

        runWithStubbedAudit(
                List.of(live),
                List.of(new Row(null, event, RevisionType.DEL)), // entity null (e.g. DEL revision)
                () -> {
                    Instant from = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    Instant to = LocalDate.of(2026, 6, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

                    RiskTrendsResult result = service.trends(projectId, ASOF, from, to, RiskTrendsBucket.MONTH);

                    assertThat(result.points()).hasSize(1);
                    RiskTrendsResult.TrendPoint point = result.points().get(0);
                    assertThat(point.totalRevisions()).isEqualTo(1);
                    assertThat(point.byRevisionType()).containsEntry("DEL", 1);
                    // Null entity must NOT contribute to byStatus accumulation.
                    assertThat(point.byStatus()).isEmpty();
                });
    }

    // --- helpers -------------------------------------------------------

    private RiskRegisterRecord registerRecord(String uid, RiskRegisterStatus status) {
        RiskRegisterRecord registerRecord = new RiskRegisterRecord(project, uid, "Risk " + uid);
        setField(registerRecord, "id", UUID.randomUUID());
        setField(registerRecord, "status", status);
        return registerRecord;
    }

    private void runWithStubbedAudit(List<RiskRegisterRecord> liveProjectRecords, List<Row> auditRows, Runnable body) {
        when(registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                .thenReturn(liveProjectRecords);

        AuditReader auditReader = Mockito.mock(AuditReader.class);
        AuditQueryCreator queryCreator = Mockito.mock(AuditQueryCreator.class);
        AuditQuery query = Mockito.mock(AuditQuery.class);

        // Mockito.lenient because the test exercises a single chain shape.
        lenient().when(auditReader.createQuery()).thenReturn(queryCreator);
        lenient()
                .when(queryCreator.forRevisionsOfEntity(RiskRegisterRecord.class, false, true))
                .thenReturn(query);
        lenient().when(query.add(any(AuditCriterion.class))).thenReturn(query);
        lenient().when(query.addOrder(any(AuditOrder.class))).thenReturn(query);

        List<Object[]> rowList = new ArrayList<>();
        for (Row r : auditRows) {
            rowList.add(new Object[] {r.entity(), r.revisionInfo(), r.revisionType()});
        }
        lenient().when(query.getResultList()).thenReturn(rowList);

        try (MockedStatic<AuditReaderFactory> factory = Mockito.mockStatic(AuditReaderFactory.class)) {
            factory.when(() -> AuditReaderFactory.get(entityManager)).thenReturn(auditReader);
            body.run();
        }
    }

    /**
     * Carries the per-revision tuple used to drive the Envers projection. When the
     * timestamp is null the stub revision info returns no Long from its
     * timestamp accessor — exercising the readTimestamp null-handling path. When
     * the timestamp is present the stub returns its epoch millis.
     */
    private record Row(RiskRegisterRecord entity, Instant timestamp, RevisionType revisionType) {

        Object revisionInfo() {
            return new StubRevisionInfo(timestamp);
        }
    }

    /**
     * Minimal stand-in for {@code GroundControlRevisionEntity}. The trends service
     * reads the timestamp via reflection on {@code getTimestamp}, so any class with
     * a long-returning method of that name works for the projection path test.
     */
    public static final class StubRevisionInfo {
        private final Long timestampMs;

        StubRevisionInfo(Instant timestamp) {
            this.timestampMs = timestamp == null ? null : timestamp.toEpochMilli();
        }

        public Long getTimestamp() {
            return timestampMs;
        }
    }
}

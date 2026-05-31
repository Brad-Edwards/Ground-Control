package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-T008 risk trends projection. Walks the Envers audit history of
 * {@link RiskRegisterRecord} for the project, buckets each revision into the
 * requested interval (default monthly), and counts revisions per status / per
 * revision type.
 *
 * <p>Per ADR-033 the audit-row actor is intentionally not surfaced — trends
 * report event counts only. Per ADR-035 the envelope carries the derivation
 * method explicitly so a consumer never confuses event-count totals with the
 * methodology-specific assessment outputs reported by the heat map / top-N
 * surfaces.
 *
 * <p>Live-roster bound: the audit table for {@link RiskRegisterRecord} does
 * not carry {@code project_id} (the Project association is {@code @NotAudited}
 * by convention across audited domain entities), so the roster of audited
 * record ids is sourced from the live project query. Revisions of records
 * that have since been deleted are therefore NOT included in the counts; an
 * explicit limitation ({@link #DELETED_RECORDS_NOT_COUNTED_LIMITATION}) is
 * always surfaced on the envelope so consumers cannot mistake the buckets for
 * an exhaustive log.
 */
@Service
@Transactional(readOnly = true)
public class RiskTrendsService {

    static final String ANALYSIS_KIND = "risk_trends";
    static final String DERIVATION_METHOD = "risk-register-envers-audit-trends-v1";
    static final String SCALE = "count";
    static final String UNITS = "audit revisions per bucket";
    static final long MAX_WINDOW_DAYS = 365L * 5;
    static final String DEFAULT_WINDOW_LIMITATION =
            "window 'from' defaulted to 12 months before asOf; supply 'from' explicitly to widen or narrow the window";
    /**
     * The Envers audit table for {@link RiskRegisterRecord} does NOT carry
     * {@code project_id} (the Project association is {@code @NotAudited} by
     * convention across the audited domain entities), so the trend roster is
     * bounded to records currently in the project. Historical revisions
     * belonging to records that have since been deleted are not part of the
     * buckets. This limitation is surfaced on every envelope so consumers do
     * not mistake the counts for an exhaustive audit log of every status
     * transition ever recorded against the project's risks.
     */
    static final String DELETED_RECORDS_NOT_COUNTED_LIMITATION =
            "trend counts are bounded to risk_register_record rows currently in the project;"
                    + " revisions of deleted records are not included because the audit table"
                    + " does not carry project_id (the Project association on RiskRegisterRecord"
                    + " is @NotAudited by convention across the audited domain entities)";

    private final RiskRegisterRecordRepository registerRepository;
    private final ProjectRepository projectRepository;
    private final EntityManager entityManager;

    public RiskTrendsService(
            RiskRegisterRecordRepository registerRepository,
            ProjectRepository projectRepository,
            EntityManager entityManager) {
        this.registerRepository = registerRepository;
        this.projectRepository = projectRepository;
        this.entityManager = entityManager;
    }

    public RiskTrendsResult trends(UUID projectId, Instant asOf, Instant from, Instant to, RiskTrendsBucket bucket) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(bucket, "bucket");
        Instant effectiveAsOf = asOf != null ? asOf : Instant.now();
        Instant effectiveTo = to != null ? to : effectiveAsOf;

        ResolvedWindow window = resolveWindow(from, effectiveTo);
        validateWindow(window.effectiveFrom(), effectiveTo);

        Project project = projectRepository
                .findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

        Set<UUID> ids = collectProjectRecordIds(projectId);

        BucketingResult bucketingResult = bucketAuditRevisions(ids, window.effectiveFrom(), effectiveTo, bucket);

        List<RiskTrendsResult.TrendPoint> points = buildTrendPoints(bucketingResult.bucketed(), bucket);

        List<String> limitations = buildLimitations(window.defaulted());

        return new RiskTrendsResult(
                ANALYSIS_KIND,
                project.getIdentifier(),
                effectiveAsOf,
                DERIVATION_METHOD,
                SCALE,
                UNITS,
                new RiskTrendsResult.Inputs(
                        project.getIdentifier(),
                        effectiveAsOf,
                        window.effectiveFrom(),
                        effectiveTo,
                        bucket.name(),
                        "RiskRegisterRecord"),
                points,
                new RiskTrendsResult.Counts(bucketingResult.totalEvents(), points.size()),
                limitations);
    }

    /** Resolves the effective window start, defaulting to 12 months before {@code effectiveTo}. */
    private static ResolvedWindow resolveWindow(Instant from, Instant effectiveTo) {
        if (from != null) {
            return new ResolvedWindow(from, false);
        }
        return new ResolvedWindow(effectiveTo.minus(365, ChronoUnit.DAYS), true);
    }

    private static void validateWindow(Instant effectiveFrom, Instant effectiveTo) {
        if (!effectiveFrom.isBefore(effectiveTo)) {
            throw new DomainValidationException(
                    "from must be strictly before to",
                    "validation_error",
                    Map.of("from", effectiveFrom.toString(), "to", effectiveTo.toString()));
        }
        if (ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) > MAX_WINDOW_DAYS) {
            throw new DomainValidationException(
                    "trend window exceeds maximum of " + MAX_WINDOW_DAYS + " days",
                    "validation_error",
                    Map.of("days", ChronoUnit.DAYS.between(effectiveFrom, effectiveTo)));
        }
    }

    /** Collects the IDs of all live risk register records for the project. */
    private Set<UUID> collectProjectRecordIds(UUID projectId) {
        List<RiskRegisterRecord> projectRecords =
                registerRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId);
        Set<UUID> ids = new HashSet<>();
        for (RiskRegisterRecord registerRecord : projectRecords) {
            ids.add(registerRecord.getId());
        }
        return ids;
    }

    /**
     * Queries the Envers audit log for the given record IDs within the window and
     * accumulates revision counts per bucket start instant.
     */
    private BucketingResult bucketAuditRevisions(
            Set<UUID> ids, Instant effectiveFrom, Instant effectiveTo, RiskTrendsBucket bucket) {
        Map<Instant, BucketAccumulator> bucketed = new TreeMap<>();
        int totalEvents = 0;
        if (!ids.isEmpty()) {
            totalEvents = queryAndAccumulate(ids, effectiveFrom, effectiveTo, bucket, bucketed);
        }
        return new BucketingResult(bucketed, totalEvents);
    }

    @SuppressWarnings("unchecked")
    private int queryAndAccumulate(
            Set<UUID> ids,
            Instant effectiveFrom,
            Instant effectiveTo,
            RiskTrendsBucket bucket,
            Map<Instant, BucketAccumulator> bucketed) {
        var auditReader = AuditReaderFactory.get(entityManager);
        List<Object[]> results = auditReader
                .createQuery()
                .forRevisionsOfEntity(RiskRegisterRecord.class, false, true)
                .add(AuditEntity.id().in(ids))
                .add(AuditEntity.revisionProperty("timestamp").ge(effectiveFrom.toEpochMilli()))
                .add(AuditEntity.revisionProperty("timestamp").le(effectiveTo.toEpochMilli()))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();
        int totalEvents = 0;
        for (Object[] row : results) {
            totalEvents += accumulateRow(row, effectiveFrom, effectiveTo, bucket, bucketed);
        }
        return totalEvents;
    }

    private static int accumulateRow(
            Object[] row,
            Instant effectiveFrom,
            Instant effectiveTo,
            RiskTrendsBucket bucket,
            Map<Instant, BucketAccumulator> bucketed) {
        RiskRegisterRecord entity = (RiskRegisterRecord) row[0];
        Object revInfo = row[1];
        RevisionType revType = (RevisionType) row[2];
        Instant timestamp = readTimestamp(revInfo);
        if (timestamp == null || timestamp.isBefore(effectiveFrom) || timestamp.isAfter(effectiveTo)) {
            return 0;
        }
        Instant windowStart = bucketStart(timestamp, bucket);
        BucketAccumulator acc = bucketed.computeIfAbsent(windowStart, k -> new BucketAccumulator());
        acc.total++;
        acc.byRevisionType.merge(revType.name(), 1, Integer::sum);
        if (entity != null && entity.getStatus() != null) {
            acc.byStatus.merge(entity.getStatus().name(), 1, Integer::sum);
        }
        return 1;
    }

    private static List<RiskTrendsResult.TrendPoint> buildTrendPoints(
            Map<Instant, BucketAccumulator> bucketed, RiskTrendsBucket bucket) {
        List<RiskTrendsResult.TrendPoint> points = new ArrayList<>(bucketed.size());
        for (Map.Entry<Instant, BucketAccumulator> entry : bucketed.entrySet()) {
            Instant start = entry.getKey();
            Instant end = bucketEnd(start, bucket);
            BucketAccumulator acc = entry.getValue();
            points.add(new RiskTrendsResult.TrendPoint(
                    start, end, acc.total, new LinkedHashMap<>(acc.byStatus), new LinkedHashMap<>(acc.byRevisionType)));
        }
        return points;
    }

    private static List<String> buildLimitations(boolean defaultedFrom) {
        List<String> limitations = new ArrayList<>();
        if (defaultedFrom) {
            limitations.add(DEFAULT_WINDOW_LIMITATION);
        }
        // Always surface the deleted-records limitation so consumers cannot mistake the
        // event counts for an exhaustive audit log of every status transition the
        // project's risks have ever undergone. See ADR-038.
        limitations.add(DELETED_RECORDS_NOT_COUNTED_LIMITATION);
        return limitations;
    }

    private record ResolvedWindow(Instant effectiveFrom, boolean defaulted) {}

    private record BucketingResult(Map<Instant, BucketAccumulator> bucketed, int totalEvents) {}

    private static Instant readTimestamp(Object revisionInfo) {
        if (revisionInfo == null) {
            return null;
        }
        try {
            var method = revisionInfo.getClass().getMethod("getTimestamp");
            Object ts = method.invoke(revisionInfo);
            return epochMilliToInstant(ts);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static Instant epochMilliToInstant(Object ts) {
        if (ts instanceof Long longValue) {
            return Instant.ofEpochMilli(longValue);
        }
        if (ts instanceof Number num) {
            return Instant.ofEpochMilli(num.longValue());
        }
        return null;
    }

    private static Instant bucketStart(Instant t, RiskTrendsBucket bucket) {
        LocalDate date = t.atOffset(ZoneOffset.UTC).toLocalDate();
        return switch (bucket) {
            case WEEK -> date.minusDays((date.getDayOfWeek().getValue() - 1L))
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC);
            case MONTH -> date.withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            case QUARTER -> {
                int month0 = date.getMonthValue() - 1;
                int quarterStartMonth = (month0 / 3) * 3 + 1;
                yield LocalDate.of(date.getYear(), quarterStartMonth, 1)
                        .atStartOfDay()
                        .toInstant(ZoneOffset.UTC);
            }
        };
    }

    private static Instant bucketEnd(Instant start, RiskTrendsBucket bucket) {
        LocalDate startDate = start.atOffset(ZoneOffset.UTC).toLocalDate();
        return switch (bucket) {
            case WEEK -> startDate.plusDays(7).atStartOfDay().toInstant(ZoneOffset.UTC);
            case MONTH -> startDate.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            case QUARTER -> startDate.plusMonths(3).atStartOfDay().toInstant(ZoneOffset.UTC);
        };
    }

    private static final class BucketAccumulator {
        int total = 0;
        Map<String, Integer> byStatus = new TreeMap<>();
        Map<String, Integer> byRevisionType = new TreeMap<>();
    }
}

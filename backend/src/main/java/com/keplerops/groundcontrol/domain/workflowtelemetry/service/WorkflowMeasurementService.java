package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingDisposition;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventEmitter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowGateFinding;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowGateFindingRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ADR-090 measurement projection over the ADR-061 reporting model (issue #1355).
 *
 * <p>Separate from {@link WorkflowTelemetryService} because the two answer different questions:
 * that service owns the run and phase-event lifecycle, this one owns what the gates found and what
 * the yield formulas make of it. {@link #persistFindings} is called from inside
 * {@code recordPhaseEvent} and therefore runs in that method's transaction, so an attempt and its
 * finding batch remain one atomic write; a partial batch must never be readable as a complete gate
 * outcome.
 *
 * <p>This is not a second measurement store. Findings are subordinate rows on the same reporting
 * model, and nothing here derives a gate verdict from a lifecycle event.
 */
@Service
public class WorkflowMeasurementService {

    /** The measurement contract version every aggregate response is computed against. */
    public static final String MEASUREMENT_VERSION = "gc.measurement/v1";

    /** Default look-back when from/to are omitted. */
    public static final int DEFAULT_WINDOW_DAYS = 30;

    /** Maximum allowed aggregation window in days. */
    public static final int MAX_WINDOW_DAYS = 366;

    /** Only pass and fail are evaluable; every other result is coverage, not a verdict. */
    private static final Set<StationResult> EVALUABLE = EnumSet.of(StationResult.PASS, StationResult.FAIL);

    /**
     * Upper bound on one attempt's batch. A gate producing more is a signal in itself, but an
     * unbounded batch would let a single pathological run dominate the store.
     */
    private static final int MAX_FINDINGS_PER_ATTEMPT = 500;

    private static final int MAX_FINDING_KEY = 200;
    private static final int MAX_FINDING_SOURCE_ID = 100;
    private static final int MAX_FINDING_CATEGORY = 300;
    private static final int MAX_FINDING_SEVERITY = 60;
    private static final int MAX_FINDING_CLASSIFICATION = 20;

    private static final String PROJECT_FIELD = "project";

    /** Reserved sequence that opens every {@code gc:} workflow marker; never allowed in stored fields. */
    private static final String RESERVED_MARKER = "<!-- gc:";

    private final WorkflowGateFindingRepository gateFindingRepository;
    private final WorkflowPhaseEventRepository phaseEventRepository;

    public WorkflowMeasurementService(
            WorkflowGateFindingRepository gateFindingRepository, WorkflowPhaseEventRepository phaseEventRepository) {
        this.gateFindingRepository = gateFindingRepository;
        this.phaseEventRepository = phaseEventRepository;
    }

    /**
     * Persist the findings one attempt observed, in the caller's transaction.
     *
     * <p>Counts are derived from the rows accepted here, never trusted as a caller-supplied total:
     * a batch claiming "17 findings" while sending three would otherwise be believed.
     */
    void persistFindings(WorkflowPhaseEvent event, List<GateFindingCommand> findings) {
        if (findings == null || findings.isEmpty()) {
            return;
        }
        if (findings.size() > MAX_FINDINGS_PER_ATTEMPT) {
            throw new DomainValidationException("findings batch exceeds the maximum of " + MAX_FINDINGS_PER_ATTEMPT);
        }
        var seen = new HashSet<String>();
        for (var finding : findings) {
            if (finding == null) {
                throw new DomainValidationException("findings must not contain a null entry");
            }
            var key = requireBounded(finding.findingKey(), "findingKey", MAX_FINDING_KEY);
            if (!seen.add(key)) {
                throw new DomainValidationException("duplicate findingKey within one batch: " + key);
            }
            if (finding.sourceKind() == null) {
                throw new DomainValidationException("sourceKind must not be null");
            }
            var row = new WorkflowGateFinding(
                    event.getRunId(),
                    event.getId(),
                    event.getProject(),
                    // The station is the attempt's, never the caller's: a batch cannot attribute its
                    // findings to a station other than the one that produced them.
                    event.getStationId() == null ? event.getPhase() : event.getStationId(),
                    finding.sourceKind(),
                    requireBounded(finding.sourceId(), "sourceId", MAX_FINDING_SOURCE_ID),
                    key);
            row.setCategory(optionalBounded(finding.category(), "category", MAX_FINDING_CATEGORY));
            row.setSeverity(optionalBounded(finding.severity(), "severity", MAX_FINDING_SEVERITY));
            row.setClassification(
                    optionalBounded(finding.classification(), "classification", MAX_FINDING_CLASSIFICATION));
            row.setOccurredAt(event.getOccurredAt());
            gateFindingRepository.save(row);
        }
    }

    /**
     * Move one finding to a terminal disposition.
     *
     * <p>Project-scoped, so a finding id is not an authorization capability. Monotonic and
     * idempotent: re-applying the same terminal value is a no-op so an at-least-once delivery
     * converges, while a conflicting terminal claim raises {@link ConflictException} rather than
     * being silently overwritten. Two sources disagreeing about whether something was fixed is a
     * fact worth surfacing, not one to settle by write order.
     */
    @Transactional
    public WorkflowGateFinding recordFindingDisposition(
            UUID findingId, String project, FindingDisposition disposition, String authorizationReference) {
        requireText(project, PROJECT_FIELD);
        if (findingId == null) {
            throw new DomainValidationException("findingId must not be null");
        }
        if (disposition == null || disposition == FindingDisposition.OPEN) {
            throw new DomainValidationException("disposition must name a terminal value");
        }
        var finding = gateFindingRepository
                .findByIdAndProject(findingId, project)
                .orElseThrow(() -> new NotFoundException("Workflow gate finding not found: " + findingId));
        try {
            finding.applyDisposition(disposition, authorizationReference);
        } catch (IllegalArgumentException invalid) {
            // The entity owns the authorization rule so no caller can bypass it. Surfaced as a
            // validation failure rather than a 500: the request is refusable, not broken.
            throw new DomainValidationException(invalid.getMessage());
        } catch (IllegalStateException conflict) {
            throw new ConflictException(conflict.getMessage());
        }
        return gateFindingRepository.save(finding);
    }

    /** Per-station yield and rework over a window, from evaluable attempts only. */
    @Transactional(readOnly = true)
    public Map<String, StationYieldCalculator.StationYield> aggregateStationYield(
            String project, Instant from, Instant to) {
        requireText(project, PROJECT_FIELD);
        var window = resolveWindow(from, to);
        var rows = phaseEventRepository
                .findEvaluableAttempts(
                        project, PhaseEventEmitter.ADR061_WORKFLOW_TELEMETRY, EVALUABLE, window.from(), window.to())
                .stream()
                .map(r -> new StationYieldCalculator.AttemptRow(
                        (String) r[0], (UUID) r[1], (Integer) r[2], (StationResult) r[3]))
                .toList();
        return StationYieldCalculator.compute(rows);
    }

    /** Finding counts grouped by station, reviewer/detector, category, severity, and disposition. */
    @Transactional(readOnly = true)
    public List<Object[]> aggregateFindingCounts(String project, Instant from, Instant to) {
        requireText(project, PROJECT_FIELD);
        var window = resolveWindow(from, to);
        return gateFindingRepository.aggregateByProject(project, window.from(), window.to());
    }

    /** A validated, bounded reporting window. */
    public record Window(Instant from, Instant to) {}

    /** The validated window an aggregate was computed over, so a response can report its bounds. */
    public Window resolveReportingWindow(Instant from, Instant to) {
        return resolveWindow(from, to);
    }

    /**
     * Default an omitted window to the standard look-back and validate the bounds.
     *
     * <p>Bounded on purpose: an unbounded scan is a denial-of-service surface, not a reporting
     * convenience.
     */
    private static Window resolveWindow(Instant from, Instant to) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(Duration.ofDays(DEFAULT_WINDOW_DAYS));
        if (!effectiveFrom.isBefore(effectiveTo)) {
            throw new DomainValidationException("from must be before to");
        }
        long days = Duration.between(effectiveFrom, effectiveTo).toDays();
        if (days > MAX_WINDOW_DAYS) {
            throw new DomainValidationException(
                    "time window must not exceed " + MAX_WINDOW_DAYS + " days (requested " + days + " days)");
        }
        return new Window(effectiveFrom, effectiveTo);
    }

    private static String requireBounded(String value, String field, int max) {
        requireText(value, field);
        rejectReservedMarker(value);
        if (value.length() > max) {
            throw new DomainValidationException(field + " must not exceed " + max + " characters");
        }
        return value;
    }

    /** Absence is a real observation: a source that cannot attest this must not have one invented. */
    private static String optionalBounded(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireBounded(value, field, max);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
    }

    private static void rejectReservedMarker(String value) {
        if (value != null && value.contains(RESERVED_MARKER)) {
            throw new DomainValidationException("field must not contain the reserved '" + RESERVED_MARKER + "' marker");
        }
    }
}

package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskappetite.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskappetite.model.ToleranceThreshold;
import com.keplerops.groundcontrol.domain.riskappetite.repository.RiskAppetiteProfileRepository;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates risk assessment results against a risk appetite profile (GC-T005). Read-only: it never
 * mutates {@code RiskAssessmentResult} or {@code RiskRegisterRecord} rows. For each assessment whose
 * methodology family matches the profile, each applicable {@link ToleranceThreshold} ceiling is
 * compared against the residual value read from {@code computedOutputs}. A residual that exceeds the
 * ceiling breaches appetite and is flagged for escalation. Comparisons that cannot be performed
 * (missing metric, currency/scale mismatch, non-numeric value, ordinal value outside the declared
 * scale) are reported as limitations, never as silent passes.
 */
@Service
@Transactional(readOnly = true)
public class RiskAppetiteEvaluationService {

    static final String ANALYSIS_KIND = "appetite_evaluation";
    static final String DERIVATION_METHOD = "risk-appetite-tolerance-evaluation-v1";

    private static final String KEY_PRIMARY_LOSS_MAGNITUDE = "primary_loss_magnitude";
    private static final String KEY_CURRENCY = "currency";

    private final RiskAppetiteProfileRepository appetiteProfileRepository;
    private final RiskAssessmentResultRepository assessmentRepository;
    private final ProjectRepository projectRepository;
    private final Clock clock;

    public RiskAppetiteEvaluationService(
            RiskAppetiteProfileRepository appetiteProfileRepository,
            RiskAssessmentResultRepository assessmentRepository,
            ProjectRepository projectRepository,
            Clock clock) {
        this.appetiteProfileRepository = appetiteProfileRepository;
        this.assessmentRepository = assessmentRepository;
        this.projectRepository = projectRepository;
        this.clock = clock;
    }

    public RiskAppetiteEvaluationResult evaluate(
            UUID projectId,
            Instant asOf,
            UUID profileId,
            String appetiteKey,
            UUID riskRegisterRecordId,
            UUID riskScenarioId) {
        Instant effectiveAsOf = asOf == null ? Instant.now(clock) : asOf;
        String projectIdentifier = resolveProjectIdentifier(projectId);
        RiskAppetiteProfile profile = resolveProfile(projectId, profileId, appetiteKey, effectiveAsOf);
        List<RiskAssessmentResult> rows = loadRows(projectId, riskRegisterRecordId, riskScenarioId);

        List<RiskAppetiteEvaluationResult.Evaluation> evaluations = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        List<ToleranceThreshold> thresholds =
                profile.getToleranceThresholds() == null ? List.of() : profile.getToleranceThresholds();
        if (thresholds.isEmpty()) {
            limitations.add("appetite profile defines no tolerance thresholds; nothing to evaluate");
        }

        Tally tally = accumulate(rows, profile, thresholds, evaluations);
        if (tally.familyMismatch() > 0) {
            limitations.add(tally.familyMismatch()
                    + " assessment result(s) skipped: methodology family differs from the "
                    + profile.getMethodologyFamily() + " appetite profile");
        }

        var summary = new RiskAppetiteEvaluationResult.Summary(
                evaluations.size(), tally.breached(), tally.escalations(), tally.notDerivable());
        return new RiskAppetiteEvaluationResult(
                projectIdentifier,
                ANALYSIS_KIND,
                effectiveAsOf,
                DERIVATION_METHOD,
                toProfileSummary(profile),
                evaluations,
                summary,
                limitations);
    }

    private record Tally(int familyMismatch, int breached, int escalations, int notDerivable) {}

    private Tally accumulate(
            List<RiskAssessmentResult> rows,
            RiskAppetiteProfile profile,
            List<ToleranceThreshold> thresholds,
            List<RiskAppetiteEvaluationResult.Evaluation> evaluations) {
        int familyMismatch = 0;
        int breached = 0;
        int escalations = 0;
        int notDerivable = 0;
        for (RiskAssessmentResult row : rows) {
            if (row.getMethodologyProfile() == null
                    || row.getMethodologyProfile().getFamily() != profile.getMethodologyFamily()) {
                familyMismatch++;
                continue;
            }
            for (ToleranceThreshold threshold : thresholds) {
                if (!thresholdApplies(threshold, row)) {
                    continue;
                }
                var evaluation = evaluateOne(row, threshold);
                evaluations.add(evaluation);
                if (evaluation.withinAppetite() == null) {
                    notDerivable++;
                } else if (evaluation.breached()) {
                    breached++;
                    escalations++;
                }
            }
        }
        return new Tally(familyMismatch, breached, escalations, notDerivable);
    }

    private RiskAppetiteEvaluationResult.Evaluation evaluateOne(
            RiskAssessmentResult row, ToleranceThreshold threshold) {
        List<String> itemLimitations = new ArrayList<>();
        Object residual = resolvePath(row.getComputedOutputs(), threshold.metricPath());
        UUID registerRecordId = row.getRiskRegisterRecord() == null
                ? null
                : row.getRiskRegisterRecord().getId();
        String scenarioUid =
                row.getRiskScenario() == null ? null : row.getRiskScenario().getUid();
        UUID scenarioId =
                row.getRiskScenario() == null ? null : row.getRiskScenario().getId();

        boolean ordinal = threshold.maxOrdinalValue() != null
                && !threshold.maxOrdinalValue().isBlank();
        String thresholdValue =
                ordinal ? threshold.maxOrdinalValue() : String.valueOf(threshold.maxQuantitativeValue());

        ToleranceVerdict verdict;
        if (residual == null) {
            itemLimitations.add("not-derivable: metric '" + threshold.metricPath() + "' absent from computed outputs");
            verdict = ToleranceVerdict.NOT_DERIVABLE;
        } else if (ordinal) {
            verdict = evaluateOrdinal(threshold, residual, itemLimitations);
        } else {
            verdict = evaluateQuantitative(threshold, row, residual, itemLimitations);
        }
        // null withinAppetite signals "not derivable"; a concrete verdict maps to within/breached.
        Boolean withinAppetite = verdict == ToleranceVerdict.NOT_DERIVABLE ? null : verdict == ToleranceVerdict.WITHIN;
        boolean breached = verdict == ToleranceVerdict.BREACHED;

        return new RiskAppetiteEvaluationResult.Evaluation(
                row.getId(),
                scenarioId,
                scenarioUid,
                registerRecordId,
                threshold.riskCategory(),
                threshold.metricPath(),
                residual == null ? null : String.valueOf(residual),
                thresholdValue,
                threshold.units(),
                withinAppetite,
                breached,
                breached,
                itemLimitations);
    }

    /** Three-state outcome of comparing one residual against one tolerance ceiling. */
    private enum ToleranceVerdict {
        WITHIN,
        BREACHED,
        NOT_DERIVABLE
    }

    private ToleranceVerdict evaluateQuantitative(
            ToleranceThreshold threshold, RiskAssessmentResult row, Object residual, List<String> itemLimitations) {
        if (threshold.currency() != null && !threshold.currency().isBlank()) {
            String rowCurrency = resolveRowCurrency(row);
            if (rowCurrency != null && !rowCurrency.equalsIgnoreCase(threshold.currency())) {
                itemLimitations.add("not-derivable: assessment currency " + rowCurrency
                        + " differs from tolerance currency " + threshold.currency());
                return ToleranceVerdict.NOT_DERIVABLE;
            }
        }
        Double residualNumber = asNumber(residual);
        if (residualNumber == null) {
            itemLimitations.add("not-derivable: residual value '" + residual + "' is not numeric");
            return ToleranceVerdict.NOT_DERIVABLE;
        }
        return residualNumber <= threshold.maxQuantitativeValue() ? ToleranceVerdict.WITHIN : ToleranceVerdict.BREACHED;
    }

    private ToleranceVerdict evaluateOrdinal(
            ToleranceThreshold threshold, Object residual, List<String> itemLimitations) {
        List<String> scale = threshold.orderedScale();
        String residualText = String.valueOf(residual);
        int residualIndex = indexOfIgnoreCase(scale, residualText);
        int ceilingIndex = indexOfIgnoreCase(scale, threshold.maxOrdinalValue());
        if (residualIndex < 0) {
            itemLimitations.add(
                    "not-derivable: residual band '" + residualText + "' is not in the tolerance ordered scale");
            return ToleranceVerdict.NOT_DERIVABLE;
        }
        if (ceilingIndex < 0) {
            itemLimitations.add("not-derivable: tolerance band '" + threshold.maxOrdinalValue()
                    + "' is not in its own ordered scale");
            return ToleranceVerdict.NOT_DERIVABLE;
        }
        return residualIndex <= ceilingIndex ? ToleranceVerdict.WITHIN : ToleranceVerdict.BREACHED;
    }

    private boolean thresholdApplies(ToleranceThreshold threshold, RiskAssessmentResult row) {
        if (threshold.riskCategory() == null || threshold.riskCategory().isBlank()) {
            return true;
        }
        if (row.getRiskRegisterRecord() == null || row.getRiskRegisterRecord().getCategoryTags() == null) {
            return false;
        }
        return row.getRiskRegisterRecord().getCategoryTags().stream()
                .anyMatch(tag -> tag != null && tag.equalsIgnoreCase(threshold.riskCategory()));
    }

    private RiskAppetiteProfile resolveProfile(
            UUID projectId, UUID profileId, String appetiteKey, Instant effectiveAsOf) {
        if (profileId != null) {
            return appetiteProfileRepository
                    .findByIdAndProjectId(profileId, projectId)
                    .orElseThrow(() -> new NotFoundException("Risk appetite profile not found: " + profileId));
        }
        if (appetiteKey != null && !appetiteKey.isBlank()) {
            return appetiteProfileRepository
                    .findByProjectIdAndAppetiteKeyAndStatus(projectId, appetiteKey, RiskAppetiteProfileStatus.ACTIVE)
                    .stream()
                    .filter(p -> coversInstant(p, effectiveAsOf))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException(
                            "No active risk appetite profile for key " + appetiteKey + " as of " + effectiveAsOf));
        }
        throw new DomainValidationException("Provide either profileId or appetiteKey to evaluate appetite");
    }

    private boolean coversInstant(RiskAppetiteProfile profile, Instant asOf) {
        boolean startedByAsOf = profile.getEffectiveFrom() != null
                && !profile.getEffectiveFrom().isAfter(asOf);
        boolean notYetEnded =
                profile.getEffectiveTo() == null || profile.getEffectiveTo().isAfter(asOf);
        return startedByAsOf && notYetEnded;
    }

    private List<RiskAssessmentResult> loadRows(UUID projectId, UUID riskRegisterRecordId, UUID riskScenarioId) {
        // Scope filters compose as an intersection (same contract as fair-cam-control-analytics):
        // when both are supplied, narrow the register-record rows to the requested scenario so the
        // escalation view never includes assessments outside the caller's requested scope.
        if (riskRegisterRecordId != null && riskScenarioId != null) {
            return assessmentRepository
                    .findByProjectIdAndRiskRegisterRecordIdOrderByCreatedAtDesc(projectId, riskRegisterRecordId)
                    .stream()
                    .filter(r -> r.getRiskScenario() != null
                            && riskScenarioId.equals(r.getRiskScenario().getId()))
                    .toList();
        }
        if (riskRegisterRecordId != null) {
            return assessmentRepository.findByProjectIdAndRiskRegisterRecordIdOrderByCreatedAtDesc(
                    projectId, riskRegisterRecordId);
        }
        if (riskScenarioId != null) {
            return assessmentRepository.findByProjectIdAndRiskScenarioIdOrderByCreatedAtDesc(projectId, riskScenarioId);
        }
        return assessmentRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId);
    }

    private String resolveRowCurrency(RiskAssessmentResult row) {
        Map<String, Object> inputs = row.getInputFactors();
        if (inputs == null) {
            return null;
        }
        Object plm = inputs.get(KEY_PRIMARY_LOSS_MAGNITUDE);
        if (plm instanceof Map<?, ?> plmMap) {
            Object currency = plmMap.get(KEY_CURRENCY);
            return currency == null ? null : String.valueOf(currency);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object resolvePath(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(segment);
            if (current == null) {
                return null;
            }
        }
        // A nested object/array is not a scalar residual value.
        return current instanceof Map || current instanceof List ? null : current;
    }

    private Double asNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int indexOfIgnoreCase(List<String> scale, String value) {
        if (scale == null || value == null) {
            return -1;
        }
        for (int i = 0; i < scale.size(); i++) {
            if (value.equalsIgnoreCase(scale.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private String resolveProjectIdentifier(UUID projectId) {
        return projectRepository
                .findById(projectId)
                .map(Project::getIdentifier)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
    }

    private RiskAppetiteEvaluationResult.ProfileSummary toProfileSummary(RiskAppetiteProfile profile) {
        return new RiskAppetiteEvaluationResult.ProfileSummary(
                profile.getId(),
                profile.getAppetiteKey(),
                profile.getVersion(),
                profile.getMethodologyFamily(),
                profile.getStatus(),
                profile.getEffectiveFrom(),
                profile.getEffectiveTo());
    }
}

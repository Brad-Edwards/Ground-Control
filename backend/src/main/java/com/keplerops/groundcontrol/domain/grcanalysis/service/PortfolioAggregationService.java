package com.keplerops.groundcontrol.domain.grcanalysis.service;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetCriticality;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.findings.model.Finding;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingStatus;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the GRC Portfolio Reporting Views per GC-Q013.
 *
 * <p>This is a <strong>read-only projection</strong> over existing aggregates — no new JPA aggregate,
 * table, or materialized view is introduced (GC-L007). It is the fifth delegate behind
 * {@link GrcAnalysisService}. Evidence-freshness counts (and project + asOf validation) are reused
 * wholesale from {@link EvidenceFreshnessAnalysisService#analyze}; every other dimension is counted in
 * memory from a single per-project load of each aggregate.
 */
@Service
@Transactional(readOnly = true)
public class PortfolioAggregationService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioAggregationService.class);

    static final String DERIVATION_METHOD = "portfolio-projection-v1";

    /**
     * Maximum number of ids materialised per drill-down list. The associated count is always the full
     * total; only the id list is bounded, and a truncation note is added to {@code limitations}.
     */
    public static final int MAX_DRILLDOWN = 500;

    private final EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;
    private final RiskScenarioRepository riskScenarioRepository;
    private final RiskAssessmentResultRepository riskAssessmentResultRepository;
    private final TreatmentPlanRepository treatmentPlanRepository;
    private final RiskRegisterRecordRepository riskRegisterRecordRepository;
    private final ControlRepository controlRepository;
    private final ControlEffectivenessAssessmentRepository controlEffectivenessAssessmentRepository;
    private final RiskControlMappingRepository riskControlMappingRepository;
    private final FindingRepository findingRepository;
    private final OperationalAssetRepository operationalAssetRepository;
    private final MethodologyProfileRepository methodologyProfileRepository;

    @SuppressWarnings("java:S107") // Portfolio aggregator over the GRC graph; each source is a distinct aggregate root.
    public PortfolioAggregationService(
            EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService,
            RiskScenarioRepository riskScenarioRepository,
            RiskAssessmentResultRepository riskAssessmentResultRepository,
            TreatmentPlanRepository treatmentPlanRepository,
            RiskRegisterRecordRepository riskRegisterRecordRepository,
            ControlRepository controlRepository,
            ControlEffectivenessAssessmentRepository controlEffectivenessAssessmentRepository,
            RiskControlMappingRepository riskControlMappingRepository,
            FindingRepository findingRepository,
            OperationalAssetRepository operationalAssetRepository,
            MethodologyProfileRepository methodologyProfileRepository) {
        this.evidenceFreshnessAnalysisService = evidenceFreshnessAnalysisService;
        this.riskScenarioRepository = riskScenarioRepository;
        this.riskAssessmentResultRepository = riskAssessmentResultRepository;
        this.treatmentPlanRepository = treatmentPlanRepository;
        this.riskRegisterRecordRepository = riskRegisterRecordRepository;
        this.controlRepository = controlRepository;
        this.controlEffectivenessAssessmentRepository = controlEffectivenessAssessmentRepository;
        this.riskControlMappingRepository = riskControlMappingRepository;
        this.findingRepository = findingRepository;
        this.operationalAssetRepository = operationalAssetRepository;
        this.methodologyProfileRepository = methodologyProfileRepository;
    }

    /**
     * Assembles the portfolio summary for a project.
     *
     * @param projectId           resolved project UUID (never null)
     * @param asOf                reference instant; null means now
     * @param freshnessWindowDays must be positive (validated by the freshness analysis)
     * @return composed portfolio summary
     */
    public PortfolioSummaryResult summarize(UUID projectId, Instant asOf, int freshnessWindowDays) {
        // Reuse the freshness analysis for evidence counts and for project/window validation.
        EvidenceFreshnessResult freshness =
                evidenceFreshnessAnalysisService.analyze(projectId, asOf, freshnessWindowDays, true, null, null);
        Instant effectiveAsOf = freshness.asOf();
        LocalDate asOfDate = effectiveAsOf.atZone(ZoneOffset.UTC).toLocalDate();

        // Drill-down id lists are bounded; counts stay exact and any truncation is recorded here.
        List<String> limitations = new ArrayList<>(freshness.limitations());

        PortfolioSummaryResult.RiskPosture riskPosture = riskPosture(projectId, effectiveAsOf, limitations);
        PortfolioSummaryResult.ControlHealth controlHealth = controlHealth(projectId, limitations);
        PortfolioSummaryResult.EvidenceFreshness evidenceFreshness = new PortfolioSummaryResult.EvidenceFreshness(
                freshness.counts().fresh(),
                freshness.counts().stale(),
                freshness.counts().expired(),
                freshness.counts().superseded(),
                freshness.counts().currentlyValid());
        PortfolioSummaryResult.FindingTrends findingTrends = findingTrends(projectId, asOfDate, limitations);
        PortfolioSummaryResult.AssetCriticality assetCriticality = assetCriticality(projectId, limitations);
        List<PortfolioSummaryResult.MethodologySummary> methodologySummaries = methodologySummaries(projectId);

        limitations.add("distribution maps contain only non-zero buckets; absent enum values are implicitly zero");

        log.info(
                "grcanalysis.portfolio assembled: project={} scenarios={} controls={} findings={} assets={}",
                freshness.project(),
                riskPosture.totalScenarios(),
                controlHealth.totalControls(),
                findingTrends.totalFindings(),
                assetCriticality.totalAssets());

        return new PortfolioSummaryResult(
                freshness.project(),
                effectiveAsOf,
                DERIVATION_METHOD,
                riskPosture,
                controlHealth,
                evidenceFreshness,
                findingTrends,
                assetCriticality,
                methodologySummaries,
                limitations);
    }

    private PortfolioSummaryResult.RiskPosture riskPosture(UUID projectId, Instant asOf, List<String> limitations) {
        List<RiskScenario> scenarios = riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<RiskAssessmentResult> assessments =
                riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId);
        List<TreatmentPlan> treatments = treatmentPlanRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<RiskRegisterRecord> registers =
                riskRegisterRecordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId);

        Map<String, Integer> scenariosByStatus = new LinkedHashMap<>();
        for (RiskScenario s : scenarios) {
            increment(scenariosByStatus, s.getStatus().name());
        }

        Map<String, Integer> assessmentsByApprovalState = new LinkedHashMap<>();
        int reassessmentSignals = 0;
        for (RiskAssessmentResult a : assessments) {
            increment(assessmentsByApprovalState, a.getApprovalState().name());
            if (a.getReassessmentRequiredAt() != null) {
                reassessmentSignals++;
            }
        }

        Map<String, Integer> treatmentsByStatus = new LinkedHashMap<>();
        Map<String, Integer> treatmentsByStrategy = new LinkedHashMap<>();
        for (TreatmentPlan t : treatments) {
            increment(treatmentsByStatus, t.getStatus().name());
            increment(treatmentsByStrategy, t.getStrategy().name());
        }

        Map<String, Integer> registerByStatus = new LinkedHashMap<>();
        List<String> overdueRegisterRecordUids = new ArrayList<>();
        for (RiskRegisterRecord r : registers) {
            increment(registerByStatus, r.getStatus().name());
            if (r.getNextReviewAt() != null && r.getNextReviewAt().isBefore(asOf)) {
                overdueRegisterRecordUids.add(r.getUid());
            }
        }

        return new PortfolioSummaryResult.RiskPosture(
                scenarios.size(),
                scenariosByStatus,
                assessments.size(),
                assessmentsByApprovalState,
                treatments.size(),
                treatmentsByStatus,
                treatmentsByStrategy,
                registers.size(),
                registerByStatus,
                reassessmentSignals,
                overdueRegisterRecordUids.size(),
                capDrilldown(overdueRegisterRecordUids, "overdue register reviews", limitations));
    }

    private PortfolioSummaryResult.ControlHealth controlHealth(UUID projectId, List<String> limitations) {
        List<Control> controls = controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<ControlEffectivenessAssessment> assessments =
                controlEffectivenessAssessmentRepository.findByProjectIdOrderByAssessedAtDesc(projectId);

        Map<String, Integer> controlsByStatus = new LinkedHashMap<>();
        for (Control c : controls) {
            increment(controlsByStatus, c.getStatus().name());
        }

        // Latest assessment per control (list is assessedAt desc, so first seen wins).
        Map<String, Integer> designDistribution = new LinkedHashMap<>();
        Map<String, Integer> operatingDistribution = new LinkedHashMap<>();
        Set<UUID> assessedControlIds = new HashSet<>();
        for (ControlEffectivenessAssessment a : assessments) {
            if (a.getControl() == null || !assessedControlIds.add(a.getControl().getId())) {
                continue;
            }
            increment(designDistribution, a.getDesignEffectiveness().name());
            increment(operatingDistribution, a.getOperatingEffectiveness().name());
        }

        List<String> unassessedControlUids = new ArrayList<>();
        for (Control c : controls) {
            if (!assessedControlIds.contains(c.getId())) {
                unassessedControlUids.add(c.getUid());
            }
        }

        Set<UUID> unmappedIdSet = new HashSet<>(riskControlMappingRepository.findUnmappedControlIds(projectId));
        List<String> unmappedControlUids = new ArrayList<>();
        for (Control c : controls) {
            if (unmappedIdSet.contains(c.getId())) {
                unmappedControlUids.add(c.getUid());
            }
        }

        return new PortfolioSummaryResult.ControlHealth(
                controls.size(),
                controlsByStatus,
                designDistribution,
                operatingDistribution,
                unassessedControlUids.size(),
                unmappedControlUids.size(),
                capDrilldown(unassessedControlUids, "unassessed controls", limitations),
                capDrilldown(unmappedControlUids, "unmapped controls", limitations));
    }

    private PortfolioSummaryResult.FindingTrends findingTrends(
            UUID projectId, LocalDate asOfDate, List<String> limitations) {
        List<Finding> findings = findingRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Integer> byType = new LinkedHashMap<>();
        List<String> openFindingUids = new ArrayList<>();
        List<String> overdueFindingUids = new ArrayList<>();
        for (Finding f : findings) {
            increment(bySeverity, f.getSeverity().name());
            increment(byStatus, f.getStatus().name());
            increment(byType, f.getFindingType().name());
            boolean closed = f.getStatus() == FindingStatus.VERIFIED_CLOSED;
            if (f.getStatus() == FindingStatus.OPEN) {
                openFindingUids.add(f.getUid());
            }
            if (!closed && f.getDueDate() != null && f.getDueDate().isBefore(asOfDate)) {
                overdueFindingUids.add(f.getUid());
            }
        }
        return new PortfolioSummaryResult.FindingTrends(
                findings.size(),
                bySeverity,
                byStatus,
                byType,
                openFindingUids.size(),
                overdueFindingUids.size(),
                capDrilldown(openFindingUids, "open findings", limitations),
                capDrilldown(overdueFindingUids, "overdue findings", limitations));
    }

    private PortfolioSummaryResult.AssetCriticality assetCriticality(UUID projectId, List<String> limitations) {
        List<OperationalAsset> assets = operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId);
        Map<String, Integer> byCriticality = new LinkedHashMap<>();
        Map<String, Integer> byEnvironment = new LinkedHashMap<>();
        Map<String, Integer> byScope = new LinkedHashMap<>();
        List<String> criticalAssetUids = new ArrayList<>();
        for (OperationalAsset a : assets) {
            if (a.getCriticality() != null) {
                increment(byCriticality, a.getCriticality().name());
                if (a.getCriticality() == AssetCriticality.CRITICAL) {
                    criticalAssetUids.add(a.getUid());
                }
            }
            if (a.getEnvironment() != null) {
                increment(byEnvironment, a.getEnvironment().name());
            }
            if (a.getScopeDesignation() != null) {
                increment(byScope, a.getScopeDesignation().name());
            }
        }
        return new PortfolioSummaryResult.AssetCriticality(
                assets.size(),
                byCriticality,
                byEnvironment,
                byScope,
                capDrilldown(criticalAssetUids, "critical assets", limitations));
    }

    private List<PortfolioSummaryResult.MethodologySummary> methodologySummaries(UUID projectId) {
        List<MethodologyProfile> profiles =
                methodologyProfileRepository.findByProjectIdOrderByNameAscVersionDesc(projectId);
        List<RiskAssessmentResult> assessments =
                riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId);

        // Map each methodology profile id to its family so assessments can be attributed by family.
        Map<UUID, MethodologyFamily> familyByProfileId = new LinkedHashMap<>();
        Map<String, int[]> profileCountByFamily = new LinkedHashMap<>(); // [profileCount]
        for (MethodologyProfile p : profiles) {
            familyByProfileId.put(p.getId(), p.getFamily());
            profileCountByFamily.computeIfAbsent(p.getFamily().name(), k -> new int[1])[0]++;
        }

        // [assessmentCount, approvedCount, withComputedOutputsCount]
        Map<String, int[]> assessmentStatsByFamily = new LinkedHashMap<>();
        for (RiskAssessmentResult a : assessments) {
            MethodologyProfile mp = a.getMethodologyProfile();
            if (mp == null) {
                continue;
            }
            MethodologyFamily family = familyByProfileId.get(mp.getId());
            if (family == null) {
                family = mp.getFamily();
            }
            int[] stats = assessmentStatsByFamily.computeIfAbsent(family.name(), k -> new int[3]);
            stats[0]++;
            if (a.getApprovalState() == RiskAssessmentApprovalStatus.APPROVED) {
                stats[1]++;
            }
            if (a.getComputedOutputs() != null && !a.getComputedOutputs().isEmpty()) {
                stats[2]++;
            }
        }

        Set<String> families = new java.util.LinkedHashSet<>();
        families.addAll(profileCountByFamily.keySet());
        families.addAll(assessmentStatsByFamily.keySet());

        List<PortfolioSummaryResult.MethodologySummary> summaries = new ArrayList<>();
        for (String family : families) {
            int profileCount = profileCountByFamily.getOrDefault(family, new int[1])[0];
            int[] stats = assessmentStatsByFamily.getOrDefault(family, new int[3]);
            summaries.add(
                    new PortfolioSummaryResult.MethodologySummary(family, profileCount, stats[0], stats[1], stats[2]));
        }
        return summaries;
    }

    private static void increment(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }

    /**
     * Returns {@code uids} bounded to {@link #MAX_DRILLDOWN}, recording a truncation note in
     * {@code limitations} when the list is shortened. The caller keeps the full {@code uids.size()} as
     * the dimension's count before calling this.
     */
    private static List<String> capDrilldown(List<String> uids, String label, List<String> limitations) {
        if (uids.size() <= MAX_DRILLDOWN) {
            return uids;
        }
        limitations.add(label + " drill-down list truncated to " + MAX_DRILLDOWN + " of " + uids.size());
        return new ArrayList<>(uids.subList(0, MAX_DRILLDOWN));
    }
}

package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.events.AssetStateChangedEvent;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ControlStateChangedEvent;
import com.keplerops.groundcontrol.domain.riskscenarios.events.KriBreachedEvent;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ReassessmentSignal;
import com.keplerops.groundcontrol.domain.riskscenarios.events.TreatmentProgressChangedEvent;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.KeyRiskIndicatorRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-T004 / C8 (#863): transactional reassessment signal listener.
 *
 * <p>Reassessment is a governance signal, not a cache rebuild. Per the preflight, this
 * listener stays synchronous, DB-only, idempotent, and directly tested — NOT
 * best-effort like the embedding listener. It does not recompute risk, file findings,
 * or send notifications; it sets {@code reassessmentRequiredAt} on the affected
 * assessment-result rows and stops there.
 *
 * <p>Traversal is bounded to the link surfaces named in the preflight:
 * {@code AssetLink}, {@code ControlLink}, {@code RiskScenarioLink}, plus
 * {@code TreatmentPlan.riskRegisterRecord} / {@code TreatmentPlan.riskScenario}. No
 * graph traversal, no AGE queries, no multi-hop inference. Handlers use
 * {@link EventListener} (NOT {@code @TransactionalEventListener}), so they run
 * inline in the publishing service's transaction — a listener failure rolls back
 * the publishing mutation, and the reassessment write commits or fails atomically
 * with it. This is the explicit non-best-effort contract from the preflight
 * (codex review #863 cycle 1: a separate-transaction listener would let the
 * source mutation commit while the signal silently disappears).
 */
@Service
@Transactional
public class ReassessmentSignalService {

    private static final Logger log = LoggerFactory.getLogger(ReassessmentSignalService.class);

    private final RiskAssessmentResultRepository assessmentRepository;
    private final TreatmentPlanRepository treatmentPlanRepository;
    private final AssetLinkRepository assetLinkRepository;
    private final ControlLinkRepository controlLinkRepository;
    private final RiskScenarioLinkRepository riskScenarioLinkRepository;
    private final KeyRiskIndicatorRepository keyRiskIndicatorRepository;

    @SuppressWarnings("java:S107") // the listener fans out across every project-scoped link surface
    public ReassessmentSignalService(
            RiskAssessmentResultRepository assessmentRepository,
            TreatmentPlanRepository treatmentPlanRepository,
            AssetLinkRepository assetLinkRepository,
            ControlLinkRepository controlLinkRepository,
            RiskScenarioLinkRepository riskScenarioLinkRepository,
            KeyRiskIndicatorRepository keyRiskIndicatorRepository) {
        this.assessmentRepository = assessmentRepository;
        this.treatmentPlanRepository = treatmentPlanRepository;
        this.assetLinkRepository = assetLinkRepository;
        this.controlLinkRepository = controlLinkRepository;
        this.riskScenarioLinkRepository = riskScenarioLinkRepository;
        this.keyRiskIndicatorRepository = keyRiskIndicatorRepository;
    }

    @EventListener
    public void onTreatmentProgressChanged(TreatmentProgressChangedEvent event) {
        var signal = event.signal();
        var affectedResults = collectFromTreatmentPlan(signal);
        markReassessmentRequired(affectedResults, signal);
    }

    @EventListener
    public void onAssetStateChanged(AssetStateChangedEvent event) {
        var signal = event.signal();
        var affectedResults = collectFromAsset(signal);
        markReassessmentRequired(affectedResults, signal);
    }

    @EventListener
    public void onControlStateChanged(ControlStateChangedEvent event) {
        var signal = event.signal();
        var affectedResults = collectFromControl(signal);
        markReassessmentRequired(affectedResults, signal);
    }

    /**
     * GC-T007: KRI breach fans the reassessment signal to assessments under the
     * KRI's linked register record / scenario. Synchronous {@code @EventListener}
     * (NOT {@code @TransactionalEventListener}) per the shared cross-cluster
     * contract — a listener failure rolls back the KRI measurement write.
     */
    @EventListener
    public void onKriBreached(KriBreachedEvent event) {
        var signal = event.signal();
        var affectedResults = collectFromKri(signal);
        markReassessmentRequired(affectedResults, signal);
    }

    /** Treatment plan → its register record + (optionally) scenario → assessment results. */
    private Set<UUID> collectFromTreatmentPlan(ReassessmentSignal signal) {
        Set<UUID> ids = new LinkedHashSet<>();
        treatmentPlanRepository
                .findByIdAndProjectId(signal.entityId(), signal.projectId())
                .ifPresent(plan -> {
                    // Plan → linked register record → all assessment results under it.
                    assessmentRepository
                            .findByProjectIdAndRiskRegisterRecordIdOrderByCreatedAtDesc(
                                    signal.projectId(),
                                    plan.getRiskRegisterRecord().getId())
                            .forEach(r -> ids.add(r.getId()));
                    // Plan → optional scenario → all assessment results under that scenario.
                    if (plan.getRiskScenario() != null) {
                        assessmentRepository
                                .findByProjectIdAndRiskScenarioIdOrderByCreatedAtDesc(
                                        signal.projectId(),
                                        plan.getRiskScenario().getId())
                                .forEach(r -> ids.add(r.getId()));
                    }
                });
        return ids;
    }

    /** Asset → links pointing FROM the asset → scenarios / records / results / plans. */
    private Set<UUID> collectFromAsset(ReassessmentSignal signal) {
        Set<UUID> resultIds = new LinkedHashSet<>();
        for (var link : assetLinkRepository.findByAssetId(signal.entityId())) {
            switch (link.getTargetType()) {
                case RISK_SCENARIO -> addAllAssessmentsForScenario(
                        signal.projectId(), link.getTargetEntityId(), resultIds);
                case RISK_REGISTER_RECORD -> addAllAssessmentsForRecord(
                        signal.projectId(), link.getTargetEntityId(), resultIds);
                case RISK_ASSESSMENT_RESULT -> resultIds.add(link.getTargetEntityId());
                case TREATMENT_PLAN -> addAllAssessmentsForTreatmentPlan(
                        signal.projectId(), link.getTargetEntityId(), resultIds);
                default -> {
                    // intentional fall-through: other AssetLinkTargetType values are
                    // not in the bounded surface set the listener walks.
                }
            }
        }
        return resultIds;
    }

    /** Control → links pointing FROM the control → scenarios / records / results / plans. */
    private Set<UUID> collectFromControl(ReassessmentSignal signal) {
        Set<UUID> resultIds = new LinkedHashSet<>();
        for (var link : controlLinkRepository.findByControlId(signal.entityId())) {
            switch (link.getTargetType()) {
                case RISK_SCENARIO -> addAllAssessmentsForScenario(
                        signal.projectId(), link.getTargetEntityId(), resultIds);
                case RISK_REGISTER_RECORD -> addAllAssessmentsForRecord(
                        signal.projectId(), link.getTargetEntityId(), resultIds);
                case RISK_ASSESSMENT_RESULT -> resultIds.add(link.getTargetEntityId());
                case TREATMENT_PLAN -> addAllAssessmentsForTreatmentPlan(
                        signal.projectId(), link.getTargetEntityId(), resultIds);
                default -> {
                    // intentional fall-through (see collectFromAsset).
                }
            }
        }
        // RiskScenarioLink is the inverse direction (scenario → control); when a
        // control changes, scenarios that LINK to it as a mitigation become
        // re-assessment candidates. Follow that surface too.
        for (var link : riskScenarioLinkRepository.findByTargetTypeAndTargetEntityIdAndProjectId(
                RiskScenarioLinkTargetType.CONTROL, signal.entityId(), signal.projectId())) {
            addAllAssessmentsForScenario(
                    signal.projectId(), link.getRiskScenario().getId(), resultIds);
        }
        return resultIds;
    }

    /** KRI → linked register record + (optionally) scenario → assessment results. */
    private Set<UUID> collectFromKri(ReassessmentSignal signal) {
        Set<UUID> ids = new LinkedHashSet<>();
        keyRiskIndicatorRepository
                .findByIdAndProjectId(signal.entityId(), signal.projectId())
                .ifPresent(kri -> {
                    if (kri.getRiskRegisterRecord() != null) {
                        addAllAssessmentsForRecord(
                                signal.projectId(), kri.getRiskRegisterRecord().getId(), ids);
                    }
                    if (kri.getRiskScenario() != null) {
                        addAllAssessmentsForScenario(
                                signal.projectId(), kri.getRiskScenario().getId(), ids);
                    }
                });
        return ids;
    }

    private void addAllAssessmentsForScenario(UUID projectId, UUID scenarioId, Set<UUID> out) {
        if (scenarioId == null) {
            return;
        }
        assessmentRepository
                .findByProjectIdAndRiskScenarioIdOrderByCreatedAtDesc(projectId, scenarioId)
                .forEach(r -> out.add(r.getId()));
    }

    private void addAllAssessmentsForRecord(UUID projectId, UUID recordId, Set<UUID> out) {
        if (recordId == null) {
            return;
        }
        assessmentRepository
                .findByProjectIdAndRiskRegisterRecordIdOrderByCreatedAtDesc(projectId, recordId)
                .forEach(r -> out.add(r.getId()));
    }

    private void addAllAssessmentsForTreatmentPlan(UUID projectId, UUID planId, Set<UUID> out) {
        if (planId == null) {
            return;
        }
        treatmentPlanRepository.findByIdAndProjectId(planId, projectId).ifPresent(plan -> {
            addAllAssessmentsForRecord(projectId, plan.getRiskRegisterRecord().getId(), out);
            if (plan.getRiskScenario() != null) {
                addAllAssessmentsForScenario(projectId, plan.getRiskScenario().getId(), out);
            }
        });
    }

    private void markReassessmentRequired(Set<UUID> ids, ReassessmentSignal signal) {
        if (ids.isEmpty()) {
            return;
        }
        var now = Instant.now();
        int updated = 0;
        for (UUID id : ids) {
            var existing = assessmentRepository.findByIdAndProjectId(id, signal.projectId());
            if (existing.isEmpty()) {
                continue;
            }
            RiskAssessmentResult row = existing.get();
            row.setReassessmentRequiredAt(now);
            assessmentRepository.save(row);
            updated++;
        }
        if (updated > 0 && log.isInfoEnabled()) {
            // Low-cardinality structured log per the preflight observability rule —
            // entity ids and categories only; never raw field values, action items,
            // or metadata payloads.
            log.info(
                    "reassessment_required_marked: project_id={} source_type={} source_id={} category={} affected={}",
                    signal.projectId(),
                    signal.entityType(),
                    signal.entityId(),
                    signal.category(),
                    updated);
        }
    }
}

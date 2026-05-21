package com.keplerops.groundcontrol.domain.riskcontrol.service;

import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.model.MappingEvidenceRef;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-time feed service for GC-T003 C7 and C8.
 *
 * <p>C7: For a given {@link RiskAssessmentResult}, gathers the RiskControlMapping rows linked
 * to its scenario/record, then resolves each mapped control's latest as-of
 * {@link ControlEffectivenessAssessment} via the repository's as-of query.
 * {@code operatingEffectiveness} is the stable GC-I013 input field.
 *
 * <p>C8: Surfaces mapping-owned observations and evidence refs as structured provenance
 * inputs alongside the C7 effectiveness inputs — one unified feed read path.
 */
@Service
@Transactional(readOnly = true)
public class RiskControlMappingFeedService {

    private final RiskControlMappingRepository mappingRepository;
    private final RiskAssessmentResultRepository assessmentRepository;
    private final ControlEffectivenessAssessmentRepository effectivenessRepository;

    public RiskControlMappingFeedService(
            RiskControlMappingRepository mappingRepository,
            RiskAssessmentResultRepository assessmentRepository,
            ControlEffectivenessAssessmentRepository effectivenessRepository) {
        this.mappingRepository = mappingRepository;
        this.assessmentRepository = assessmentRepository;
        this.effectivenessRepository = effectivenessRepository;
    }

    /**
     * Feed for a risk assessment result: returns the combined C7 (effectiveness) + C8
     * (observations, evidence) inputs from all mappings linked to the same scenario/record.
     */
    public AssessmentFeedResult feedForAssessment(UUID projectId, UUID assessmentResultId) {
        var assessment = assessmentRepository
                .findByIdAndProjectId(assessmentResultId, projectId)
                .orElseThrow(() -> new com.keplerops.groundcontrol.domain.exception.NotFoundException(
                        "RiskAssessmentResult not found: " + assessmentResultId));

        // Determine as-of date from the assessment's createdAt
        var asOf = LocalDate.ofInstant(assessment.getCreatedAt(), ZoneOffset.UTC);

        // Gather all mappings for this scenario or record
        List<RiskControlMapping> mappings;
        if (assessment.getRiskScenario() != null) {
            mappings = mappingRepository.findByProjectIdAndRiskScenarioId(
                    projectId, assessment.getRiskScenario().getId());
        } else if (assessment.getRiskRegisterRecord() != null) {
            mappings = mappingRepository.findByProjectIdAndRiskRegisterRecordId(
                    projectId, assessment.getRiskRegisterRecord().getId());
        } else {
            return new AssessmentFeedResult(List.of(), List.of(), List.of());
        }

        // C7: Gather as-of effectiveness assessments from the repository
        // The repository returns rows ordered by controlId asc, assessedAt desc —
        // we pick the most recent per control (first entry per controlId group).
        var effectivenessRows =
                effectivenessRepository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        projectId, asOf);

        // Build a map: controlId -> latest ControlEffectivenessAssessment (as-of)
        Map<UUID, ControlEffectivenessAssessment> latestByControl = new LinkedHashMap<>();
        for (var row : effectivenessRows) {
            latestByControl.putIfAbsent(row.getControl().getId(), row);
        }

        // Resolve effectiveness inputs for each mapped control
        var c7Inputs = new ArrayList<ControlEffectivenessInput>();
        var c8Observations = new ArrayList<ObservationInput>();
        var c8Evidence = new ArrayList<MappingEvidenceRef>();

        for (var mapping : mappings) {
            UUID controlId = resolveControlId(mapping);
            if (controlId != null) {
                var eff = latestByControl.get(controlId);
                if (eff != null) {
                    c7Inputs.add(new ControlEffectivenessInput(
                            mapping.getId(),
                            controlId,
                            eff.getId(),
                            eff.getOperatingEffectiveness().name(),
                            eff.getDesignEffectiveness().name(),
                            eff.getAssessedAt()));
                }
            }

            // C8: observations anchored on this mapping
            for (var obs : mapping.getObservations()) {
                c8Observations.add(new ObservationInput(
                        mapping.getId(), obs.getId(), obs.getObservationKey(), obs.getObservationValue()));
            }

            // C8: evidence refs
            c8Evidence.addAll(mapping.getEvidenceRefs());
        }

        return new AssessmentFeedResult(c7Inputs, c8Observations, c8Evidence);
    }

    private UUID resolveControlId(RiskControlMapping mapping) {
        if (mapping.getControl() != null) {
            return mapping.getControl().getId();
        }
        if (mapping.getScopedImplementation() != null
                && mapping.getScopedImplementation().getControl() != null) {
            return mapping.getScopedImplementation().getControl().getId();
        }
        return null;
    }

    // ---- Value types ----

    public record ControlEffectivenessInput(
            UUID mappingId,
            UUID controlId,
            UUID assessmentId,
            String operatingEffectiveness,
            String designEffectiveness,
            java.time.LocalDate assessedAt) {}

    public record ObservationInput(
            UUID mappingId, UUID observationId, String observationKey, String observationValue) {}

    public record AssessmentFeedResult(
            List<ControlEffectivenessInput> effectivenessInputs,
            List<ObservationInput> observationInputs,
            List<MappingEvidenceRef> evidenceRefs) {}
}

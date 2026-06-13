package com.keplerops.groundcontrol.domain.evidencestate.service;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.model.ControlTest;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlTestRepository;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.evidencestate.service.EvidenceStateWorkspaceResult.EvidenceArtifactItem;
import com.keplerops.groundcontrol.domain.evidencestate.service.EvidenceStateWorkspaceResult.EvidenceFreshnessCounts;
import com.keplerops.groundcontrol.domain.evidencestate.service.EvidenceStateWorkspaceResult.ObservationItem;
import com.keplerops.groundcontrol.domain.evidencestate.service.EvidenceStateWorkspaceResult.ProvenanceSource;
import com.keplerops.groundcontrol.domain.evidencestate.service.EvidenceStateWorkspaceResult.WorkspaceAsset;
import com.keplerops.groundcontrol.domain.evidencestate.service.EvidenceStateWorkspaceResult.WorkspaceLink;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.findings.model.Finding;
import com.keplerops.groundcontrol.domain.findings.model.FindingLink;
import com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EvidenceStateWorkspaceService {

    private static final int PREVIEW_LIMIT = 80;

    private final EvidenceFreshnessAnalysisService freshnessAnalysisService;
    private final EvidenceArtifactRepository evidenceArtifactRepository;
    private final ObservationRepository observationRepository;
    private final OperationalAssetRepository operationalAssetRepository;
    private final ControlTestRepository controlTestRepository;
    private final ControlEffectivenessAssessmentRepository controlEffectivenessAssessmentRepository;
    private final RiskAssessmentResultRepository riskAssessmentResultRepository;
    private final FindingRepository findingRepository;
    private final FindingLinkRepository findingLinkRepository;

    @SuppressWarnings("java:S107")
    public EvidenceStateWorkspaceService(
            EvidenceFreshnessAnalysisService freshnessAnalysisService,
            EvidenceArtifactRepository evidenceArtifactRepository,
            ObservationRepository observationRepository,
            OperationalAssetRepository operationalAssetRepository,
            ControlTestRepository controlTestRepository,
            ControlEffectivenessAssessmentRepository controlEffectivenessAssessmentRepository,
            RiskAssessmentResultRepository riskAssessmentResultRepository,
            FindingRepository findingRepository,
            FindingLinkRepository findingLinkRepository) {
        this.freshnessAnalysisService = freshnessAnalysisService;
        this.evidenceArtifactRepository = evidenceArtifactRepository;
        this.observationRepository = observationRepository;
        this.operationalAssetRepository = operationalAssetRepository;
        this.controlTestRepository = controlTestRepository;
        this.controlEffectivenessAssessmentRepository = controlEffectivenessAssessmentRepository;
        this.riskAssessmentResultRepository = riskAssessmentResultRepository;
        this.findingRepository = findingRepository;
        this.findingLinkRepository = findingLinkRepository;
    }

    public EvidenceStateWorkspaceResult workspace(
            UUID projectId,
            Instant asOf,
            int freshnessWindowDays,
            boolean includeSuperseded,
            UUID assetId,
            UUID controlId) {
        if (freshnessWindowDays <= 0) {
            throw new DomainValidationException(
                    "freshnessWindowDays must be positive",
                    "validation_error",
                    Map.of("parameter", "freshnessWindowDays", "value", freshnessWindowDays));
        }
        var effectiveAsOf = asOf != null ? asOf : Instant.now();
        var freshness = freshnessAnalysisService.analyze(
                projectId, effectiveAsOf, freshnessWindowDays, includeSuperseded, assetId, controlId);
        var artifactRows = evidenceArtifactRepository.findByProjectIdAndDerivedAtLessThanEqualOrderByDerivedAtDesc(
                projectId, effectiveAsOf);
        var context = loadContext(projectId, effectiveAsOf, freshness, artifactRows);

        var artifacts = composeArtifacts(artifactRows, includeSuperseded, context);
        var observations = composeObservations(context);
        return new EvidenceStateWorkspaceResult(
                loadAssets(projectId),
                artifacts,
                observations,
                toCounts(freshness.counts()),
                List.copyOf(freshness.limitations()));
    }

    private WorkspaceContext loadContext(
            UUID projectId, Instant asOf, EvidenceFreshnessResult freshness, List<EvidenceArtifact> artifactRows) {
        var artifactFreshness = new LinkedHashMap<UUID, EvidenceFreshnessResult.EvidenceArtifactFreshnessItem>();
        for (var item : freshness.evidenceArtifacts()) {
            artifactFreshness.put(item.id(), item);
        }
        var observationFreshness = new LinkedHashMap<UUID, EvidenceFreshnessResult.ObservationFreshnessItem>();
        for (var item : freshness.observations()) {
            observationFreshness.put(item.id(), item);
        }
        var observationIds = new ArrayList<>(observationFreshness.keySet());
        var observations = observationIds.isEmpty()
                ? List.<Observation>of()
                : observationRepository.findAllByIdInAndProjectId(observationIds, projectId);
        var observationsById = mapObservations(observations);

        var asOfDate = asOf.atZone(ZoneOffset.UTC).toLocalDate();
        var controlTests =
                controlTestRepository.findByProjectIdAndTestDateLessThanEqualOrderByTestDateDesc(projectId, asOfDate);
        var assessments =
                controlEffectivenessAssessmentRepository
                        .findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                                projectId, asOfDate);
        var riskAssessments =
                riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId);
        var findings = findingRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        var findingLinks = findingLinkRepository.findByProjectId(projectId);

        return new WorkspaceContext(
                artifactFreshness,
                observationFreshness,
                mapArtifacts(artifactRows),
                observationsById,
                mapControlTests(controlTests),
                mapControlAssessments(assessments),
                riskAssessments,
                mapRiskAssessments(riskAssessments),
                mapFindings(findings),
                findingLinks);
    }

    private List<WorkspaceAsset> loadAssets(UUID projectId) {
        return operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId).stream()
                .map(asset -> new WorkspaceAsset(
                        asset.getId(),
                        asset.getUid(),
                        asset.getName(),
                        asset.getAssetType().name(),
                        asset.getAssetType().name().equals("BOUNDARY")))
                .toList();
    }

    private List<EvidenceArtifactItem> composeArtifacts(
            List<EvidenceArtifact> rows, boolean includeSuperseded, WorkspaceContext context) {
        return rows.stream()
                .filter(artifact -> hasFreshness(artifact, context))
                .filter(artifact -> includeSuperseded || artifact.getSupersededByArtifactId() == null)
                .map(artifact ->
                        composeArtifact(artifact, context.artifactFreshness().get(artifact.getId()), context))
                .toList();
    }

    private boolean hasFreshness(EvidenceArtifact artifact, WorkspaceContext context) {
        return context.artifactFreshness().containsKey(artifact.getId());
    }

    private EvidenceArtifactItem composeArtifact(
            EvidenceArtifact artifact,
            EvidenceFreshnessResult.EvidenceArtifactFreshnessItem freshness,
            WorkspaceContext context) {
        var sources = artifact.getSources() == null ? List.<EvidenceSourceRef>of() : artifact.getSources();
        var sourceDtos = new ArrayList<ProvenanceSource>();
        var assets = new LinkedHashSet<WorkspaceLink>();
        var controls = new LinkedHashSet<WorkspaceLink>();
        var assessments = new LinkedHashSet<WorkspaceLink>();
        var findings = new LinkedHashSet<WorkspaceLink>();

        for (var source : sources) {
            sourceDtos.add(toSource(source, context));
            collectSourceImpact(source, context, assets, controls, assessments, findings);
        }
        for (var link : context.findingLinks()) {
            if (link.getTargetType() == FindingLinkTargetType.EVIDENCE
                    && artifact.getId().equals(link.getTargetEntityId())) {
                findings.add(toFindingLink(link.getFinding()));
            }
        }

        return new EvidenceArtifactItem(
                artifact.getId(),
                artifact.getUid(),
                artifact.getTitle(),
                preview(artifact.getSummary()),
                artifact.getEvidenceType().name(),
                artifact.getDerivedAt(),
                freshness.ageDays(),
                freshness.state(),
                artifact.getSupersededByArtifactId(),
                artifact.getDerivedBy(),
                artifact.getAssuranceLevel() == null
                        ? null
                        : artifact.getAssuranceLevel().name(),
                artifact.getConfidence(),
                sourceDtos,
                List.copyOf(assets),
                List.copyOf(controls),
                List.copyOf(assessments),
                List.copyOf(findings));
    }

    private void collectSourceImpact(
            EvidenceSourceRef source,
            WorkspaceContext context,
            Set<WorkspaceLink> assets,
            Set<WorkspaceLink> controls,
            Set<WorkspaceLink> assessments,
            Set<WorkspaceLink> findings) {
        if (source.sourceEntityId() == null) {
            return;
        }
        var sourceId = source.sourceEntityId();
        switch (source.sourceKind()) {
            case OBSERVATION -> collectObservationImpact(sourceId, context, assets, assessments);
            case CONTROL_TEST -> collectControlTestImpact(sourceId, context, controls);
            case CONTROL_EFFECTIVENESS_ASSESSMENT -> collectControlAssessmentImpact(sourceId, context, controls);
            case RISK_ASSESSMENT_RESULT -> collectRiskAssessmentImpact(sourceId, context, assessments);
            case FINDING -> collectFindingImpact(sourceId, context, findings);
            case VERIFICATION_RESULT, ATTESTATION, EXTERNAL -> {
                // Provenance-only sources are represented in the source list without impact traversal.
            }
            default -> {
                // Future source kinds remain visible as provenance without widening impact traversal.
            }
        }
    }

    private void collectObservationImpact(
            UUID sourceId, WorkspaceContext context, Set<WorkspaceLink> assets, Set<WorkspaceLink> assessments) {
        var observation = context.observationsById().get(sourceId);
        if (observation == null) {
            return;
        }
        assets.add(toAssetLink(observation.getAsset()));
        collectRiskAssessmentsForObservation(observation, context, assessments);
    }

    private void collectRiskAssessmentsForObservation(
            Observation observation, WorkspaceContext context, Set<WorkspaceLink> assessments) {
        for (var assessment : context.riskAssessments()) {
            if (assessment.getObservations().contains(observation)) {
                assessments.add(toAssessmentLink(assessment));
            }
        }
    }

    private void collectControlTestImpact(UUID sourceId, WorkspaceContext context, Set<WorkspaceLink> controls) {
        var test = context.controlTestsById().get(sourceId);
        if (test != null) {
            controls.add(toControlLink(test.getControl()));
        }
    }

    private void collectControlAssessmentImpact(UUID sourceId, WorkspaceContext context, Set<WorkspaceLink> controls) {
        var assessment = context.controlAssessmentsById().get(sourceId);
        if (assessment != null) {
            controls.add(toControlLink(assessment.getControl()));
        }
    }

    private void collectRiskAssessmentImpact(UUID sourceId, WorkspaceContext context, Set<WorkspaceLink> assessments) {
        var assessment = context.riskAssessmentsById().get(sourceId);
        if (assessment != null) {
            assessments.add(toAssessmentLink(assessment));
        }
    }

    private void collectFindingImpact(UUID sourceId, WorkspaceContext context, Set<WorkspaceLink> findings) {
        var finding = context.findingsById().get(sourceId);
        if (finding != null) {
            findings.add(toFindingLink(finding));
        }
    }

    private List<ObservationItem> composeObservations(WorkspaceContext context) {
        var evidenceByObservation = evidenceArtifactsByObservation(context);
        var assessmentsByObservation = assessmentsByObservation(context);
        var findingsByObservation = findingsByObservation(context);
        var observations = new ArrayList<ObservationItem>();
        for (var entry : context.observationFreshness().entrySet()) {
            var observation = context.observationsById().get(entry.getKey());
            if (observation == null) {
                continue;
            }
            var freshness = entry.getValue();
            observations.add(new ObservationItem(
                    observation.getId(),
                    observation.getAsset().getId(),
                    observation.getAsset().getUid(),
                    observation.getCategory().name(),
                    observation.getObservationKey(),
                    preview(observation.getObservationValue()),
                    preview(observation.getSource()),
                    preview(observation.getEvidenceRef()),
                    observation.getObservedAt(),
                    observation.getExpiresAt(),
                    freshness.ageDays(),
                    freshness.state(),
                    observation.getConfidence(),
                    evidenceByObservation.getOrDefault(observation.getId(), List.of()),
                    assessmentsByObservation.getOrDefault(observation.getId(), List.of()),
                    findingsByObservation.getOrDefault(observation.getId(), List.of())));
        }
        return observations;
    }

    private Map<UUID, List<WorkspaceLink>> evidenceArtifactsByObservation(WorkspaceContext context) {
        var result = new LinkedHashMap<UUID, List<WorkspaceLink>>();
        for (var entry : context.artifactFreshness().entrySet()) {
            // Presence in the freshness map means this artifact matched current filters.
            var id = entry.getKey();
            var artifact = context.artifactsById().get(id);
            if (artifact == null) {
                continue;
            }
            var sources = artifact.getSources() == null ? List.<EvidenceSourceRef>of() : artifact.getSources();
            for (var source : sources) {
                if (source.sourceKind() == EvidenceSourceKind.OBSERVATION && source.sourceEntityId() != null) {
                    result.computeIfAbsent(source.sourceEntityId(), k -> new ArrayList<>())
                            .add(new WorkspaceLink(artifact.getId(), artifact.getUid(), artifact.getTitle(), null));
                }
            }
        }
        return result;
    }

    private Map<UUID, List<WorkspaceLink>> assessmentsByObservation(WorkspaceContext context) {
        var result = new LinkedHashMap<UUID, List<WorkspaceLink>>();
        for (var assessment : context.riskAssessments()) {
            for (var observation : assessment.getObservations()) {
                result.computeIfAbsent(observation.getId(), k -> new ArrayList<>())
                        .add(toAssessmentLink(assessment));
            }
        }
        return result;
    }

    private Map<UUID, List<WorkspaceLink>> findingsByObservation(WorkspaceContext context) {
        var result = new LinkedHashMap<UUID, List<WorkspaceLink>>();
        for (var link : context.findingLinks()) {
            if (link.getTargetType() == FindingLinkTargetType.OBSERVATION && link.getTargetEntityId() != null) {
                result.computeIfAbsent(link.getTargetEntityId(), k -> new ArrayList<>())
                        .add(toFindingLink(link.getFinding()));
            }
        }
        return result;
    }

    private ProvenanceSource toSource(EvidenceSourceRef source, WorkspaceContext context) {
        return new ProvenanceSource(
                source.sourceKind().name(),
                source.sourceEntityId(),
                source.sourceIdentifier(),
                source.role(),
                sourceLabel(source, context));
    }

    private String sourceLabel(EvidenceSourceRef source, WorkspaceContext context) {
        if (source.sourceEntityId() == null) {
            return source.sourceIdentifier();
        }
        return switch (source.sourceKind()) {
            case OBSERVATION -> {
                var observation = context.observationsById().get(source.sourceEntityId());
                yield observation == null
                        ? source.sourceEntityId().toString()
                        : observation.getAsset().getUid() + " " + observation.getObservationKey();
            }
            case CONTROL_TEST -> {
                var test = context.controlTestsById().get(source.sourceEntityId());
                yield test == null ? source.sourceEntityId().toString() : test.getUid();
            }
            case CONTROL_EFFECTIVENESS_ASSESSMENT -> {
                var assessment = context.controlAssessmentsById().get(source.sourceEntityId());
                yield assessment == null ? source.sourceEntityId().toString() : assessment.getUid();
            }
            case RISK_ASSESSMENT_RESULT -> {
                var assessment = context.riskAssessmentsById().get(source.sourceEntityId());
                yield assessment == null
                        ? source.sourceEntityId().toString()
                        : assessment.getRiskScenario().getUid();
            }
            case FINDING -> {
                var finding = context.findingsById().get(source.sourceEntityId());
                yield finding == null ? source.sourceEntityId().toString() : finding.getUid();
            }
            case VERIFICATION_RESULT -> source.sourceEntityId().toString();
            case ATTESTATION, EXTERNAL -> source.sourceIdentifier();
            default -> source.sourceEntityId().toString();
        };
    }

    private static WorkspaceLink toAssetLink(OperationalAsset asset) {
        return new WorkspaceLink(asset.getId(), asset.getUid(), asset.getName(), null);
    }

    private static WorkspaceLink toControlLink(com.keplerops.groundcontrol.domain.controls.model.Control control) {
        return new WorkspaceLink(control.getId(), control.getUid(), control.getTitle(), null);
    }

    private static WorkspaceLink toAssessmentLink(RiskAssessmentResult assessment) {
        var title = assessment.getMethodologyProfile() == null
                ? null
                : assessment.getMethodologyProfile().getName();
        return new WorkspaceLink(
                assessment.getId(), assessment.getRiskScenario().getUid(), title, null);
    }

    private static WorkspaceLink toFindingLink(Finding finding) {
        return new WorkspaceLink(finding.getId(), finding.getUid(), finding.getTitle(), null);
    }

    private static EvidenceFreshnessCounts toCounts(EvidenceFreshnessResult.EvidenceFreshnessCounts counts) {
        return new EvidenceFreshnessCounts(
                counts.fresh(), counts.stale(), counts.expired(), counts.superseded(), counts.currentlyValid());
    }

    private static Map<UUID, Observation> mapObservations(List<Observation> observations) {
        var result = new HashMap<UUID, Observation>();
        for (var observation : observations) {
            result.put(observation.getId(), observation);
        }
        return result;
    }

    private static Map<UUID, EvidenceArtifact> mapArtifacts(List<EvidenceArtifact> artifacts) {
        var result = new HashMap<UUID, EvidenceArtifact>();
        for (var artifact : artifacts) {
            result.put(artifact.getId(), artifact);
        }
        return result;
    }

    private static Map<UUID, ControlTest> mapControlTests(List<ControlTest> tests) {
        var result = new HashMap<UUID, ControlTest>();
        for (var test : tests) {
            result.put(test.getId(), test);
        }
        return result;
    }

    private static Map<UUID, ControlEffectivenessAssessment> mapControlAssessments(
            List<ControlEffectivenessAssessment> assessments) {
        var result = new HashMap<UUID, ControlEffectivenessAssessment>();
        for (var assessment : assessments) {
            result.put(assessment.getId(), assessment);
        }
        return result;
    }

    private static Map<UUID, RiskAssessmentResult> mapRiskAssessments(List<RiskAssessmentResult> assessments) {
        var result = new HashMap<UUID, RiskAssessmentResult>();
        for (var assessment : assessments) {
            result.put(assessment.getId(), assessment);
        }
        return result;
    }

    private static Map<UUID, Finding> mapFindings(List<Finding> findings) {
        var result = new HashMap<UUID, Finding>();
        for (var finding : findings) {
            result.put(finding.getId(), finding);
        }
        return result;
    }

    private static String preview(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.replaceAll("\\s+", " ").trim();
        if (trimmed.length() <= PREVIEW_LIMIT) {
            return trimmed;
        }
        return trimmed.substring(0, PREVIEW_LIMIT - 3) + "...";
    }

    private record WorkspaceContext(
            Map<UUID, EvidenceFreshnessResult.EvidenceArtifactFreshnessItem> artifactFreshness,
            Map<UUID, EvidenceFreshnessResult.ObservationFreshnessItem> observationFreshness,
            Map<UUID, EvidenceArtifact> artifactsById,
            Map<UUID, Observation> observationsById,
            Map<UUID, ControlTest> controlTestsById,
            Map<UUID, ControlEffectivenessAssessment> controlAssessmentsById,
            List<RiskAssessmentResult> riskAssessments,
            Map<UUID, RiskAssessmentResult> riskAssessmentsById,
            Map<UUID, Finding> findingsById,
            List<FindingLink> findingLinks) {}
}

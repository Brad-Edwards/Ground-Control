package com.keplerops.groundcontrol.domain.evidence.service;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the Evidence and State Explorer per GC-Q012.
 *
 * <p>This is a <strong>read-only composition</strong> over existing aggregates — no new JPA
 * aggregate, table, or migration is introduced. Freshness state, ageDays, and counts come entirely
 * from {@link EvidenceFreshnessAnalysisService#analyze} (which also validates the project, asset
 * filter, and freshness window), so the explorer never re-derives freshness logic. This service
 * enriches those freshness items with provenance (evidence source refs; observation
 * source/confidence/evidenceRef/value) and downstream impact: findings linked to an evidence artifact
 * or observation via {@link FindingLink}, plus the risk assessments that consumed an observation as an
 * evidence input. The artifact and observation listings are bounded by {@link #MAX_LISTING}; the
 * freshness counts always reflect the full set and any truncation is recorded in {@code limitations}.
 */
@Service
@Transactional(readOnly = true)
public class EvidenceExplorerService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceExplorerService.class);

    /**
     * Maximum number of artifacts / observations materialised into the response. The aggregate
     * freshness counts always reflect the full set; only the listing is bounded, and a truncation note
     * is added to {@code limitations} so the cap is never silent.
     */
    public static final int MAX_LISTING = 500;

    private final EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;
    private final EvidenceArtifactRepository evidenceArtifactRepository;
    private final ObservationRepository observationRepository;
    private final RiskAssessmentResultRepository riskAssessmentResultRepository;
    private final FindingRepository findingRepository;
    private final FindingLinkRepository findingLinkRepository;

    public EvidenceExplorerService(
            EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService,
            EvidenceArtifactRepository evidenceArtifactRepository,
            ObservationRepository observationRepository,
            RiskAssessmentResultRepository riskAssessmentResultRepository,
            FindingRepository findingRepository,
            FindingLinkRepository findingLinkRepository) {
        this.evidenceFreshnessAnalysisService = evidenceFreshnessAnalysisService;
        this.evidenceArtifactRepository = evidenceArtifactRepository;
        this.observationRepository = observationRepository;
        this.riskAssessmentResultRepository = riskAssessmentResultRepository;
        this.findingRepository = findingRepository;
        this.findingLinkRepository = findingLinkRepository;
    }

    /**
     * Assembles the explorer for a project.
     *
     * @param projectId           resolved project UUID (never null)
     * @param asOf                freshness reference instant; null means now
     * @param freshnessWindowDays must be positive (validated by the freshness analysis)
     * @param assetId             optional asset-scope filter (validated in-project by the freshness analysis)
     * @param evidenceType        optional evidence-artifact type narrowing (in-memory; observations unaffected)
     * @param includeSuperseded   whether superseded evidence artifacts are surfaced
     * @return composed explorer result
     */
    public EvidenceExplorerResult explore(
            UUID projectId,
            Instant asOf,
            int freshnessWindowDays,
            UUID assetId,
            EvidenceType evidenceType,
            boolean includeSuperseded) {

        EvidenceFreshnessResult freshness = evidenceFreshnessAnalysisService.analyze(
                projectId, asOf, freshnessWindowDays, includeSuperseded, assetId, null);

        Map<UUID, EvidenceArtifact> artifactsById =
                indexById(evidenceArtifactRepository.findByProjectIdOrderByDerivedAtDesc(projectId));
        Map<UUID, Observation> observationsById = indexObservations(observationRepository.findByProjectId(projectId));

        Map<UUID, Finding> findingsById =
                indexFindings(findingRepository.findByProjectIdOrderByCreatedAtDesc(projectId));
        List<FindingLink> findingLinks = findingLinkRepository.findByProjectId(projectId);
        Map<UUID, List<Finding>> findingsByEvidence =
                groupFindingsByTarget(findingLinks, FindingLinkTargetType.EVIDENCE, findingsById);
        Map<UUID, List<Finding>> findingsByObservation =
                groupFindingsByTarget(findingLinks, FindingLinkTargetType.OBSERVATION, findingsById);

        // Downstream assessment impact: which risk assessments consumed each observation as evidence.
        Map<UUID, List<RiskAssessmentResult>> assessmentsByObservation = groupAssessmentsByObservation(
                riskAssessmentResultRepository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId));

        List<EvidenceExplorerResult.ExplorerArtifact> artifacts =
                composeArtifacts(freshness, artifactsById, evidenceType, findingsByEvidence);
        List<EvidenceExplorerResult.ExplorerObservation> observations =
                composeObservations(freshness, observationsById, findingsByObservation, assessmentsByObservation);

        EvidenceExplorerResult.FreshnessCounts counts = new EvidenceExplorerResult.FreshnessCounts(
                freshness.counts().fresh(),
                freshness.counts().stale(),
                freshness.counts().expired(),
                freshness.counts().superseded(),
                freshness.counts().currentlyValid());

        List<String> limitations = new ArrayList<>(freshness.limitations());
        if (evidenceType != null) {
            limitations.add(
                    "evidenceType narrows the artifact listing only; the freshness counts reflect the full evidence set for the scope");
        }

        // Bound the response payload. The freshness counts above already reflect the full set; only the
        // listings are capped, and truncation is recorded explicitly (no silent caps).
        int totalArtifacts = artifacts.size();
        int totalObservations = observations.size();
        List<EvidenceExplorerResult.ExplorerArtifact> cappedArtifacts = cap(artifacts);
        List<EvidenceExplorerResult.ExplorerObservation> cappedObservations = cap(observations);
        if (totalArtifacts > MAX_LISTING) {
            limitations.add("evidence-artifact listing truncated to " + MAX_LISTING + " of " + totalArtifacts
                    + "; narrow by assetId or evidenceType to see the rest");
        }
        if (totalObservations > MAX_LISTING) {
            limitations.add("observation listing truncated to " + MAX_LISTING + " of " + totalObservations
                    + "; narrow by assetId to see the rest");
        }

        log.info(
                "evidence_explorer assembled: project={} artifacts={} observations={}",
                projectId,
                cappedArtifacts.size(),
                cappedObservations.size());

        return new EvidenceExplorerResult(cappedArtifacts, cappedObservations, counts, limitations);
    }

    private static <T> List<T> cap(List<T> items) {
        return items.size() <= MAX_LISTING ? items : new ArrayList<>(items.subList(0, MAX_LISTING));
    }

    private static List<EvidenceExplorerResult.ExplorerArtifact> composeArtifacts(
            EvidenceFreshnessResult freshness,
            Map<UUID, EvidenceArtifact> artifactsById,
            EvidenceType evidenceType,
            Map<UUID, List<Finding>> findingsByEvidence) {
        List<EvidenceExplorerResult.ExplorerArtifact> artifacts = new ArrayList<>();
        for (EvidenceFreshnessResult.EvidenceArtifactFreshnessItem item : freshness.evidenceArtifacts()) {
            EvidenceArtifact entity = artifactsById.get(item.id());
            if (entity == null || (evidenceType != null && entity.getEvidenceType() != evidenceType)) {
                continue;
            }
            artifacts.add(new EvidenceExplorerResult.ExplorerArtifact(
                    item.id(),
                    item.uid(),
                    item.title(),
                    entity.getEvidenceType(),
                    entity.getDerivationMethod(),
                    item.derivedAt(),
                    entity.getDerivedBy(),
                    entity.getAssuranceLevel(),
                    entity.getConfidence(),
                    item.supersededByArtifactId(),
                    item.state(),
                    item.ageDays(),
                    toSources(entity.getSources()),
                    toFindingRefs(findingsByEvidence.getOrDefault(item.id(), List.of()))));
        }
        return artifacts;
    }

    private static List<EvidenceExplorerResult.ExplorerObservation> composeObservations(
            EvidenceFreshnessResult freshness,
            Map<UUID, Observation> observationsById,
            Map<UUID, List<Finding>> findingsByObservation,
            Map<UUID, List<RiskAssessmentResult>> assessmentsByObservation) {
        List<EvidenceExplorerResult.ExplorerObservation> observations = new ArrayList<>();
        for (EvidenceFreshnessResult.ObservationFreshnessItem item : freshness.observations()) {
            Observation entity = observationsById.get(item.id());
            observations.add(new EvidenceExplorerResult.ExplorerObservation(
                    item.id(),
                    item.assetId(),
                    item.assetUid(),
                    item.category(),
                    item.observationKey(),
                    entity != null ? entity.getObservationValue() : null,
                    entity != null ? entity.getSource() : null,
                    entity != null ? entity.getConfidence() : null,
                    entity != null ? entity.getEvidenceRef() : null,
                    item.observedAt(),
                    item.expiresAt(),
                    item.state(),
                    item.ageDays(),
                    toFindingRefs(findingsByObservation.getOrDefault(item.id(), List.of())),
                    toAssessmentRefs(assessmentsByObservation.getOrDefault(item.id(), List.of()))));
        }
        return observations;
    }

    /** Reverse index: observation id -> risk assessments that included that observation in their evidence set. */
    private static Map<UUID, List<RiskAssessmentResult>> groupAssessmentsByObservation(
            List<RiskAssessmentResult> assessments) {
        Map<UUID, List<RiskAssessmentResult>> map = new LinkedHashMap<>();
        for (RiskAssessmentResult assessment : assessments) {
            for (Observation obs : assessment.getObservations()) {
                map.computeIfAbsent(obs.getId(), k -> new ArrayList<>()).add(assessment);
            }
        }
        return map;
    }

    private static List<EvidenceExplorerResult.ExplorerAssessmentRef> toAssessmentRefs(
            List<RiskAssessmentResult> assessments) {
        List<EvidenceExplorerResult.ExplorerAssessmentRef> result = new ArrayList<>(assessments.size());
        for (RiskAssessmentResult a : assessments) {
            result.add(new EvidenceExplorerResult.ExplorerAssessmentRef(
                    a.getId(),
                    a.getRiskScenario() != null ? a.getRiskScenario().getId() : null,
                    a.getApprovalState(),
                    a.getMethodologyProfile() != null
                            ? a.getMethodologyProfile().getName()
                            : null));
        }
        return result;
    }

    private static List<EvidenceExplorerResult.ExplorerSource> toSources(List<EvidenceSourceRef> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<EvidenceExplorerResult.ExplorerSource> result = new ArrayList<>(sources.size());
        for (EvidenceSourceRef ref : sources) {
            result.add(new EvidenceExplorerResult.ExplorerSource(
                    ref.sourceKind(), ref.sourceEntityId(), ref.sourceIdentifier(), ref.role()));
        }
        return result;
    }

    private static List<EvidenceExplorerResult.ExplorerFindingRef> toFindingRefs(List<Finding> findings) {
        List<EvidenceExplorerResult.ExplorerFindingRef> result = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            result.add(new EvidenceExplorerResult.ExplorerFindingRef(
                    f.getId(), f.getUid(), f.getTitle(), f.getSeverity(), f.getStatus()));
        }
        return result;
    }

    private static Map<UUID, EvidenceArtifact> indexById(List<EvidenceArtifact> artifacts) {
        Map<UUID, EvidenceArtifact> map = new HashMap<>();
        for (EvidenceArtifact a : artifacts) {
            map.put(a.getId(), a);
        }
        return map;
    }

    private static Map<UUID, Observation> indexObservations(List<Observation> observations) {
        Map<UUID, Observation> map = new HashMap<>();
        for (Observation o : observations) {
            map.put(o.getId(), o);
        }
        return map;
    }

    private static Map<UUID, Finding> indexFindings(List<Finding> findings) {
        Map<UUID, Finding> map = new HashMap<>();
        for (Finding f : findings) {
            map.put(f.getId(), f);
        }
        return map;
    }

    /**
     * Groups findings that link to a target of {@code targetType}, keyed by {@code targetEntityId}.
     * {@code link.getFinding().getId()} reads the lazy proxy id without initialising the finding; the
     * full record is resolved from {@code findingsById}.
     */
    private static Map<UUID, List<Finding>> groupFindingsByTarget(
            List<FindingLink> findingLinks, FindingLinkTargetType targetType, Map<UUID, Finding> findingsById) {
        Map<UUID, List<Finding>> map = new LinkedHashMap<>();
        for (FindingLink link : findingLinks) {
            if (link.getTargetType() != targetType || link.getTargetEntityId() == null) {
                continue;
            }
            Finding finding = findingsById.get(link.getFinding().getId());
            if (finding != null) {
                map.computeIfAbsent(link.getTargetEntityId(), k -> new ArrayList<>())
                        .add(finding);
            }
        }
        return map;
    }
}

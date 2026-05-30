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
 * source/confidence/evidenceRef/value) and downstream finding impact (findings linked to an evidence
 * artifact or observation via {@link FindingLink}).
 */
@Service
@Transactional(readOnly = true)
public class EvidenceExplorerService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceExplorerService.class);

    private final EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;
    private final EvidenceArtifactRepository evidenceArtifactRepository;
    private final ObservationRepository observationRepository;
    private final FindingRepository findingRepository;
    private final FindingLinkRepository findingLinkRepository;

    public EvidenceExplorerService(
            EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService,
            EvidenceArtifactRepository evidenceArtifactRepository,
            ObservationRepository observationRepository,
            FindingRepository findingRepository,
            FindingLinkRepository findingLinkRepository) {
        this.evidenceFreshnessAnalysisService = evidenceFreshnessAnalysisService;
        this.evidenceArtifactRepository = evidenceArtifactRepository;
        this.observationRepository = observationRepository;
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

        List<EvidenceExplorerResult.ExplorerArtifact> artifacts =
                composeArtifacts(freshness, artifactsById, evidenceType, findingsByEvidence);
        List<EvidenceExplorerResult.ExplorerObservation> observations =
                composeObservations(freshness, observationsById, findingsByObservation);

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

        log.info(
                "evidence_explorer assembled: project={} artifacts={} observations={}",
                projectId,
                artifacts.size(),
                observations.size());

        return new EvidenceExplorerResult(artifacts, observations, counts, limitations);
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
            Map<UUID, List<Finding>> findingsByObservation) {
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
                    toFindingRefs(findingsByObservation.getOrDefault(item.id(), List.of()))));
        }
        return observations;
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

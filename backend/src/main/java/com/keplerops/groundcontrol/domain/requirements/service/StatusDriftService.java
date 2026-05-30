package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.adrs.model.ArchitectureDecisionRecord;
import com.keplerops.groundcontrol.domain.adrs.repository.AdrRepository;
import com.keplerops.groundcontrol.domain.adrs.state.AdrStatus;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.ConfidenceLevel;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import com.keplerops.groundcontrol.domain.requirements.state.StatusDriftSignal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Detects {@code DRAFT} requirements that carry independent evidence of
 * implementation or design completion (lifecycle "status drift").
 *
 * <p>Read-only derived analysis per ADR-011 §9 and the ARCHITECTURE.md "Status
 * Drift Analysis" contract: it never creates traceability links, never transitions
 * requirements, never relaxes the {@code IMPLEMENTS}-only-on-{@code ACTIVE} rule,
 * never shells out to {@code gh}, and never scans the filesystem. Every evidence
 * signal is derived from data owned by the requirement's own project — its
 * canonical traceability links and accepted ADR records — so a project-scoped
 * request never reads project- or repo-unscoped global caches (e.g. the GitHub
 * issue/PR sync tables, which are not project-scoped).
 */
@Service
@Transactional(readOnly = true)
public class StatusDriftService {

    private static final Logger log = LoggerFactory.getLogger(StatusDriftService.class);

    private final RequirementRepository requirementRepository;
    private final TraceabilityLinkRepository traceabilityLinkRepository;
    private final AdrRepository adrRepository;

    public StatusDriftService(
            RequirementRepository requirementRepository,
            TraceabilityLinkRepository traceabilityLinkRepository,
            AdrRepository adrRepository) {
        this.requirementRepository = requirementRepository;
        this.traceabilityLinkRepository = traceabilityLinkRepository;
        this.adrRepository = adrRepository;
    }

    /**
     * Analyze a project for status drift.
     *
     * @param projectId the project (already resolved by the caller per ADR-016)
     * @param minimumConfidence only findings at or above this band are returned
     * @return findings sorted strongest-confidence-first, then UID ascending
     */
    public StatusDriftResult analyze(UUID projectId, ConfidenceLevel minimumConfidence) {
        var drafts = requirementRepository.findByProjectIdAndStatusAndArchivedAtIsNull(projectId, Status.DRAFT);
        if (drafts.isEmpty()) {
            return new StatusDriftResult(0, minimumConfidence, List.of());
        }

        var draftIds = drafts.stream().map(Requirement::getId).toList();
        Map<UUID, List<TraceabilityLink>> linksByRequirement = new HashMap<>();
        for (TraceabilityLink link : traceabilityLinkRepository.findByRequirementIdIn(draftIds)) {
            linksByRequirement
                    .computeIfAbsent(link.getRequirement().getId(), k -> new ArrayList<>())
                    .add(link);
        }

        // Preload the project's ADRs once (one query) rather than one lookup per ADR link.
        Map<String, ArchitectureDecisionRecord> adrsByUid = new HashMap<>();
        for (ArchitectureDecisionRecord adr : adrRepository.findByProjectIdOrderByDecisionDateDesc(projectId)) {
            adrsByUid.putIfAbsent(adr.getUid(), adr);
        }

        List<StatusDriftResult.Finding> findings = new ArrayList<>();
        for (Requirement requirement : drafts) {
            buildFinding(
                            requirement,
                            linksByRequirement.getOrDefault(requirement.getId(), List.of()),
                            adrsByUid,
                            minimumConfidence)
                    .ifPresent(findings::add);
        }

        findings.sort(Comparator.comparing(StatusDriftResult.Finding::confidence, Comparator.reverseOrder())
                .thenComparing(StatusDriftResult.Finding::uid));

        log.info(
                "status_drift_analyzed: project={} drafts={} flagged={} minConfidence={}",
                projectId,
                drafts.size(),
                findings.size(),
                minimumConfidence);
        return new StatusDriftResult(drafts.size(), minimumConfidence, findings);
    }

    // ------------------------------------------------------------------------
    // Evidence collection — link-based and project-scoped only
    // ------------------------------------------------------------------------

    private Optional<StatusDriftResult.Finding> buildFinding(
            Requirement requirement,
            List<TraceabilityLink> links,
            Map<String, ArchitectureDecisionRecord> adrsByUid,
            ConfidenceLevel minimumConfidence) {
        var evidence = collectEvidence(links, adrsByUid);
        if (evidence.isEmpty()) {
            return Optional.empty();
        }
        var confidence = strongestConfidence(evidence);
        if (!confidence.atLeast(minimumConfidence)) {
            return Optional.empty();
        }
        return Optional.of(new StatusDriftResult.Finding(
                requirement.getUid(), requirement.getTitle(), confidence, strongestSignal(evidence), evidence));
    }

    private List<StatusDriftResult.Evidence> collectEvidence(
            List<TraceabilityLink> links, Map<String, ArchitectureDecisionRecord> adrsByUid) {
        List<StatusDriftResult.Evidence> evidence = new ArrayList<>();
        for (TraceabilityLink link : links) {
            if (link.getLinkType() == LinkType.IMPLEMENTS) {
                evidence.add(linkEvidence(
                        StatusDriftSignal.IMPLEMENTS_LINK_ON_DRAFT, link, "IMPLEMENTS link on a DRAFT requirement"));
                continue;
            }
            classifyNonImplementsLink(link, adrsByUid).ifPresent(evidence::add);
        }
        return evidence;
    }

    private Optional<StatusDriftResult.Evidence> classifyNonImplementsLink(
            TraceabilityLink link, Map<String, ArchitectureDecisionRecord> adrsByUid) {
        return switch (link.getArtifactType()) {
            case ADR -> acceptedAdrDocumentsEvidence(link, adrsByUid);
            case GITHUB_ISSUE -> Optional.of(
                    linkEvidence(StatusDriftSignal.LINKED_GITHUB_ISSUE, link, "linked GitHub issue"));
            case PULL_REQUEST -> Optional.of(
                    linkEvidence(StatusDriftSignal.LINKED_PULL_REQUEST, link, "linked GitHub pull request"));
            case CODE_FILE, TEST, SPEC, PROOF -> Optional.of(linkEvidence(
                    StatusDriftSignal.LINKED_CODE_ARTIFACT,
                    link,
                    link.getLinkType().name() + " link to "
                            + link.getArtifactType().name()));
            case DOCUMENTATION, CONFIG, POLICY -> Optional.of(linkEvidence(
                    StatusDriftSignal.LINKED_DOC_ARTIFACT,
                    link,
                    link.getLinkType().name() + " link to "
                            + link.getArtifactType().name()));
            case RISK_SCENARIO, CONTROL, DECISION_RECORD -> Optional.empty();
        };
    }

    private static Optional<StatusDriftResult.Evidence> acceptedAdrDocumentsEvidence(
            TraceabilityLink link, Map<String, ArchitectureDecisionRecord> adrsByUid) {
        if (link.getLinkType() != LinkType.DOCUMENTS) {
            return Optional.empty();
        }
        var adr = adrsByUid.get(link.getArtifactIdentifier());
        if (adr == null || adr.getStatus() != AdrStatus.ACCEPTED) {
            return Optional.empty();
        }
        // Build evidence from the ADR record (authoritative title) rather than the link, which may
        // carry empty artifact title/url.
        return Optional.of(new StatusDriftResult.Evidence(
                StatusDriftSignal.ACCEPTED_ADR_DOCUMENTS_LINK,
                StatusDriftSignal.ACCEPTED_ADR_DOCUMENTS_LINK.defaultConfidence(),
                "ADR",
                adr.getUid(),
                nullSafe(adr.getTitle()),
                nullSafe(link.getArtifactUrl()),
                "DOCUMENTS link to ACCEPTED " + adr.getUid()));
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static StatusDriftResult.Evidence linkEvidence(
            StatusDriftSignal signal, TraceabilityLink link, String detail) {
        return new StatusDriftResult.Evidence(
                signal,
                signal.defaultConfidence(),
                link.getArtifactType().name(),
                link.getArtifactIdentifier(),
                nullSafe(link.getArtifactTitle()),
                nullSafe(link.getArtifactUrl()),
                detail);
    }

    private static ConfidenceLevel strongestConfidence(List<StatusDriftResult.Evidence> evidence) {
        ConfidenceLevel best = ConfidenceLevel.LOW;
        for (StatusDriftResult.Evidence e : evidence) {
            best = best.strongest(e.confidence());
        }
        return best;
    }

    private static StatusDriftSignal strongestSignal(List<StatusDriftResult.Evidence> evidence) {
        return evidence.stream()
                .map(StatusDriftResult.Evidence::signal)
                .min(Comparator.comparing(StatusDriftSignal::defaultConfidence, Comparator.reverseOrder())
                        .thenComparing(Comparator.naturalOrder()))
                .orElseThrow();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ProtocolAnswerProvenance;
import com.keplerops.groundcontrol.domain.research.model.ProtocolCoverageDisposition;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSectionKind;
import com.keplerops.groundcontrol.domain.research.model.ProtocolSourceRole;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.service.ProtocolPlanAggregate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GC-RSCH-F008 / GC-RSCH-F009 / ADR-083 — the protocol plan as later stages
 * consume it: the artifact/contract attempt it belongs to, the chosen method,
 * coverage dispositions for the ADR-080 contract's requirements/questions, and
 * method-specific output sections. Bounded metadata only — never source text.
 */
public record ProtocolPlanResponse(
        UUID id,
        UUID researchRunId,
        UUID methodologyRequirementsContractId,
        UUID artifactId,
        int attemptNo,
        String protocolSchemaVersion,
        String methodKey,
        String methodProfileVersion,
        Instant createdAt,
        List<CoverageResponse> coverages,
        List<SectionResponse> sections) {

    /** One coverage disposition for an ADR-080 contract entry. */
    public record CoverageResponse(
            UUID id,
            String contractEntryKey,
            ProtocolCoverageDisposition disposition,
            String answerSummary,
            ProtocolAnswerProvenance answerProvenance,
            String rationale,
            ResearchRunStage deferredToStage,
            String decisionReference) {}

    /** One method-specific output section. */
    public record SectionResponse(
            UUID id,
            String sectionKey,
            ProtocolSectionKind sectionKind,
            ProtocolSourceRole sourceRole,
            String contentSummary) {}

    public static ProtocolPlanResponse from(ProtocolPlanAggregate aggregate) {
        var plan = aggregate.plan();

        var coverages = aggregate.coverages().stream()
                .map(c -> new CoverageResponse(
                        c.getId(),
                        c.getContractEntryKey(),
                        c.getDisposition(),
                        c.getAnswerSummary(),
                        c.getAnswerProvenance(),
                        c.getRationale(),
                        c.getDeferredToStage(),
                        c.getDecisionReference()))
                .toList();

        var sections = aggregate.sections().stream()
                .map(s -> new SectionResponse(
                        s.getId(), s.getSectionKey(), s.getSectionKind(), s.getSourceRole(), s.getContentSummary()))
                .toList();

        return new ProtocolPlanResponse(
                plan.getId(),
                plan.getResearchRun().getId(),
                plan.getMethodologyRequirementsContract().getId(),
                plan.getArtifactId(),
                plan.getAttemptNo(),
                plan.getProtocolSchemaVersion(),
                plan.getMethodKey(),
                plan.getMethodProfileVersion(),
                plan.getCreatedAt(),
                coverages,
                sections);
    }
}

package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.RationaleEntryKind;
import com.keplerops.groundcontrol.domain.research.model.RationaleEvidenceBasis;
import com.keplerops.groundcontrol.domain.research.model.RationaleProvenance;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunRationaleEntry;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for {@link ResearchRunRationaleEntry} (GC-RSCH-N012, ADR-068).
 * DTOs (not the controller) name the domain enums (ArchUnit boundary).
 */
public record ResearchRunRationaleEntryResponse(
        UUID id,
        ResearchRunStage stage,
        ResearchArtifactType artifactType,
        UUID artifactId,
        Integer attemptNo,
        ResearchGatePoint gatePoint,
        RationaleEntryKind kind,
        RationaleEvidenceBasis evidenceBasis,
        RationaleProvenance provenance,
        String subjectKey,
        String rationaleSummary,
        String evidenceLocator,
        String confidenceSummary,
        String actor,
        Instant recordedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ResearchRunRationaleEntryResponse from(ResearchRunRationaleEntry e) {
        return new ResearchRunRationaleEntryResponse(
                e.getId(),
                e.getStage(),
                e.getArtifactType(),
                e.getArtifactId(),
                e.getAttemptNo(),
                e.getGatePoint(),
                e.getKind(),
                e.getEvidenceBasis(),
                e.getProvenance(),
                e.getSubjectKey(),
                e.getRationaleSummary(),
                e.getEvidenceLocator(),
                e.getConfidenceSummary(),
                e.getActor(),
                e.getRecordedAt(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}

package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.RationaleEntryKind;
import com.keplerops.groundcontrol.domain.research.model.RationaleEvidenceBasis;
import com.keplerops.groundcontrol.domain.research.model.RationaleProvenance;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import java.util.UUID;

/**
 * Append an immutable rationale-ledger entry (GC-RSCH-N012, ADR-068). {@code
 * rationaleSummary}, {@code subjectKey}, {@code evidenceLocator}, and {@code
 * confidenceSummary} are bounded summaries only. The recording actor is taken
 * from the authenticated server context (ADR-026), not this command.
 */
public record AddRationaleEntryCommand(
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
        String confidenceSummary) {}

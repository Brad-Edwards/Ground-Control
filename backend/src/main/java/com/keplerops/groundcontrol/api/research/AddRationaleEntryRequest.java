package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.RationaleEntryKind;
import com.keplerops.groundcontrol.domain.research.model.RationaleEvidenceBasis;
import com.keplerops.groundcontrol.domain.research.model.RationaleProvenance;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.service.AddRationaleEntryCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Append an immutable rationale-ledger entry (GC-RSCH-N012, ADR-068). The
 * recording actor is taken from the authenticated server context, not the
 * request body (ADR-026). Summaries are bounded — never raw prompts or prose.
 */
public record AddRationaleEntryRequest(
        @NotNull ResearchRunStage stage,
        ResearchArtifactType artifactType,
        UUID artifactId,
        Integer attemptNo,
        ResearchGatePoint gatePoint,
        @NotNull RationaleEntryKind kind,
        @NotNull RationaleEvidenceBasis evidenceBasis,
        @NotNull RationaleProvenance provenance,
        @NotNull @Size(max = 200) String subjectKey,
        @NotNull @Size(max = 2000) String rationaleSummary,
        @Size(max = 500) String evidenceLocator,
        @Size(max = 500) String confidenceSummary) {

    public AddRationaleEntryCommand toCommand() {
        return new AddRationaleEntryCommand(
                stage,
                artifactType,
                artifactId,
                attemptNo,
                gatePoint,
                kind,
                evidenceBasis,
                provenance,
                subjectKey,
                rationaleSummary,
                evidenceLocator,
                confidenceSummary);
    }
}

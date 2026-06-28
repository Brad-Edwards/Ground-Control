package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ProvenanceNodeKind;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.service.RecordProvenanceNodeCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Record (or rework) a provenance node (ADR-069 §2). Bounded references and
 * summaries only; the schema deliberately has no field for raw research content.
 * The recording actor is taken from the authenticated server context, not the
 * request body (ADR-026).
 */
public record ProvenanceNodeRequest(
        @NotNull ProvenanceNodeKind kind,
        @NotBlank @Size(max = 200) String subjectKey,
        ResearchRunStage stage,
        ResearchArtifactType artifactType,
        UUID artifactId,
        @Positive Integer attemptNo,
        @Size(max = 500) String locator,
        @Size(max = 128) String contentHash,
        @Size(max = 200) String externalIdentifier,
        @Size(max = 2000) String summary,
        @Size(max = 200) String toolName,
        @Size(max = 100) String toolVersion,
        @Size(max = 200) String sourceActionId,
        @Size(max = 200) String idempotencyKey) {

    public RecordProvenanceNodeCommand toCommand() {
        return new RecordProvenanceNodeCommand(
                kind,
                subjectKey,
                stage,
                artifactType,
                artifactId,
                attemptNo,
                locator,
                contentHash,
                externalIdentifier,
                summary,
                toolName,
                toolVersion,
                sourceActionId,
                idempotencyKey);
    }
}

package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ProvenanceNodeKind;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import java.util.UUID;

/**
 * Record (or rework) a provenance node (ADR-069 §2). Bounded references and
 * summaries only — no raw research content. The recording actor is taken from
 * the authenticated server context, not this command.
 */
public record RecordProvenanceNodeCommand(
        ProvenanceNodeKind kind,
        String subjectKey,
        ResearchRunStage stage,
        ResearchArtifactType artifactType,
        UUID artifactId,
        Integer attemptNo,
        String locator,
        String contentHash,
        String externalIdentifier,
        String summary,
        String toolName,
        String toolVersion,
        String sourceActionId,
        String idempotencyKey) {}

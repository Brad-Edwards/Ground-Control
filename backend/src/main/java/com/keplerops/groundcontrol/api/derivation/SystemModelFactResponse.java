package com.keplerops.groundcontrol.api.derivation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.derivation.model.SystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SystemModelFactResponse(
        UUID id,
        UUID derivationRunId,
        String projectIdentifier,
        SystemModelFactKind factKind,
        String schemaVersion,
        String factKey,
        String label,
        String summary,
        String sourcePath,
        Map<String, Object> payload,
        ProvenanceResponse provenance,
        Instant createdAt,
        Instant updatedAt) {

    public static SystemModelFactResponse from(SystemModelFact fact) {
        return new SystemModelFactResponse(
                fact.getId(),
                fact.getDerivationRun().getId(),
                fact.getProject().getIdentifier(),
                fact.getFactKind(),
                fact.getSchemaVersion(),
                fact.getFactKey(),
                fact.getLabel(),
                fact.getSummary(),
                fact.getSourcePath(),
                fact.getPayload(),
                new ProvenanceResponse(
                        fact.getAdapterId(),
                        fact.getToolName(),
                        fact.getToolVersion(),
                        fact.getRulesetName(),
                        fact.getRulesetVersion(),
                        fact.getCommitSha(),
                        fact.getDerivedAt()),
                fact.getCreatedAt(),
                fact.getUpdatedAt());
    }

    public record ProvenanceResponse(
            String adapterId,
            String toolName,
            String toolVersion,
            String rulesetName,
            String rulesetVersion,
            String commitSha,
            Instant derivedAt) {}
}

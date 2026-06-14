package com.keplerops.groundcontrol.api.requirements;

import com.keplerops.groundcontrol.domain.requirements.service.RequirementRevision;
import java.time.Instant;
import java.util.Map;

public record RequirementHistoryResponse(
        int revisionNumber,
        String revisionType,
        Instant timestamp,
        String actor,
        String reason,
        RequirementResponse snapshot,
        Map<String, FieldChangeResponse> changes,
        boolean truncated) {

    public static RequirementHistoryResponse from(RequirementRevision revision, boolean expand) {
        var responses = AuditDiffTruncation.toResponses(revision.changes(), expand);
        boolean truncated = responses.values().stream().anyMatch(FieldChangeResponse::truncated);
        return new RequirementHistoryResponse(
                revision.revisionNumber(),
                revision.revisionType(),
                revision.timestamp(),
                revision.actor(),
                revision.reason(),
                RequirementResponse.from(revision.entity()),
                responses,
                truncated);
    }
}

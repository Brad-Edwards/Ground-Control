package com.keplerops.groundcontrol.api.requirements;

import com.keplerops.groundcontrol.domain.requirements.service.TimelineEntry;
import com.keplerops.groundcontrol.domain.requirements.state.ChangeCategory;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TimelineEntryResponse(
        int revisionNumber,
        String revisionType,
        Instant timestamp,
        String actor,
        String reason,
        ChangeCategory changeCategory,
        UUID entityId,
        Map<String, Object> snapshot,
        Map<String, FieldChangeResponse> changes,
        boolean truncated) {

    /**
     * Backward-compatible mapping that preserves full (untruncated) field values. Used by the
     * project-wide audit timeline, which is outside the per-requirement truncation feature (#1107).
     * Truncation-aware callers use {@link #from(TimelineEntry, boolean)}.
     */
    public static TimelineEntryResponse from(TimelineEntry entry) {
        return from(entry, true);
    }

    public static TimelineEntryResponse from(TimelineEntry entry, boolean expand) {
        var responses = AuditDiffTruncation.toResponses(entry.changes(), expand);
        boolean truncated = responses.values().stream().anyMatch(FieldChangeResponse::truncated);
        return new TimelineEntryResponse(
                entry.revisionNumber(),
                entry.revisionType(),
                entry.timestamp(),
                entry.actor(),
                entry.reason(),
                entry.changeCategory(),
                entry.entityId(),
                entry.snapshot(),
                responses,
                truncated);
    }
}

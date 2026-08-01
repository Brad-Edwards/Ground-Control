package com.keplerops.groundcontrol.domain.requirements.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Stateless helpers split out of {@link AuditService} under issue #1467
 * for the 500-LOC limit (docs/CODING_STANDARDS.md).
 *
 * Every method here touches no instance state, so it is static and the
 * original keeps a static import for each -- call sites are unchanged.
 */
final class AuditServiceSupport {

    private AuditServiceSupport() {}

    static List<TimelineEntry> filterAndPaginate(
            List<TimelineEntry> entries, String actor, Instant from, Instant to, int limit, int offset) {
        var stream = entries.stream();
        if (actor != null && !actor.isBlank()) {
            stream = stream.filter(e -> actor.equals(e.actor()));
        }
        if (from != null) {
            stream = stream.filter(e -> !e.timestamp().isBefore(from));
        }
        if (to != null) {
            stream = stream.filter(e -> !e.timestamp().isAfter(to));
        }

        return stream.sorted(Comparator.comparing(TimelineEntry::timestamp).reversed())
                .skip(offset)
                .limit(limit)
                .toList();
    }
}

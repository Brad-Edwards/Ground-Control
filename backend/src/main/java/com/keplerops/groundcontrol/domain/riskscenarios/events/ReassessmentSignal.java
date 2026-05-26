package com.keplerops.groundcontrol.domain.riskscenarios.events;

import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerCategory;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable payload shared by every reassessment-triggering event
 * (GC-T004 / C8, issue #863). Not a JPA entity — events outlive the
 * transaction that wrote them only until the synchronous listener runs.
 *
 * <p>{@code changedFields} is the set of field names whose value moved
 * in this mutation (drives the publishers' tracked-field set without
 * forcing a new event schema per added field). {@code oldValues} and
 * {@code newValues} are keyed by the same field names so the listener
 * or future consumers can inspect what moved.
 */
public record ReassessmentSignal(
        UUID projectId,
        ReassessmentTriggerCategory category,
        ReassessmentSourceEntityType entityType,
        UUID entityId,
        Set<String> changedFields,
        Map<String, Object> oldValues,
        Map<String, Object> newValues,
        Instant occurredAt) {}

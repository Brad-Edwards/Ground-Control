package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Converts audited entities to flat snapshot maps for timeline diff computation. */
public final class SnapshotMapper {

    private SnapshotMapper() {}

    public static Map<String, Object> fromRequirement(Requirement r) {
        var map = new LinkedHashMap<String, Object>();
        map.put("uid", r.getUid());
        map.put("title", r.getTitle());
        map.put("statement", r.getStatement());
        map.put("rationale", r.getRationale());
        map.put(
                "requirementType",
                r.getRequirementType() != null ? r.getRequirementType().name() : null);
        map.put("priority", r.getPriority() != null ? r.getPriority().name() : null);
        map.put("status", r.getStatus() != null ? r.getStatus().name() : null);
        map.put("wave", r.getWave());
        return map;
    }

    public static Map<String, Object> fromRelation(RequirementRelation r) {
        var map = new LinkedHashMap<String, Object>();
        map.put("sourceId", r.getSource() != null ? r.getSource().getId().toString() : null);
        map.put("targetId", r.getTarget() != null ? r.getTarget().getId().toString() : null);
        map.put(
                "relationType",
                r.getRelationType() != null ? r.getRelationType().name() : null);
        map.put("description", r.getDescription());
        return map;
    }

    public static Map<String, Object> fromTraceabilityLink(TraceabilityLink t) {
        var map = new LinkedHashMap<String, Object>();
        map.put(
                "artifactType",
                t.getArtifactType() != null ? t.getArtifactType().name() : null);
        map.put("artifactIdentifier", t.getArtifactIdentifier());
        map.put("artifactUrl", t.getArtifactUrl());
        map.put("artifactTitle", t.getArtifactTitle());
        map.put("linkType", t.getLinkType() != null ? t.getLinkType().name() : null);
        map.put("syncStatus", t.getSyncStatus() != null ? t.getSyncStatus().name() : null);
        return map;
    }

    public static Map<String, FieldChange> computeDiff(Map<String, Object> previous, Map<String, Object> current) {
        var diff = new LinkedHashMap<String, FieldChange>();
        for (var entry : current.entrySet()) {
            var oldVal = previous.get(entry.getKey());
            if (!Objects.equals(oldVal, entry.getValue())) {
                diff.put(entry.getKey(), new FieldChange(oldVal, entry.getValue()));
            }
        }
        return diff;
    }

    /**
     * Computes an "addition" diff for a snapshot that has no prior state — every field
     * is represented as {@code (null, newValue)}.
     */
    public static Map<String, FieldChange> computeAdditionDiff(Map<String, Object> snapshot) {
        return computeDiff(Map.of(), snapshot);
    }

    /**
     * Computes a "removal" diff for a snapshot that no longer exists — every non-null
     * field is represented as {@code (oldValue, null)}.
     */
    public static Map<String, FieldChange> computeRemovalDiff(Map<String, Object> previousSnapshot) {
        var diff = new LinkedHashMap<String, FieldChange>();
        for (var entry : previousSnapshot.entrySet()) {
            if (entry.getValue() != null) {
                diff.put(entry.getKey(), new FieldChange(entry.getValue(), null));
            }
        }
        return diff;
    }

    /** Computes added/removed/modified relation changes between two point-in-time snapshot maps. */
    public static List<RelationChange> computeRelationChanges(
            Map<UUID, Map<String, Object>> fromMap, Map<UUID, Map<String, Object>> toMap) {
        return computeEntityChanges(fromMap, toMap, RelationChange::new);
    }

    /** Computes added/removed/modified traceability link changes between two point-in-time snapshot maps. */
    public static List<TraceabilityLinkChange> computeTraceabilityLinkChanges(
            Map<UUID, Map<String, Object>> fromMap, Map<UUID, Map<String, Object>> toMap) {
        return computeEntityChanges(fromMap, toMap, TraceabilityLinkChange::new);
    }

    @FunctionalInterface
    interface EntityChangeFactory<T> {
        T create(UUID id, ChangeType changeType, Map<String, Object> snapshot, Map<String, FieldChange> fieldChanges);
    }

    static <T> List<T> computeEntityChanges(
            Map<UUID, Map<String, Object>> fromMap,
            Map<UUID, Map<String, Object>> toMap,
            EntityChangeFactory<T> factory) {
        var changes = new ArrayList<T>();

        for (var entry : toMap.entrySet()) {
            var fromSnapshot = fromMap.get(entry.getKey());
            if (fromSnapshot == null) {
                changes.add(factory.create(entry.getKey(), ChangeType.ADDED, entry.getValue(), Map.of()));
            } else {
                var fieldChanges = computeDiff(fromSnapshot, entry.getValue());
                if (!fieldChanges.isEmpty()) {
                    changes.add(factory.create(entry.getKey(), ChangeType.MODIFIED, entry.getValue(), fieldChanges));
                }
            }
        }

        for (var entry : fromMap.entrySet()) {
            if (!toMap.containsKey(entry.getKey())) {
                changes.add(factory.create(entry.getKey(), ChangeType.REMOVED, entry.getValue(), Map.of()));
            }
        }

        return changes;
    }
}

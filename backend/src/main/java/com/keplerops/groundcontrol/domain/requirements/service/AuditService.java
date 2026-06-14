package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.audit.GroundControlRevisionEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.ChangeCategory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuditService {

    private final RequirementRepository requirementRepository;
    private final RequirementRelationRepository relationRepository;
    private final TraceabilityLinkRepository traceabilityLinkRepository;
    private final EntityManager entityManager;

    public AuditService(
            RequirementRepository requirementRepository,
            RequirementRelationRepository relationRepository,
            TraceabilityLinkRepository traceabilityLinkRepository,
            EntityManager entityManager) {
        this.requirementRepository = requirementRepository;
        this.relationRepository = relationRepository;
        this.traceabilityLinkRepository = traceabilityLinkRepository;
        this.entityManager = entityManager;
    }

    public List<RequirementRevision> getRequirementHistory(UUID id) {
        if (!requirementRepository.existsById(id)) {
            throw new NotFoundException("Requirement not found: " + id);
        }

        var auditReader = AuditReaderFactory.get(entityManager);

        @SuppressWarnings("unchecked")
        List<Object[]> results = auditReader
                .createQuery()
                .forRevisionsOfEntity(Requirement.class, false, true)
                .add(AuditEntity.id().eq(id))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        var revisions = new ArrayList<RequirementRevision>();
        Map<String, Object> previous = null;

        for (var row : results) {
            var entity = (Requirement) row[0];
            var revInfo = (GroundControlRevisionEntity) row[1];
            var revType = (RevisionType) row[2];
            var snapshot = SnapshotMapper.fromRequirement(entity);

            Map<String, FieldChange> changes;
            if (revType == RevisionType.ADD) {
                changes = SnapshotMapper.computeAdditionDiff(snapshot);
            } else if (revType == RevisionType.DEL) {
                changes = previous != null ? SnapshotMapper.computeRemovalDiff(previous) : Map.of();
            } else {
                changes = previous != null ? SnapshotMapper.computeDiff(previous, snapshot) : Map.of();
            }

            revisions.add(new RequirementRevision(
                    revInfo.getId(),
                    java.time.Instant.ofEpochMilli(revInfo.getTimestamp()),
                    revType.name(),
                    revInfo.getActor(),
                    revInfo.getReason(),
                    entity,
                    changes));

            if (revType != RevisionType.DEL) {
                previous = snapshot;
            }
        }

        return revisions;
    }

    public List<RelationRevision> getRelationHistory(UUID requirementId, UUID relationId) {
        var relation = relationRepository
                .findById(relationId)
                .orElseThrow(() -> new NotFoundException("Relation not found: " + relationId));
        if (!requirementId.equals(relation.getSource().getId())
                && !requirementId.equals(relation.getTarget().getId())) {
            throw new NotFoundException("Relation not found: " + relationId);
        }

        var auditReader = AuditReaderFactory.get(entityManager);

        @SuppressWarnings("unchecked")
        List<Object[]> results = auditReader
                .createQuery()
                .forRevisionsOfEntity(RequirementRelation.class, false, true)
                .add(AuditEntity.id().eq(relationId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        return results.stream()
                .map(row -> {
                    var entity = (RequirementRelation) row[0];
                    var revInfo = (GroundControlRevisionEntity) row[1];
                    var revType = (RevisionType) row[2];
                    return new RelationRevision(
                            revInfo.getId(),
                            java.time.Instant.ofEpochMilli(revInfo.getTimestamp()),
                            revType.name(),
                            revInfo.getActor(),
                            revInfo.getReason(),
                            entity);
                })
                .toList();
    }

    public List<RecentChange> getRecentRequirementChanges(Set<UUID> requirementIds, int limit) {
        if (requirementIds.isEmpty()) {
            return List.of();
        }

        var auditReader = AuditReaderFactory.get(entityManager);

        @SuppressWarnings("unchecked")
        List<Object[]> results = auditReader
                .createQuery()
                .forRevisionsOfEntity(Requirement.class, false, true)
                .add(AuditEntity.id().in(requirementIds))
                .addOrder(AuditEntity.revisionNumber().desc())
                .setMaxResults(limit)
                .getResultList();

        return results.stream()
                .map(row -> {
                    var entity = (Requirement) row[0];
                    var revInfo = (GroundControlRevisionEntity) row[1];
                    var revType = (RevisionType) row[2];
                    return new RecentChange(
                            entity.getUid(),
                            entity.getTitle(),
                            revType.name(),
                            java.time.Instant.ofEpochMilli(revInfo.getTimestamp()),
                            revInfo.getActor(),
                            revInfo.getReason());
                })
                .toList();
    }

    public List<TraceabilityLinkRevision> getTraceabilityLinkHistory(UUID requirementId, UUID linkId) {
        var link = traceabilityLinkRepository
                .findById(linkId)
                .orElseThrow(() -> new NotFoundException("Traceability link not found: " + linkId));
        if (!requirementId.equals(link.getRequirement().getId())) {
            throw new NotFoundException("Traceability link not found: " + linkId);
        }

        var auditReader = AuditReaderFactory.get(entityManager);

        @SuppressWarnings("unchecked")
        List<Object[]> results = auditReader
                .createQuery()
                .forRevisionsOfEntity(TraceabilityLink.class, false, true)
                .add(AuditEntity.id().eq(linkId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        return results.stream()
                .map(row -> {
                    var entity = (TraceabilityLink) row[0];
                    var revInfo = (GroundControlRevisionEntity) row[1];
                    var revType = (RevisionType) row[2];
                    return new TraceabilityLinkRevision(
                            revInfo.getId(),
                            java.time.Instant.ofEpochMilli(revInfo.getTimestamp()),
                            revType.name(),
                            revInfo.getActor(),
                            revInfo.getReason(),
                            entity);
                })
                .toList();
    }

    /**
     * Returns a structured diff between two revisions of a single requirement, including per-field
     * changes, added/removed/modified relations, and added/removed/modified traceability links.
     */
    public RequirementVersionDiff getRequirementDiff(UUID id, int fromRevision, int toRevision) {
        if (!requirementRepository.existsById(id)) {
            throw new NotFoundException("Requirement not found: " + id);
        }
        if (fromRevision >= toRevision) {
            throw new DomainValidationException("fromRevision must be less than toRevision");
        }

        var auditReader = AuditReaderFactory.get(entityManager);

        var fromEntity = auditReader.find(Requirement.class, id, fromRevision);
        if (fromEntity == null) {
            throw new DomainValidationException("Requirement does not exist at revision " + fromRevision);
        }
        var toEntity = auditReader.find(Requirement.class, id, toRevision);
        if (toEntity == null) {
            throw new DomainValidationException("Requirement does not exist at revision " + toRevision);
        }

        var fieldChanges = SnapshotMapper.computeDiff(
                SnapshotMapper.fromRequirement(fromEntity), SnapshotMapper.fromRequirement(toEntity));

        var fromRelations = getRelationSnapshotsAtRevision(id, fromRevision);
        var toRelations = getRelationSnapshotsAtRevision(id, toRevision);
        var relationChanges = SnapshotMapper.computeRelationChanges(fromRelations, toRelations);

        var fromLinks = getTraceabilityLinkSnapshotsAtRevision(id, fromRevision);
        var toLinks = getTraceabilityLinkSnapshotsAtRevision(id, toRevision);
        var linkChanges = SnapshotMapper.computeTraceabilityLinkChanges(fromLinks, toLinks);

        return new RequirementVersionDiff(id, fromRevision, toRevision, fieldChanges, relationChanges, linkChanges);
    }

    Map<UUID, Map<String, Object>> getRelationSnapshotsAtRevision(UUID requirementId, int revision) {
        var auditReader = AuditReaderFactory.get(entityManager);

        @SuppressWarnings("unchecked")
        List<Object[]> results = auditReader
                .createQuery()
                .forRevisionsOfEntity(RequirementRelation.class, false, true)
                .add(AuditEntity.revisionNumber().le(revision))
                .add(AuditEntity.disjunction()
                        .add(AuditEntity.relatedId("source").eq(requirementId))
                        .add(AuditEntity.relatedId("target").eq(requirementId)))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        return replayToAliveSnapshots(
                results,
                row -> {
                    var entity = (RequirementRelation) row[0];
                    return Map.entry(entity.getId(), SnapshotMapper.fromRelation(entity));
                },
                row -> ((RevisionType) row[2]));
    }

    Map<UUID, Map<String, Object>> getTraceabilityLinkSnapshotsAtRevision(UUID requirementId, int revision) {
        var auditReader = AuditReaderFactory.get(entityManager);

        @SuppressWarnings("unchecked")
        List<Object[]> results = auditReader
                .createQuery()
                .forRevisionsOfEntity(TraceabilityLink.class, false, true)
                .add(AuditEntity.revisionNumber().le(revision))
                .add(AuditEntity.relatedId("requirement").eq(requirementId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        return replayToAliveSnapshots(
                results,
                row -> {
                    var entity = (TraceabilityLink) row[0];
                    return Map.entry(entity.getId(), SnapshotMapper.fromTraceabilityLink(entity));
                },
                row -> ((RevisionType) row[2]));
    }

    Map<UUID, Map<String, Object>> replayToAliveSnapshots(
            List<Object[]> results,
            java.util.function.Function<Object[], Map.Entry<UUID, Map<String, Object>>> snapshotExtractor,
            java.util.function.Function<Object[], RevisionType> revTypeExtractor) {
        var alive = new HashMap<UUID, Map<String, Object>>();
        for (Object[] row : results) {
            var entry = snapshotExtractor.apply(row);
            var revType = revTypeExtractor.apply(row);
            if (revType == RevisionType.DEL) {
                alive.remove(entry.getKey());
            } else {
                alive.put(entry.getKey(), entry.getValue());
            }
        }
        return alive;
    }

    /**
     * Returns a unified, chronologically-sorted audit timeline for a requirement,
     * including requirement field changes, relation changes, and traceability link changes.
     */
    public List<TimelineEntry> getRequirementTimeline(
            UUID id, ChangeCategory changeCategory, String actor, Instant from, Instant to, int limit, int offset) {
        if (!requirementRepository.existsById(id)) {
            throw new NotFoundException("Requirement not found: " + id);
        }

        var entries = new ArrayList<TimelineEntry>();

        if (changeCategory == null || changeCategory == ChangeCategory.REQUIREMENT) {
            entries.addAll(buildRequirementEntries(id));
        }
        if (changeCategory == null || changeCategory == ChangeCategory.RELATION) {
            entries.addAll(buildRelationEntries(id));
        }
        if (changeCategory == null || changeCategory == ChangeCategory.TRACEABILITY_LINK) {
            entries.addAll(buildTraceabilityLinkEntries(id));
        }

        return filterAndPaginate(entries, actor, from, to, limit, offset);
    }

    /**
     * Returns a unified, chronologically-sorted audit timeline across all requirements in a project.
     *
     * <p>TODO: This loads all revisions into memory then filters/paginates. Move to DB-level
     * pagination (e.g. a native query joining revinfo with audit tables) before beta to avoid
     * O(requirements * revisions) memory usage on large projects.
     */
    public List<TimelineEntry> getProjectTimeline(
            UUID projectId,
            ChangeCategory changeCategory,
            String actor,
            Instant from,
            Instant to,
            int limit,
            int offset) {
        var requirements = requirementRepository.findByProjectId(projectId);
        var entries = new ArrayList<TimelineEntry>();

        for (var requirement : requirements) {
            var reqId = requirement.getId();
            if (changeCategory == null || changeCategory == ChangeCategory.REQUIREMENT) {
                entries.addAll(buildRequirementEntries(reqId));
            }
            if (changeCategory == null || changeCategory == ChangeCategory.RELATION) {
                entries.addAll(buildRelationEntries(reqId));
            }
            if (changeCategory == null || changeCategory == ChangeCategory.TRACEABILITY_LINK) {
                entries.addAll(buildTraceabilityLinkEntries(reqId));
            }
        }

        return filterAndPaginate(entries, actor, from, to, limit, offset);
    }

    private List<TimelineEntry> filterAndPaginate(
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

    private List<TimelineEntry> buildRequirementEntries(UUID requirementId) {
        var auditReader = AuditReaderFactory.get(entityManager);

        @SuppressWarnings("unchecked")
        List<Object[]> results = auditReader
                .createQuery()
                .forRevisionsOfEntity(Requirement.class, false, true)
                .add(AuditEntity.id().eq(requirementId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        var entries = new ArrayList<TimelineEntry>();
        Map<String, Object> previousSnapshot = null;

        for (Object[] row : results) {
            var entity = (Requirement) row[0];
            var revInfo = (GroundControlRevisionEntity) row[1];
            var revType = (RevisionType) row[2];

            var snapshot = SnapshotMapper.fromRequirement(entity);
            Map<String, FieldChange> changes;
            if (revType == RevisionType.ADD) {
                changes = SnapshotMapper.computeAdditionDiff(snapshot);
            } else if (revType == RevisionType.DEL) {
                changes = previousSnapshot != null ? SnapshotMapper.computeRemovalDiff(previousSnapshot) : Map.of();
            } else {
                changes = previousSnapshot != null ? SnapshotMapper.computeDiff(previousSnapshot, snapshot) : Map.of();
            }

            entries.add(new TimelineEntry(
                    revInfo.getId(),
                    revType.name(),
                    Instant.ofEpochMilli(revInfo.getTimestamp()),
                    revInfo.getActor(),
                    revInfo.getReason(),
                    ChangeCategory.REQUIREMENT,
                    entity.getId(),
                    snapshot,
                    changes));

            if (revType != RevisionType.DEL) {
                previousSnapshot = snapshot;
            }
        }
        return entries;
    }

    private List<TimelineEntry> buildRelationEntries(UUID requirementId) {
        var auditReader = AuditReaderFactory.get(entityManager);

        @SuppressWarnings("unchecked")
        List<Object[]> results = auditReader
                .createQuery()
                .forRevisionsOfEntity(RequirementRelation.class, false, true)
                .add(AuditEntity.disjunction()
                        .add(AuditEntity.relatedId("source").eq(requirementId))
                        .add(AuditEntity.relatedId("target").eq(requirementId)))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        var previousSnapshots = new HashMap<UUID, Map<String, Object>>();
        var entries = new ArrayList<TimelineEntry>();

        for (Object[] row : results) {
            var entity = (RequirementRelation) row[0];
            var revInfo = (GroundControlRevisionEntity) row[1];
            var revType = (RevisionType) row[2];

            var snapshot = SnapshotMapper.fromRelation(entity);
            var prev = previousSnapshots.get(entity.getId());
            Map<String, FieldChange> changes;
            if (revType == RevisionType.ADD) {
                changes = SnapshotMapper.computeAdditionDiff(snapshot);
            } else if (revType == RevisionType.DEL) {
                changes = prev != null ? SnapshotMapper.computeRemovalDiff(prev) : Map.of();
            } else {
                changes = prev != null ? SnapshotMapper.computeDiff(prev, snapshot) : Map.of();
            }

            entries.add(new TimelineEntry(
                    revInfo.getId(),
                    revType.name(),
                    Instant.ofEpochMilli(revInfo.getTimestamp()),
                    revInfo.getActor(),
                    revInfo.getReason(),
                    ChangeCategory.RELATION,
                    entity.getId(),
                    snapshot,
                    changes));

            if (revType != RevisionType.DEL) {
                previousSnapshots.put(entity.getId(), snapshot);
            }
        }
        return entries;
    }

    private List<TimelineEntry> buildTraceabilityLinkEntries(UUID requirementId) {
        var auditReader = AuditReaderFactory.get(entityManager);

        @SuppressWarnings("unchecked")
        List<Object[]> results = auditReader
                .createQuery()
                .forRevisionsOfEntity(TraceabilityLink.class, false, true)
                .add(AuditEntity.relatedId("requirement").eq(requirementId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        var previousSnapshots = new HashMap<UUID, Map<String, Object>>();
        var entries = new ArrayList<TimelineEntry>();

        for (Object[] row : results) {
            var entity = (TraceabilityLink) row[0];
            var revInfo = (GroundControlRevisionEntity) row[1];
            var revType = (RevisionType) row[2];

            var snapshot = SnapshotMapper.fromTraceabilityLink(entity);
            var prev = previousSnapshots.get(entity.getId());
            Map<String, FieldChange> changes;
            if (revType == RevisionType.ADD) {
                changes = SnapshotMapper.computeAdditionDiff(snapshot);
            } else if (revType == RevisionType.DEL) {
                changes = prev != null ? SnapshotMapper.computeRemovalDiff(prev) : Map.of();
            } else {
                changes = prev != null ? SnapshotMapper.computeDiff(prev, snapshot) : Map.of();
            }

            entries.add(new TimelineEntry(
                    revInfo.getId(),
                    revType.name(),
                    Instant.ofEpochMilli(revInfo.getTimestamp()),
                    revInfo.getActor(),
                    revInfo.getReason(),
                    ChangeCategory.TRACEABILITY_LINK,
                    entity.getId(),
                    snapshot,
                    changes));

            if (revType != RevisionType.DEL) {
                previousSnapshots.put(entity.getId(), snapshot);
            }
        }
        return entries;
    }
}

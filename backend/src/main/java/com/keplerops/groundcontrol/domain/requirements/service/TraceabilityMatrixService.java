package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the Traceability Matrix per GC-Q003.
 *
 * <p>This is a <strong>read-only composition</strong> over existing aggregates — no new JPA
 * aggregate, table, or migration is introduced. Requirements (filtered in-project by optional
 * {@code status} and {@code wave}) form the rows; their {@link TraceabilityLink}s are bulk-loaded in a
 * single {@code findByRequirementIdIn} query (no N+1) and grouped in memory into per-requirement cells.
 *
 * <p>The {@code linkType} filter narrows both the projected cells and the returned columns to that one
 * {@link LinkType}; when absent, all five link types are summarized. Gap semantics are documented on
 * {@link TraceabilityMatrixResult}.
 */
@Service
@Transactional(readOnly = true)
public class TraceabilityMatrixService {

    private static final Logger log = LoggerFactory.getLogger(TraceabilityMatrixService.class);

    /** Coverage axes for the canonical "implemented and tested?" audit question (GC-Q003). */
    private static final List<LinkType> COVERAGE_AXES = List.of(LinkType.IMPLEMENTS, LinkType.TESTS);

    private final RequirementRepository requirementRepository;
    private final TraceabilityLinkRepository traceabilityLinkRepository;

    public TraceabilityMatrixService(
            RequirementRepository requirementRepository, TraceabilityLinkRepository traceabilityLinkRepository) {
        this.requirementRepository = requirementRepository;
        this.traceabilityLinkRepository = traceabilityLinkRepository;
    }

    /**
     * Assembles the matrix for a project.
     *
     * @param projectId resolved project UUID (never null)
     * @param wave      optional wave filter (in-memory; matches {@code null} waves only when {@code wave} is null)
     * @param status    optional requirement status filter
     * @param linkType  optional single link-type narrowing; null = all five types
     * @return composed matrix result
     */
    public TraceabilityMatrixResult matrix(UUID projectId, Integer wave, Status status, LinkType linkType) {
        List<Requirement> requirements = status != null
                ? requirementRepository.findByProjectIdAndStatusAndArchivedAtIsNull(projectId, status)
                : requirementRepository.findByProjectIdAndArchivedAtIsNull(projectId);

        List<Requirement> scoped = requirements.stream()
                .filter(r -> wave == null || wave.equals(r.getWave()))
                .sorted(Comparator.comparing(Requirement::getUid, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        List<UUID> ids = scoped.stream().map(Requirement::getId).toList();
        Map<UUID, List<TraceabilityLink>> linksByRequirement =
                ids.isEmpty() ? Map.of() : groupByRequirement(traceabilityLinkRepository.findByRequirementIdIn(ids));

        List<LinkType> activeColumns = linkType != null ? List.of(linkType) : List.of(LinkType.values());

        Map<LinkType, int[]> columnTallies = new EnumMap<>(LinkType.class);
        for (LinkType lt : activeColumns) {
            columnTallies.put(lt, new int[] {0, 0}); // [coveredRequirements, artifactCount]
        }

        List<TraceabilityMatrixResult.MatrixRow> rows = new ArrayList<>(scoped.size());
        int linkedRequirementCount = 0;
        int gapCount = 0;

        for (Requirement requirement : scoped) {
            List<TraceabilityLink> links = linksByRequirement.getOrDefault(requirement.getId(), List.of());
            TraceabilityMatrixResult.MatrixRow row = composeRow(requirement, links, linkType, columnTallies);
            rows.add(row);
            if (!row.cells().isEmpty()) {
                linkedRequirementCount++;
            }
            if (row.hasGap()) {
                gapCount++;
            }
        }

        List<TraceabilityMatrixResult.LinkTypeColumn> columns = new ArrayList<>(activeColumns.size());
        for (LinkType lt : activeColumns) {
            int[] tally = columnTallies.get(lt);
            columns.add(new TraceabilityMatrixResult.LinkTypeColumn(lt, tally[0], scoped.size(), tally[1]));
        }

        log.info(
                "traceability_matrix assembled: project={} rows={} linked={} gaps={}",
                projectId,
                rows.size(),
                linkedRequirementCount,
                gapCount);

        return new TraceabilityMatrixResult(rows, columns, rows.size(), linkedRequirementCount, gapCount);
    }

    private static TraceabilityMatrixResult.MatrixRow composeRow(
            Requirement requirement,
            List<TraceabilityLink> links,
            LinkType linkType,
            Map<LinkType, int[]> columnTallies) {
        List<TraceabilityMatrixResult.MatrixCell> cells = new ArrayList<>();
        // Distinct link types covered, ordered by the LinkType enum for stable output.
        Map<LinkType, Boolean> covered = new EnumMap<>(LinkType.class);

        for (TraceabilityLink link : links) {
            if (linkType != null && link.getLinkType() != linkType) {
                continue;
            }
            cells.add(new TraceabilityMatrixResult.MatrixCell(
                    link.getId(),
                    link.getLinkType(),
                    link.getArtifactType(),
                    link.getArtifactIdentifier(),
                    link.getArtifactTitle(),
                    link.getArtifactUrl(),
                    link.getSyncStatus()));
            covered.put(link.getLinkType(), Boolean.TRUE);
        }

        // Tally columns: count each requirement once per type it covers, plus the artifact count.
        for (TraceabilityMatrixResult.MatrixCell cell : cells) {
            int[] tally = columnTallies.get(cell.linkType());
            if (tally != null) {
                tally[1]++; // artifactCount
            }
        }
        for (LinkType lt : covered.keySet()) {
            int[] tally = columnTallies.get(lt);
            if (tally != null) {
                tally[0]++; // coveredRequirements
            }
        }

        List<LinkType> coveredLinkTypes = new ArrayList<>(covered.keySet());
        boolean hasGap = computeGap(requirement.getStatus(), covered, linkType);

        return new TraceabilityMatrixResult.MatrixRow(
                requirement.getId(),
                requirement.getUid(),
                requirement.getTitle(),
                requirement.getStatus(),
                requirement.getWave(),
                requirement.getPriority(),
                cells,
                coveredLinkTypes,
                hasGap);
    }

    /**
     * A gap is flagged only for ACTIVE requirements (see {@link TraceabilityMatrixResult}). When a single
     * {@code linkType} is requested, that type is the sole coverage axis; otherwise IMPLEMENTS and TESTS are.
     */
    private static boolean computeGap(Status status, Map<LinkType, Boolean> covered, LinkType linkType) {
        if (status != Status.ACTIVE) {
            return false;
        }
        List<LinkType> axes = linkType != null ? List.of(linkType) : COVERAGE_AXES;
        for (LinkType axis : axes) {
            if (!covered.containsKey(axis)) {
                return true;
            }
        }
        return false;
    }

    private static Map<UUID, List<TraceabilityLink>> groupByRequirement(List<TraceabilityLink> links) {
        Map<UUID, List<TraceabilityLink>> map = new LinkedHashMap<>();
        for (TraceabilityLink link : links) {
            map.computeIfAbsent(link.getRequirement().getId(), k -> new ArrayList<>())
                    .add(link);
        }
        return map;
    }
}

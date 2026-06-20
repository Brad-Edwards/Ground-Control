package com.keplerops.groundcontrol.domain.riskcontrol.service;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides C5/C6 coverage queries for GC-T003, extended by GC-H006 with threat-side variants.
 *
 * <p>C5a: scenarios with no mapped controls.
 * C5b: records with no direct mapped controls (+ transitive: records whose every scenario is mapped).
 * C6: catalog controls not mapped to any relevant scenario (transitive-through-record interpretation).
 * C5-threat (GC-H006): threat model entries with no mapped controls.
 * C6-threat (GC-H006): catalog controls not mapped to any threat model entry.
 */
@Service
@Transactional(readOnly = true)
public class RiskControlCoverageService {

    private final RiskControlMappingRepository mappingRepository;
    private final RiskScenarioRepository scenarioRepository;
    private final RiskRegisterRecordRepository recordRepository;
    private final ControlRepository controlRepository;
    private final ThreatModelRepository threatModelRepository;
    private final ControlEffectivenessAssessmentRepository effectivenessRepository;

    public RiskControlCoverageService(
            RiskControlMappingRepository mappingRepository,
            RiskScenarioRepository scenarioRepository,
            RiskRegisterRecordRepository recordRepository,
            ControlRepository controlRepository,
            ThreatModelRepository threatModelRepository,
            ControlEffectivenessAssessmentRepository effectivenessRepository) {
        this.mappingRepository = mappingRepository;
        this.scenarioRepository = scenarioRepository;
        this.recordRepository = recordRepository;
        this.controlRepository = controlRepository;
        this.threatModelRepository = threatModelRepository;
        this.effectivenessRepository = effectivenessRepository;
    }

    /**
     * C5a — Returns risk scenarios in the project that have no {@link
     * com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping} row.
     */
    public List<RiskScenario> findUnmappedScenarios(UUID projectId) {
        var ids = mappingRepository.findUnmappedScenarioIds(projectId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return scenarioRepository.findByIdInAndProjectId(ids, projectId);
    }

    /**
     * C5b — Returns risk register records in the project that are not directly mapped
     * to any control AND (if transitive=true) whose owned scenarios are not all mapped.
     *
     * <p>When {@code transitive=false}, returns records with no direct mapping row only.
     * When {@code transitive=true}, also excludes records whose every owned scenario
     * is itself mapped (i.e. a record is considered indirectly covered if all its
     * scenarios have coverage).
     */
    public List<RiskRegisterRecord> findUnmappedRecords(UUID projectId, boolean transitive) {
        var directlyUnmappedIds = mappingRepository.findDirectlyUnmappedRecordIds(projectId);
        if (directlyUnmappedIds.isEmpty()) {
            return List.of();
        }

        var records = recordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId).stream()
                .filter(r -> directlyUnmappedIds.contains(r.getId()))
                .toList();

        if (!transitive) {
            return records;
        }

        // Transitive filter: also exclude records where every owned scenario is mapped
        var unmappedScenarioIds = mappingRepository.findUnmappedScenarioIds(projectId);
        return records.stream()
                .filter(r -> {
                    var scenarios = r.getRiskScenarios();
                    if (scenarios.isEmpty()) {
                        // No scenarios — record has no transitive coverage
                        return true;
                    }
                    // Record is transitively covered if ALL its scenarios are mapped
                    // (i.e. none appear in the unmapped set). Exclude it from results.
                    boolean allScenariosMapped =
                            scenarios.stream().noneMatch(s -> unmappedScenarioIds.contains(s.getId()));
                    return !allScenariosMapped;
                })
                .toList();
    }

    /**
     * C6 — Returns catalog controls in the project that are not mapped to any relevant
     * scenario (transitive-through-record interpretation per ADR-052).
     *
     * <p>A control is covered if it (or any of its scoped implementations) has a mapping
     * to a scenario directly, OR a mapping to a register record that owns ≥1 scenario.
     */
    public List<Control> findUnmappedControls(UUID projectId) {
        var ids = mappingRepository.findUnmappedControlIds(projectId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(c -> ids.contains(c.getId()))
                .toList();
    }

    /**
     * C5-threat (GC-H006) — Returns threat model entries in the project that have no
     * {@link RiskControlMapping} row.
     */
    public List<ThreatModel> findUnmappedThreats(UUID projectId) {
        var ids = mappingRepository.findUnmappedThreatIds(projectId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(t -> ids.contains(t.getId()))
                .toList();
    }

    /**
     * C6-threat (GC-H006) — Returns catalog controls in the project that have no
     * {@link RiskControlMapping} to a threat model entry (directly or via scoped implementations).
     */
    public List<Control> findControlsUnmappedToThreats(UUID projectId) {
        var ids = mappingRepository.findControlIdsUnmappedToThreats(projectId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(c -> ids.contains(c.getId()))
                .toList();
    }

    /**
     * GC-H006 — Returns threat model entries whose mapped controls have insufficient demonstrated
     * operating effectiveness.
     *
     * <p>A threat is flagged when it has ≥1 mapped control AND none of its mapped controls passes
     * the {@code minEffectiveness} bar within the freshness window.
     *
     * @param projectId            the project scope
     * @param minEffectiveness     minimum required rating (default: {@code EFFECTIVE})
     * @param asOf                 date ceiling for assessments (default: today UTC)
     * @param freshnessWindowDays  maximum age of a qualifying assessment in days (default: 90)
     */
    public List<ThreatModel> findThreatsWithInsufficientControlEffectiveness(
            UUID projectId, ControlEffectivenessRating minEffectiveness, LocalDate asOf, Integer freshnessWindowDays) {
        if (minEffectiveness == null) minEffectiveness = ControlEffectivenessRating.EFFECTIVE;
        if (asOf == null) asOf = LocalDate.now(ZoneOffset.UTC);
        if (freshnessWindowDays == null) freshnessWindowDays = 90;

        var effectivenessRows =
                effectivenessRepository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                        projectId, asOf);

        // Build latest-per-control map (first putIfAbsent wins = most recent per control)
        Map<UUID, ControlEffectivenessAssessment> latestByControl = new LinkedHashMap<>();
        LocalDate freshnessFloor = asOf.minusDays(freshnessWindowDays);
        for (var row : effectivenessRows) {
            if (!row.getAssessedAt().isBefore(freshnessFloor)) {
                latestByControl.putIfAbsent(row.getControl().getId(), row);
            }
        }

        // Get all threat-mapped mappings for the project
        var allMappings = mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(m -> m.getThreatModel() != null)
                .toList();

        // Group mappings by threat model id
        Map<UUID, List<RiskControlMapping>> byThreat = new LinkedHashMap<>();
        for (var m : allMappings) {
            byThreat.computeIfAbsent(m.getThreatModel().getId(), k -> new ArrayList<>())
                    .add(m);
        }

        if (byThreat.isEmpty()) {
            return List.of();
        }

        // A threat is insufficient when NO mapped control passes the bar
        var insufficientThreatIds = new HashSet<UUID>();
        for (var entry : byThreat.entrySet()) {
            var threatId = entry.getKey();
            var mappings = entry.getValue();
            boolean anyPasses = false;
            for (var mapping : mappings) {
                UUID controlId = resolveControlId(mapping);
                if (controlId == null) continue;
                var eff = latestByControl.get(controlId);
                if (eff != null && meetsMinEffectiveness(eff.getOperatingEffectiveness(), minEffectiveness)) {
                    anyPasses = true;
                    break;
                }
            }
            if (!anyPasses) {
                insufficientThreatIds.add(threatId);
            }
        }

        return threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(t -> insufficientThreatIds.contains(t.getId()))
                .toList();
    }

    private UUID resolveControlId(RiskControlMapping mapping) {
        if (mapping.getControl() != null) {
            return mapping.getControl().getId();
        }
        if (mapping.getScopedImplementation() != null
                && mapping.getScopedImplementation().getControl() != null) {
            return mapping.getScopedImplementation().getControl().getId();
        }
        return null;
    }

    private boolean meetsMinEffectiveness(ControlEffectivenessRating actual, ControlEffectivenessRating min) {
        return effectivenessRank(actual) >= effectivenessRank(min);
    }

    /**
     * Explicit effectiveness ordering (higher rank = more effective). Declared explicitly rather
     * than via {@code Enum.ordinal()} so reordering the enum constants cannot silently invert the
     * "meets minimum effectiveness" comparison.
     */
    private static int effectivenessRank(ControlEffectivenessRating rating) {
        return switch (rating) {
            case EFFECTIVE -> 2;
            case PARTIALLY_EFFECTIVE -> 1;
            case INEFFECTIVE -> 0;
        };
    }
}

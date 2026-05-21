package com.keplerops.groundcontrol.domain.riskcontrol.service;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides C5/C6 coverage queries for GC-T003.
 *
 * <p>C5a: scenarios with no mapped controls.
 * C5b: records with no direct mapped controls (+ transitive: records whose every scenario is mapped).
 * C6: catalog controls not mapped to any relevant scenario (transitive-through-record interpretation).
 */
@Service
@Transactional(readOnly = true)
public class RiskControlCoverageService {

    private final RiskControlMappingRepository mappingRepository;
    private final RiskScenarioRepository scenarioRepository;
    private final RiskRegisterRecordRepository recordRepository;
    private final ControlRepository controlRepository;

    public RiskControlCoverageService(
            RiskControlMappingRepository mappingRepository,
            RiskScenarioRepository scenarioRepository,
            RiskRegisterRecordRepository recordRepository,
            ControlRepository controlRepository) {
        this.mappingRepository = mappingRepository;
        this.scenarioRepository = scenarioRepository;
        this.recordRepository = recordRepository;
        this.controlRepository = controlRepository;
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
}

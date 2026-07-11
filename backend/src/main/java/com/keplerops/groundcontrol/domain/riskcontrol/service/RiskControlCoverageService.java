package com.keplerops.groundcontrol.domain.riskcontrol.service;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides C5/C6 coverage queries for GC-T003, extended by GC-H006 with threat-side variants.
 *
 * <p>C5a: scenarios with no mapped controls.
 * C6: catalog controls not mapped to any relevant scenario.
 * C5-threat (GC-H006): threat model entries with no mapped controls.
 * C6-threat (GC-H006): catalog controls not mapped to any threat model entry.
 */
@Service
@Transactional(readOnly = true)
public class RiskControlCoverageService {

    private final RiskControlMappingRepository mappingRepository;
    private final RiskScenarioRepository scenarioRepository;
    private final ControlRepository controlRepository;
    private final ThreatModelRepository threatModelRepository;

    public RiskControlCoverageService(
            RiskControlMappingRepository mappingRepository,
            RiskScenarioRepository scenarioRepository,
            ControlRepository controlRepository,
            ThreatModelRepository threatModelRepository) {
        this.mappingRepository = mappingRepository;
        this.scenarioRepository = scenarioRepository;
        this.controlRepository = controlRepository;
        this.threatModelRepository = threatModelRepository;
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
     * C6 — Returns catalog controls in the project that are not mapped to any relevant
     * scenario (directly or via a scoped implementation).
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
     * {@link com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping} row.
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
     * C6-threat (GC-H006) — Returns catalog controls in the project that have no {@link
     * com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping} to a threat model
     * entry (directly or via scoped implementations).
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
}

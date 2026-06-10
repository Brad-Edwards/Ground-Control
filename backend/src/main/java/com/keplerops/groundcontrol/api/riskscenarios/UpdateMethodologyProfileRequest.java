package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.riskscenarios.model.CrosswalkEntry;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyProfileStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record UpdateMethodologyProfileRequest(
        @Size(max = 200) String name,
        @Size(max = 50) String version,
        MethodologyFamily family,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        MethodologyProfileStatus status,
        Map<String, Object> treatmentStrategyVocabulary,
        @Valid List<CrosswalkEntry> crosswalkEntries) {}

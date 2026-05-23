package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyProfileStatus;
import java.util.Map;

public record UpdateMethodologyProfileCommand(
        String name,
        String version,
        MethodologyFamily family,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        MethodologyProfileStatus status,
        Map<String, Object> treatmentStrategyVocabulary) {}

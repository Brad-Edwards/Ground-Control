package com.keplerops.groundcontrol.domain.riskscenarios.service;

public record UpdateRiskScenarioCommand(
        String title, String threat, String method, String asset, String effect, String timeHorizon) {}

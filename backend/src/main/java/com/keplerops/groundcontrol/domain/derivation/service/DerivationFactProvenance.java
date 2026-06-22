package com.keplerops.groundcontrol.domain.derivation.service;

import java.time.Instant;

public record DerivationFactProvenance(
        String adapterId,
        String toolName,
        String toolVersion,
        String rulesetName,
        String rulesetVersion,
        String commitSha,
        Instant derivedAt) {}

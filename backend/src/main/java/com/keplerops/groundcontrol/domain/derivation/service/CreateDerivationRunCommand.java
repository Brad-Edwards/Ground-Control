package com.keplerops.groundcontrol.domain.derivation.service;

import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import java.util.List;
import java.util.UUID;

public record CreateDerivationRunCommand(
        UUID projectId,
        DerivationScopeMode scopeMode,
        String commitSha,
        String baseCommitSha,
        List<String> paths,
        List<String> languages,
        List<String> surfaces) {}

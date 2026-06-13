package com.keplerops.groundcontrol.domain.derivation.service;

import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import java.util.List;
import java.util.Set;

public record DerivationScope(
        DerivationScopeMode mode,
        String commitSha,
        String baseCommitSha,
        List<String> paths,
        Set<String> languages,
        Set<String> surfaces) {}

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
        List<String> surfaces,
        List<BoundaryDeclaration> declaredBoundaries) {

    public CreateDerivationRunCommand(
            UUID projectId,
            DerivationScopeMode scopeMode,
            String commitSha,
            String baseCommitSha,
            List<String> paths,
            List<String> languages,
            List<String> surfaces) {
        this(projectId, scopeMode, commitSha, baseCommitSha, paths, languages, surfaces, List.of());
    }

    public CreateDerivationRunCommand {
        declaredBoundaries = declaredBoundaries == null ? List.of() : List.copyOf(declaredBoundaries);
    }
}

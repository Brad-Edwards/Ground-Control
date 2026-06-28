package com.keplerops.groundcontrol.domain.derivation.service;

import com.keplerops.groundcontrol.domain.derivation.model.DerivationCaptureLimit;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationRun;
import com.keplerops.groundcontrol.domain.derivation.model.SystemModelFact;
import java.util.List;

public record DerivationRunResult(
        DerivationRun run,
        List<SystemModelFact> facts,
        List<DerivationCaptureLimit> captureLimits,
        BoundaryModelBuildResult boundaryModel) {

    public DerivationRunResult {
        facts = facts == null ? List.of() : List.copyOf(facts);
        captureLimits = captureLimits == null ? List.of() : List.copyOf(captureLimits);
    }

    public DerivationRunResult(
            DerivationRun run, List<SystemModelFact> facts, List<DerivationCaptureLimit> captureLimits) {
        this(run, facts, captureLimits, null);
    }
}

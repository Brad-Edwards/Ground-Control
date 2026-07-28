package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactReadiness;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import java.util.List;

/**
 * Stateless helpers split out of {@link ResearchRunService} under issue #1467
 * for the 500-LOC limit (docs/CODING_STANDARDS.md).
 *
 * Every method here touches no instance state, so it is static and the
 * original keeps a static import for each -- call sites are unchanged.
 */
final class ResearchRunServiceSupport {

    private ResearchRunServiceSupport() {}

    static ResearchArtifactReadiness computeReadiness(List<ResearchRunArtifact> artifacts, ResearchArtifactType type) {
        ResearchArtifactReadiness fallback = ResearchArtifactReadiness.MISSING;
        for (var a : artifacts) {
            if (a.getArtifactType() != type) {
                continue;
            }
            if (a.getStatus() == ResearchArtifactStatus.ACTIVE) {
                return ResearchArtifactReadiness.READY;
            }
            if (a.getStatus() == ResearchArtifactStatus.FAILED) {
                fallback = ResearchArtifactReadiness.FAILED;
            } else if (a.getStatus() == ResearchArtifactStatus.SUPERSEDED
                    && fallback != ResearchArtifactReadiness.FAILED) {
                fallback = ResearchArtifactReadiness.SUPERSEDED;
            }
        }
        return fallback;
    }
}
